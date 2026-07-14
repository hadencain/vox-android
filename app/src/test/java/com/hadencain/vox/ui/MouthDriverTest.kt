package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class MouthDriverTest {
    /** Run the per-frame update n times at 16ms steps and return the last value. */
    private fun settle(d: MouthDriver, startMs: Long, frames: Int, tracking: Boolean, floor: Float): Float {
        var v = 0f; var t = startMs
        repeat(frames) { t += 16; v = d.openness(t, 16, tracking, floor) }
        return v
    }

    @Test fun `openness converges toward a loud level`() {
        val d = MouthDriver()
        d.onLevel(0.08f, 1000)  // loud speech
        val v = settle(d, 1000, 30, tracking = true, floor = 0.1f)
        assertTrue("expected wide open, got $v", v > 0.7f)
    }

    @Test fun `silence between words closes toward the floor`() {
        val d = MouthDriver()
        d.onLevel(0.08f, 1000)
        settle(d, 1000, 30, tracking = true, floor = 0.1f)
        d.onLevel(0.0f, 1500)  // fresh level event: silence
        val v = settle(d, 1500, 60, tracking = true, floor = 0.1f)
        assertTrue("expected near floor, got $v", v < 0.2f)
    }

    @Test fun `no level events falls back to canned flap - never frozen`() {
        val d = MouthDriver(fallbackAfterMs = 600)
        // No onLevel ever called; tracking. Collect samples over 2s.
        val samples = mutableListOf<Float>()
        var t = 5000L
        repeat(120) { t += 16; samples.add(d.openness(t, 16, tracking = true, floor = 0.1f)) }
        val late = samples.takeLast(60)
        assertTrue("flap must move", late.max() - late.min() > 0.05f)
        assertTrue("flap stays gentle", late.max() < 0.7f)
        assertTrue("never below floor-ish", late.min() > 0.02f)
    }

    @Test fun `fallback recovers to live tracking once a level event arrives`() {
        val d = MouthDriver(fallbackAfterMs = 600)
        // No onLevel ever called; drive long enough (1s) for the canned flap to be active.
        var t = 5000L
        repeat(62) { t += 16; d.openness(t, 16, tracking = true, floor = 0.1f) }
        // Now a loud level event arrives at the current timestamp; tracking should resume.
        d.onLevel(0.08f, t)
        val v = settle(d, t, 30, tracking = true, floor = 0.1f)
        assertTrue("expected recovery to wide open, got $v", v > 0.7f)
    }

    @Test fun `not tracking returns the floor`() {
        val d = MouthDriver()
        d.onLevel(0.08f, 1000)  // stale loud level must be ignored
        val v = settle(d, 1000, 60, tracking = false, floor = 0.15f)
        assertEquals(0.15f, v, 0.03f)
    }
}
