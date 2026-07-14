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
    @Test fun `feedRms behaves like feed with the equivalent rms`() {
        val a = SilenceDetector(timeoutMs = 1000, sampleRate = 16000)
        val b = SilenceDetector(timeoutMs = 1000, sampleRate = 16000)
        val loud = FloatArray(1600) { 0.1f }   // rms 0.1 > threshold
        val quiet = FloatArray(1600) { 0.001f } // rms 0.001 < threshold
        assertEquals(a.feed(loud, 0), b.feedRms(0.1f, 0))
        assertEquals(a.feed(quiet, 500), b.feedRms(0.001f, 500))
        assertEquals(a.feed(quiet, 1600), b.feedRms(0.001f, 1600))  // both fire here
        assertEquals(a.feed(loud, 1700), b.feedRms(0.1f, 1700))     // both stay fired=false
    }
}
