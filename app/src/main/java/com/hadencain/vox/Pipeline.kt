package com.hadencain.vox

import android.util.Log
import android.widget.Toast
import com.hadencain.vox.asr.AudioCapture
import com.hadencain.vox.asr.WhisperBridge
import com.hadencain.vox.cleanup.CleanupEngine
import com.hadencain.vox.core.*
import com.hadencain.vox.inject.InjectResult
import com.hadencain.vox.inject.VoxAccessibilityService
import com.hadencain.vox.ui.BubbleState
import kotlinx.coroutines.*
import java.io.File

enum class PipelineState { IDLE, WAKING, RECORDING, PROCESSING }

/** The state machine: IDLE -> RECORDING -> PROCESSING -> (inject) -> IDLE.
 *  Owns model lifecycle: lazy load, idle unload after settings.modelIdleUnloadMs. */
class Pipeline(private val service: VoxService, private val settings: VoxSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var state = PipelineState.IDLE
    @Volatile private var rawMode = false
    private var capture: AudioCapture? = null
    private var whisperHandle = 0L
    private var cleanup: CleanupEngine? = null
    private var unloadJob: Job? = null
    private var targetPackage: String? = null
    private val history = History(File(service.filesDir, "history.jsonl"), settings.historyMax)

    private val whisperModel = File(service.filesDir, "models/ggml-small-q5_1.bin")
    private val gemmaModel = File(service.filesDir, "models/gemma3-1b-it-int4.task")

    fun onTap() {
        when (state) {
            PipelineState.IDLE, PipelineState.WAKING -> startTake()
            PipelineState.RECORDING -> stopTake()
            PipelineState.PROCESSING -> {} // ignore taps while working
        }
    }

    fun onLongPress() { toast("AI edit arrives in a later task") }

    /** Tap the caption during a take -> this take is verbatim (desktop raw-marker analog). */
    fun onCaptionTap() {
        if (state != PipelineState.RECORDING) return
        rawMode = !rawMode
        service.bubble.setState(if (rawMode) BubbleState.RECORDING_RAW else BubbleState.RECORDING)
    }

    private fun startTake() {
        val a11y = VoxAccessibilityService.instance
        if (a11y == null) { service.bubble.setState(BubbleState.DISABLED); toast("Enable Vox in Accessibility settings"); return }
        state = PipelineState.WAKING
        service.bubble.setState(BubbleState.WAKING)
        scope.launch {
            ensureModelsLoaded() ?: run { fail("models not loaded"); return@launch }
            targetPackage = a11y.foregroundPackage
            rawMode = false
            capture = AudioCapture(::stopTake, settings.silenceTimeoutMs).also { it.start() }
            state = PipelineState.RECORDING
            service.bubble.setState(BubbleState.RECORDING)
            service.bubble.setCaption("listening…")
        }
    }

    private fun stopTake() {
        if (state != PipelineState.RECORDING) return
        state = PipelineState.PROCESSING
        service.bubble.setState(BubbleState.PROCESSING)
        scope.launch {
            try {
                val samples = capture!!.stop(); capture = null
                if (samples.size < 8000) { finishIdle(); return@launch }  // <0.5s: nothing real
                val bias = Dictionary.biasPrompt(settings.vocab)
                val raw = withTimeoutOrNull(30_000) {
                    WhisperBridge.transcribe(whisperHandle, samples, bias)
                }?.trim()
                if (raw == null) { fail("transcription timed out"); return@launch }
                if (raw.isEmpty() || Commands.isCancel(raw, settings.enableCommands)) {
                    finishIdle(); return@launch
                }
                val appCtx = if (settings.enableContext) ContextMap.category(targetPackage) else null
                val cleaned = if (rawMode || !settings.enableCleanup) raw else
                    withTimeoutOrNull(20_000) { cleanup!!.clean(raw, appCtx) } ?: raw
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

    private suspend fun ensureModelsLoaded(): Unit? = withContext(Dispatchers.IO) {
        unloadJob?.cancel()
        if (whisperHandle == 0L) {
            if (!whisperModel.exists()) return@withContext null
            whisperHandle = WhisperBridge.init(whisperModel.path)
            if (whisperHandle == 0L) return@withContext null
        }
        if (cleanup == null && settings.enableCleanup) {
            if (!gemmaModel.exists()) return@withContext null
            cleanup = try {
                CleanupEngine(service, gemmaModel.path)
            } catch (e: Exception) {
                Log.e("Vox", "cleanup engine failed to load", e)
                return@withContext null
            }
        }
        Unit
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(settings.modelIdleUnloadMs)
            if (whisperHandle != 0L) { WhisperBridge.release(whisperHandle); whisperHandle = 0L }
            cleanup?.close(); cleanup = null
            Log.i("Vox", "models unloaded after idle")
        }
    }

    private fun finishIdle() {
        state = PipelineState.IDLE
        service.bubble.setState(BubbleState.IDLE)
        service.bubble.setCaption(null)
        scheduleUnload()
    }

    private fun fail(msg: String) {
        Log.e("Vox", "pipeline: $msg")
        state = PipelineState.IDLE
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
        scope.cancel()
        capture?.stop()
        if (whisperHandle != 0L) { WhisperBridge.release(whisperHandle); whisperHandle = 0L }
        cleanup?.close(); cleanup = null
    }
}
