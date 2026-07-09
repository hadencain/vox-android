package com.hadencain.vox.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/** Pure-logic endpointer: fires once trailing silence exceeds timeout, but only after
 *  speech has been heard at least once (so it can't fire on a take that never started). */
class SilenceDetector(private val timeoutMs: Long, sampleRate: Int) {
    private val threshold = 0.01f
    private var heardSpeech = false
    private var lastSpeechMs = 0L
    private var fired = false

    fun feed(chunk: FloatArray, nowMs: Long): Boolean {
        if (fired) return false
        var sum = 0.0
        for (s in chunk) sum += s * s
        val rms = sqrt(sum / chunk.size).toFloat()
        if (rms >= threshold) { heardSpeech = true; lastSpeechMs = nowMs }
        if (heardSpeech && nowMs - lastSpeechMs >= timeoutMs) { fired = true; return true }
        return false
    }
}

class AudioCapture(
    private val onSilenceTimeout: () -> Unit,
    private val silenceTimeoutMs: Long,
) {
    private val sampleRate = 16000
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val buffer = ArrayList<Float>(sampleRate * 60)
    @Volatile private var running = false

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is gated by onboarding before any capture
    fun start() {
        if (running) return
        synchronized(buffer) { buffer.clear() }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuf, sampleRate))  // >= 1s of headroom
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            record?.release(); record = null
            throw IllegalStateException("AudioRecord failed to initialize (mic busy or unavailable)")
        }
        val detector = SilenceDetector(silenceTimeoutMs, sampleRate)
        running = true
        record!!.startRecording()
        thread = Thread {
            val chunk = FloatArray(1600)  // 100ms
            while (running) {
                val n = record?.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING) ?: break
                if (n <= 0) continue
                synchronized(buffer) { for (i in 0 until n) buffer.add(chunk[i]) }
                if (detector.feed(chunk.copyOf(n), System.currentTimeMillis())) onSilenceTimeout()
            }
        }.also { it.start() }
    }

    fun snapshot(): FloatArray = synchronized(buffer) { buffer.toFloatArray() }

    fun stop(): FloatArray {
        running = false
        if (Thread.currentThread() !== thread) thread?.join(500); thread = null
        record?.let { it.stop(); it.release() }; record = null
        return snapshot()
    }
}
