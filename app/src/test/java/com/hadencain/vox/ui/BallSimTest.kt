package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class BallSimTest {
    @Test fun `ball stays inside the glass through 2000 drift steps`() {
        val b = BallSim()
        repeat(2000) {
            b.step(0.033f, ScreenStyle.DRIFT)
            assertTrue("x=${b.x}", b.x >= CrtFace.SX && b.x <= CrtFace.SX2)
            assertTrue("y=${b.y}", b.y >= CrtFace.SY && b.y <= CrtFace.SY2)
        }
    }

    @Test fun `loud voice lifts the ball, silence floors it`() {
        val b = BallSim()
        b.onLevel(0.08f)  // fullOpenRms -> level 1.0
        repeat(60) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertTrue("expected lifted, y=${b.y}", b.y < CrtFace.FLOOR - 2f)
        b.onLevel(0f)
        repeat(120) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertTrue("expected floored, y=${b.y}", b.y > CrtFace.FLOOR - 0.5f)
    }

    @Test fun `level normalizes and clamps rms`() {
        val b = BallSim()
        b.onLevel(0.04f)
        repeat(120) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertEquals(0.5f, b.level, 0.05f)
        b.onLevel(9f)
        repeat(120) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertEquals(1f, b.level, 0.05f)
    }

    @Test fun `reset clears level and target so a new take starts at the floor`() {
        val b = BallSim()
        b.onLevel(0.08f)  // fullOpenRms -> level 1.0
        repeat(60) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        b.reset()
        assertEquals(0f, b.level, 0f)
        repeat(60) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }  // no new onLevel
        assertTrue("expected at/near floor, y=${b.y}", b.y > CrtFace.FLOOR - 0.5f)
    }

    @Test fun `orbit sweep flatline and blank do not move the ball`() {
        val b = BallSim()
        repeat(10) { b.step(0.033f, ScreenStyle.DRIFT) }  // move off spawn
        val x = b.x; val y = b.y
        for (s in listOf(ScreenStyle.ORBIT, ScreenStyle.SWEEP, ScreenStyle.FLATLINE, ScreenStyle.BLANK))
            repeat(30) { b.step(0.033f, s) }
        assertEquals(x, b.x, 1e-6f)
        assertEquals(y, b.y, 1e-6f)
    }
}
