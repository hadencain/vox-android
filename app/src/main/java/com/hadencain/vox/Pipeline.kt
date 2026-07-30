package com.hadencain.vox

import android.util.Log
import android.widget.Toast
import com.hadencain.vox.asr.AudioCapture
import com.hadencain.vox.asr.WhisperBridge
import com.hadencain.vox.cleanup.CleanupEngine
import com.hadencain.vox.core.*
import com.hadencain.vox.inject.InjectResult
import com.hadencain.vox.inject.SelectionInfo
import com.hadencain.vox.inject.VoxAccessibilityService
import com.hadencain.vox.ui.BubbleState
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

enum class PipelineState { IDLE, WAKING, RECORDING, PROCESSING }

/** The state machine: IDLE -> RECORDING -> PROCESSING -> (inject) -> IDLE.
 *  Owns model lifecycle: lazy load, idle unload after settings.modelIdleUnloadMs.
 *
 *  Concurrency model:
 *  - [stateDispatcher] is a single-threaded executor that owns the state machine. Every
 *    public entry point (onTap, onLongPress, onCaptionTap, the AudioCapture silence-timeout
 *    callback) is a thin, non-suspending function that launches its handler onto
 *    [stateDispatcher]. Because that dispatcher has exactly one worker thread, handlers are
 *    strictly serialized -- check-then-act on [state] can't race, and the mutable fields
 *    below are safe to read/write without synchronization because only stateDispatcher-
 *    confined code ever touches them.
 *  - [asrDispatcher] / [llmDispatcher] are single-threaded executors that serialize all
 *    native calls into WhisperBridge / CleanupEngine respectively. A `withTimeoutOrNull`
 *    around a `withContext(asrDispatcher) { ... }` block can abandon the *coroutine* on
 *    timeout, but the underlying native call keeps running to completion on its own
 *    dedicated thread; any later release/close call for that same native handle is
 *    submitted to the same single-threaded executor and queues behind it, so it can never
 *    run concurrently with (or before) the call it would otherwise race.
 */
