package com.hadencain.vox.asr

import org.junit.Assert.*
import org.junit.Test

class SilenceDetectorTest {
    private val loud = FloatArray(1600) { 0.5f }   // 100ms of speech-level audio
    private val quiet = FloatArray(1600) { 0.001f }

    @Test fun `fires only after continuous trailing silence`() {
        val d = SilenceDetector(timeoutMs = 300, sampleRate = 16000)
        var t = 0L
        assertFalse(d.feed(loud, t))
        t += 100; assertFalse(d.feed(quiet, t))
        t += 100; assertFalse(d.feed(quiet, t))
        t += 250; assertTrue(d.feed(quiet, t))
    }
    @Test fun `speech resets the clock`() {
        val d = SilenceDetector(timeoutMs = 300, sampleRate = 16000)
        var t = 0L
        d.feed(quiet, t)
        t += 200; assertFalse(d.feed(loud, t))
        t += 200; assertFalse(d.feed(quiet, t))
    }
    @Test fun `never fires before any speech`() {
        val d = SilenceDetector(timeoutMs = 300, sampleRate = 16000)
        assertFalse(d.feed(quiet, 0))
        assertFalse(d.feed(quiet, 10_000))
    }
}
