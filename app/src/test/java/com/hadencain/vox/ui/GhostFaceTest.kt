package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class GhostFaceTest {
    @Test fun `every state has an expression and keeps the legacy tint`() {
        val tints = mapOf(
            BubbleState.IDLE to 0xFF3D5AFE.toInt(),
            BubbleState.WAKING to 0xFFFFB300.toInt(),
            BubbleState.RECORDING to 0xFFE53935.toInt(),
            BubbleState.RECORDING_RAW to 0xFF8E24AA.toInt(),
            BubbleState.PROCESSING to 0xFF00897B.toInt(),
            BubbleState.ERROR to 0xFF616161.toInt(),
            BubbleState.DISABLED to 0xFF424242.toInt(),
        )
        for (state in BubbleState.entries) {
            val e = GhostExpressions.forState(state)
            assertEquals("tint for $state", tints[state], e.tint)
        }
    }

    @Test fun `recording states track level, others do not`() {
        assertEquals(MouthStyle.TRACK_LEVEL, GhostExpressions.forState(BubbleState.RECORDING).mouth)
        assertEquals(MouthStyle.TRACK_LEVEL, GhostExpressions.forState(BubbleState.RECORDING_RAW).mouth)
        for (s in listOf(BubbleState.IDLE, BubbleState.WAKING, BubbleState.PROCESSING, BubbleState.DISABLED))
            assertNotEquals("$s must not track level", MouthStyle.TRACK_LEVEL, GhostExpressions.forState(s).mouth)
        assertEquals(MouthStyle.FROWN, GhostExpressions.forState(BubbleState.ERROR).mouth)
    }

    @Test fun `raw recording is distinguished by brows`() {
        assertTrue(GhostExpressions.forState(BubbleState.RECORDING_RAW).brows)
        assertFalse(GhostExpressions.forState(BubbleState.RECORDING).brows)
    }

    @Test fun `disabled sleeps`() {
        val e = GhostExpressions.forState(BubbleState.DISABLED)
        assertEquals(EyeStyle.CLOSED, e.eyes)
        assertTrue(e.zzz)
    }
}