class Pipeline(private val service: VoxService, private val settings: VoxSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Confines all state-machine reads/writes to one worker thread.
    private val stateDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vox-pipeline") }.asCoroutineDispatcher()
    // Serializes all WhisperBridge native calls.
    private val asrDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vox-whisper") }.asCoroutineDispatcher()
    // Serializes all CleanupEngine native calls.
    private val llmDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vox-gemma") }.asCoroutineDispatcher()

    // @Volatile because VoxService reads this cross-thread (isIdle-style checks). Written
    // only from stateDispatcher.
    @Volatile private var state = PipelineState.IDLE

    // Sanctioned cross-thread read of the @Volatile `state` field only -- never read the
    // stateDispatcher-confined fields below from outside stateDispatcher. Used by
    // VoxService's a11y-revocation watcher (main thread) to decide whether it's safe to
    // repaint the bubble without racing an in-flight take.
    val isIdle: Boolean get() = state == PipelineState.IDLE

    // Below fields: read/written ONLY on stateDispatcher. No @Volatile needed -- thread
    // confinement is the guarantee, not memory visibility annotations.
    private var rawMode = false
    private var capture: AudioCapture? = null
    private var whisperHandle = 0L
    private var cleanup: CleanupEngine? = null
    private var unloadJob: Job? = null
    private var targetPackage: String? = null
    private var partialsJob: Job? = null
    private var aiEditSelection: SelectionInfo? = null
    private var aiEditMode = false

    private val history = History(File(service.filesDir, "history.jsonl"), settings.historyMax)

    private val whisperModel = File(service.filesDir, "models/ggml-small-q5_1.bin")
    private val gemmaModel = File(service.filesDir, "models/gemma3-1b-it-int4.task")

    fun onTap() {
        scope.launch(stateDispatcher) { handleTap() }
    }

    fun onLongPress() {
        scope.launch(stateDispatcher) { handleLongPress() }
    }

    /** Tap the caption during a take -> this take is verbatim (desktop raw-marker analog). */
    fun onCaptionTap() {
        scope.launch(stateDispatcher) { handleCaptionTap() }
    }

    private fun handleTap() {
        when (state) {
            PipelineState.IDLE -> handleStartTake()
            PipelineState.WAKING -> {} // models still loading -- ignore
            PipelineState.RECORDING -> handleStopTake()
            PipelineState.PROCESSING -> {} // ignore taps while working
        }
    }

    /** Runs on stateDispatcher (launched from onLongPress). Captures the selection at
     *  trigger-time (spec), then reuses the normal take-start path with aiEditMode set. */
    private suspend fun handleLongPress() {
        if (state != PipelineState.IDLE) return
        val a11y = VoxAccessibilityService.instance
        if (a11y == null) { service.bubble.setState(BubbleState.DISABLED); toast("Enable Vox in Accessibility settings"); openA11ySettings(); return }
        aiEditSelection = withContext(Dispatchers.Main) { a11y.readSelection() }
        aiEditMode = true
        handleStartTake()
        if (state == PipelineState.IDLE) return  // start bailed (e.g. a11y revoked) — don't paint a caption over the DISABLED bubble
        service.bubble.setCaption(takeStartCaption())
    }

    /** Caption shown while WAKING/RECORDING -- mode-dependent so the async model-load
     *  continuation in handleStartTake doesn't stomp the AI-edit prompt with "listening…". */
    private fun takeStartCaption(): String = when {
        !aiEditMode -> "listening…"
        aiEditSelection?.text?.isNotEmpty() == true -> "AI edit: speak an instruction…"
        else -> "AI generate: speak what you want…"
    }

    private fun handleCaptionTap() {
        // Raw and AI-edit are mutually exclusive: the aiedit history write in handleStopTake
        // is never redacted, so letting raw mode toggle on during an AI-edit take would paint
        // a verbatim/privacy promise (RECORDING_RAW) the pipeline doesn't honor.
        if (state != PipelineState.RECORDING || aiEditMode) return
        rawMode = !rawMode
        service.bubble.setState(if (rawMode) BubbleState.RECORDING_RAW else BubbleState.RECORDING)
    }

    /** Runs on stateDispatcher (launched from handleTap, which itself runs there). */
    private fun handleStartTake() {
        val a11y = VoxAccessibilityService.instance
        if (a11y == null) {
            aiEditMode = false; aiEditSelection = null
            service.bubble.setState(BubbleState.DISABLED); toast("Enable Vox in Accessibility settings"); openA11ySettings(); return
        }
        state = PipelineState.WAKING
        service.bubble.setState(BubbleState.WAKING)
        scope.launch(stateDispatcher) {
            try {
                ensureModelsLoaded(requireLlm = aiEditMode) ?: run { fail("models not loaded"); return@launch }
                targetPackage = a11y.foregroundPackage
                rawMode = false
                val newCapture = AudioCapture(
                    onSilenceTimeout = { scope.launch(stateDispatcher) { handleStopTake() } },
                    silenceTimeoutMs = settings.silenceTimeoutMs,
                    onLevel = { service.bubble.setAudioLevel(it) },
                )
                newCapture.start()
                capture = newCapture
                state = PipelineState.RECORDING
                service.bubble.setState(BubbleState.RECORDING)
                if (settings.enableHaptics) Haptics.start(service)
                service.bubble.setCaption(takeStartCaption())
                // Capture the confined fields we need ONCE, here on stateDispatcher, into
                // local vals passed into the loop below -- the loop itself runs off
                // stateDispatcher (so it can never block a tap) and must never read
                // `capture`/`whisperHandle` directly, since those are thread-confined.
                val partialsCapture = newCapture
                val partialsHandle = whisperHandle
                partialsJob = scope.launch {
                    // Runs on the default dispatcher, not stateDispatcher. `state` is
                    // @Volatile, so reading it cross-thread here is acceptable for this
                    // display-only loop -- worst case is one stale/skipped caption update.
                    while (isActive && state == PipelineState.RECORDING) {
                        delay(1500)
                        if (!isActive || state != PipelineState.RECORDING) break
                        // Cap each partial snapshot to the trailing 30s -- long takes would
                        // otherwise re-transcribe an ever-growing buffer every 1.5s (thermal/
                        // battery cost that only pays for a display-only caption). The FINAL
                        // transcribe in handleStopTake still uses the full, uncapped buffer.
                        val snap0 = partialsCapture.snapshot()
                        val snap = if (snap0.size > 16000 * 30) snap0.copyOfRange(snap0.size - 16000 * 30, snap0.size) else snap0
                        if (snap.size < 16000) continue  // wait for >=1s of audio
                        // All native WhisperBridge calls must go through asrDispatcher --
                        // whisper_full is not reentrant on one context.
                        val partial = withContext(asrDispatcher) {
                            WhisperBridge.transcribe(partialsHandle, snap, null)
                        }.trim()
                        if (state == PipelineState.RECORDING && partial.isNotEmpty())
                            service.bubble.setCaption(partial)
                    }
                }
            } catch (e: Exception) {
                Log.e("Vox", "start take failed", e); fail(e.message ?: "error")
            }
        }
    }

    /** Runs on stateDispatcher (launched from handleTap or the silence-timeout callback). */
    private fun handleStopTake() {
        if (state != PipelineState.RECORDING) return
        state = PipelineState.PROCESSING
        service.bubble.setState(BubbleState.PROCESSING)
        if (settings.enableHaptics) Haptics.stop(service)
        scope.launch(stateDispatcher) {
            try {
                // Cancel the partials loop before the final transcribe. The serial
                // asrDispatcher already prevents concurrent native access, but without this
                // a queued partial would sit ahead of the final transcribe on that
                // dispatcher and delay it. cancelAndJoin cooperates at the loop's delay()
                // and withContext suspension points.
                partialsJob?.cancelAndJoin(); partialsJob = null
                val samples = capture!!.stop(); capture = null
                if (samples.size < 8000) { finishIdle(); return@launch }  // <0.5s: nothing real
                val bias = Dictionary.biasPrompt(settings.vocab)
                val raw = withTimeoutOrNull(30_000) {
                    withContext(asrDispatcher) { WhisperBridge.transcribe(whisperHandle, samples, bias) }
                }?.trim()
                if (raw == null) { fail("transcription timed out"); return@launch }
                if (raw.isEmpty() || Commands.isCancel(raw, settings.enableCommands)) {
                    finishIdle(); return@launch
                }
                if (aiEditMode) {
                    aiEditMode = false
                    val sel = aiEditSelection; aiEditSelection = null
                    if (cleanup == null) { fail("AI edit needs the cleanup model"); return@launch }
                    val editResult = withTimeoutOrNull(30_000) {
                        withContext(llmDispatcher) { cleanup!!.aiEdit(raw, sel?.text) }
                    } ?: ""
                    if (editResult.isEmpty()) { fail("AI edit produced nothing"); return@launch }
                    val outcome = withContext(Dispatchers.Main) {
                        val svc = VoxAccessibilityService.instance ?: return@withContext InjectResult.NO_TARGET
                        if (sel != null && sel.text.isNotEmpty()) svc.replaceSelection(sel, editResult)
                        else svc.injectText(editResult)
                    }
                    if (outcome != InjectResult.INJECTED) {
                        withContext(Dispatchers.Main) { copyToClipboard(editResult) }
                        toast("Couldn't apply edit — result copied to clipboard")
                    }
                    if (settings.saveHistory) history.append(HistoryEntry(
                        System.currentTimeMillis(), raw, editResult, targetPackage, "aiedit"))
                    // Only tick on a real injection -- the fallback (clipboard copy) already
                    // has its own toast, and a success haptic there would misreport failure.
                    if (settings.enableHaptics && outcome == InjectResult.INJECTED) Haptics.done(service)
                    finishIdle(); return@launch
                }
                val appCtx = if (settings.enableContext) ContextMap.category(targetPackage) else null
                val cleaned = if (rawMode || !settings.enableCleanup) raw else
                    withTimeoutOrNull(20_000) { withContext(llmDispatcher) { cleanup!!.clean(raw, appCtx) } } ?: raw
                val final = Dictionary.applyCorrections(cleaned, settings.corrections)
                val result = withContext(Dispatchers.Main) {
                    VoxAccessibilityService.instance?.injectText(final) ?: InjectResult.NO_TARGET
                }
                when (result) {
                    InjectResult.INJECTED -> {}
                    InjectResult.SECURE_FIELD -> toast("Vox can't type into secure fields")
                    else -> {
                        withContext(Dispatchers.Main) { copyToClipboard(final) }
                        toast("No text field focused — copied to clipboard")
                    }
                }
                if (settings.saveHistory) history.append(HistoryEntry(
                    System.currentTimeMillis(), raw, final, targetPackage,
                    if (rawMode) "raw" else "dictate"))
                // Only tick on a real injection -- the fallback (clipboard copy) already has
                // its own toast, and a success haptic there would misreport failure.
                if (settings.enableHaptics && result == InjectResult.INJECTED) Haptics.done(service)
                finishIdle()
            } catch (e: Exception) {
                Log.e("Vox", "take failed", e); fail(e.message ?: "error")
            }
        }
    }

    /** Called only from within stateDispatcher-confined coroutines. */
    private suspend fun ensureModelsLoaded(requireLlm: Boolean = false): Unit? {
        unloadJob?.cancel()
        if (whisperHandle == 0L) {
            if (!whisperModel.exists()) return null
            whisperHandle = withContext(asrDispatcher) { WhisperBridge.init(whisperModel.path) }
            if (whisperHandle == 0L) return null
        }
        if (cleanup == null && (settings.enableCleanup || requireLlm)) {
            if (!gemmaModel.exists()) return null
            cleanup = try {
                withContext(llmDispatcher) { CleanupEngine(service, gemmaModel.path) }
            } catch (e: Exception) {
                Log.e("Vox", "cleanup engine failed to load", e)
                return null
            }
        }
        return Unit
    }

    /** Called only from within stateDispatcher-confined code (finishIdle/fail). */
    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch(stateDispatcher) {
            delay(settings.modelIdleUnloadMs)
            // Swap the fields to their cleared state BEFORE releasing, then release under
            // NonCancellable. If cancellation raced us here (e.g. a take started right as
            // the delay elapsed), the fields are already zeroed/nulled -- no other coroutine
            // can observe a freed-but-nonzero handle, and shutdown() can't double-release
            // something we're mid-release on.
            val h = whisperHandle; whisperHandle = 0L
            val c = cleanup; cleanup = null
            withContext(NonCancellable) {
                if (h != 0L) withContext(asrDispatcher) { WhisperBridge.release(h) }
                c?.let { withContext(llmDispatcher) { it.close() } }
            }
            Log.i("Vox", "models unloaded after idle")
        }
    }

    private fun finishIdle() {
        state = PipelineState.IDLE
        aiEditMode = false
        aiEditSelection = null
        service.bubble.setState(BubbleState.IDLE)
        service.bubble.setCaption(null)
        scheduleUnload()
    }

    private fun fail(msg: String) {
        Log.e("Vox", "pipeline: $msg")
        state = PipelineState.IDLE
        aiEditMode = false
        aiEditSelection = null
        service.bubble.setState(BubbleState.ERROR)
        service.bubble.setCaption("⚠ $msg")
        if (settings.enableHaptics) Haptics.error(service)
        toast("Vox: $msg")
        scheduleUnload()
        // Some OEM builds let the user suppress Vox's toasts entirely, which made failures
        // invisible (observed on Samsung). The caption is the fallback surface -- clear it
        // after 5s, but only if nothing else has started a new take in the meantime.
        scope.launch(stateDispatcher) {
            delay(5000)
            if (state == PipelineState.IDLE) service.bubble.setCaption(null)
        }
    }

    /** Deep-link into system Accessibility settings so the user can re-enable Vox
     *  without hunting for it. Fired whenever a take is blocked by a11y revocation. */
    private fun openA11ySettings() {
        service.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun copyToClipboard(text: String) {
        val cm = service.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("vox", text))
    }

    private fun toast(msg: String) = scope.launch(Dispatchers.Main) {
        Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
    }

    fun shutdown() {
        // Confine teardown to stateDispatcher so it serializes behind any in-flight take.
        // runBlocking is a fresh scope — unaffected by scope.cancel(); bounded below by a
        // hard timeout so a stuck native call can't turn process death into an ANR.
        val completed = runBlocking {
            withTimeoutOrNull(5_000) {
                withContext(stateDispatcher) {
                    // If shutdown races a live take, stop the partials loop before releasing
                    // the whisper handle below -- otherwise a queued/in-flight partial could
                    // call WhisperBridge.transcribe with a handle that's about to be freed.
                    partialsJob?.cancelAndJoin(); partialsJob = null
                    capture?.stop(); capture = null
                    // Same swap-then-release-under-NonCancellable idiom as scheduleUnload:
                    // zero the fields first so a timeout here can't leave a freed-but-nonzero
                    // handle, and can't race scheduleUnload's own release of the same handle.
                    val h = whisperHandle; whisperHandle = 0L
                    val c = cleanup; cleanup = null
                    withContext(NonCancellable) {
                        if (h != 0L) withContext(asrDispatcher) { WhisperBridge.release(h) }
                        c?.let { withContext(llmDispatcher) { it.close() } }
                    }
                }
            }
        }
        if (completed == null) Log.w("Vox", "teardown timed out; leaking native handles to avoid ANR")
        scope.cancel()
        stateDispatcher.close(); asrDispatcher.close(); llmDispatcher.close()
    }
}
