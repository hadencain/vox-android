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
        if (a11y == null) { service.bubble.setState(BubbleState.DISABLED); toast("Enable Vox in Accessibility settings"); return }
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
        if (state != PipelineState.RECORDING) return
        rawMode = !rawMode
        service.bubble.setState(if (rawMode) BubbleState.RECORDING_RAW else BubbleState.RECORDING)
    }

    /** Runs on stateDispatcher (launched from handleTap, which itself runs there). */
    private fun handleStartTake() {
        val a11y = VoxAccessibilityService.instance
        if (a11y == null) {
            aiEditMode = false; aiEditSelection = null
            service.bubble.setState(BubbleState.DISABLED); toast("Enable Vox in Accessibility settings"); return
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
                )
                newCapture.start()
                capture = newCapture
                state = PipelineState.RECORDING
                service.bubble.setState(BubbleState.RECORDING)
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
                        val snap = partialsCapture.snapshot()
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
            if (whisperHandle != 0L) {
                withContext(asrDispatcher) { WhisperBridge.release(whisperHandle) }
                whisperHandle = 0L
            }
            withContext(llmDispatcher) { cleanup?.close() }
            cleanup = null
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
        service.bubble.setCaption(null)
        toast("Vox: $msg")
        scheduleUnload()
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
        // runBlocking is a fresh scope — unaffected by scope.cancel(); bounded by take length.
        runBlocking {
            withContext(stateDispatcher) {
                // If shutdown races a live take, stop the partials loop before releasing
                // the whisper handle below -- otherwise a queued/in-flight partial could
                // call WhisperBridge.transcribe with a handle that's about to be freed.
                partialsJob?.cancelAndJoin(); partialsJob = null
                capture?.stop(); capture = null
                if (whisperHandle != 0L) {
                    withContext(asrDispatcher) { WhisperBridge.release(whisperHandle) }
                    whisperHandle = 0L
                }
                cleanup?.let { c -> withContext(llmDispatcher) { c.close() } }
                cleanup = null
            }
        }
        scope.cancel()
        stateDispatcher.close(); asrDispatcher.close(); llmDispatcher.close()
    }
}
