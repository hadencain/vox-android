package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class CrtFaceTest {
    @Test fun `recording states ride the level, others do not`() {
        assertEquals(ScreenStyle.RIDE_LEVEL, CrtExpressions.forState(BubbleState.RECORDING).screen)
        assertEquals(ScreenStyle.RIDE_LEVEL, CrtExpressions.forState(BubbleState.RECORDING_RAW).screen)
        for (s in listOf(BubbleState.IDLE, BubbleState.WAKING, BubbleState.PROCESSING,
                         BubbleState.ERROR, BubbleState.DISABLED))
            assertNotEquals("$s must not ride level", ScreenStyle.RIDE_LEVEL, CrtExpressions.forState(s).screen)
    }

    @Test fun `raw badge only on raw recording`() {
        assertTrue(CrtExpressions.forState(BubbleState.RECORDING_RAW).raw)
        for (s in BubbleState.entries.filter { it != BubbleState.RECORDING_RAW })
            assertFalse("$s must not show RAW", CrtExpressions.forState(s).raw)
    }

    @Test fun `rec lamp blinks red while recording, error is solid red`() {
        assertEquals(LampStyle.RED_BLINK, CrtExpressions.forState(BubbleState.RECORDING).lamp)
        assertEquals(LampStyle.RED_BLINK, CrtExpressions.forState(BubbleState.RECORDING_RAW).lamp)
        assertEquals(LampStyle.RED_SOLID, CrtExpressions.forState(BubbleState.ERROR).lamp)
        assertEquals(ScreenStyle.FLATLINE, CrtExpressions.forState(BubbleState.ERROR).screen)
        assertEquals(LampStyle.AMBER_BLINK, CrtExpressions.forState(BubbleState.PROCESSING).lamp)
    }

    @Test fun `waking dims with amber blink and sweep, disabled is dim standby`() {
        val w = CrtExpressions.forState(BubbleState.WAKING)
        assertEquals(ScreenStyle.SWEEP, w.screen)
        assertEquals(LampStyle.AMBER_BLINK, w.lamp)
        assertEquals(0.7f, w.dim)
        val d = CrtExpressions.forState(BubbleState.DISABLED)
        assertEquals(ScreenStyle.BLANK, d.screen)
        assertEquals(LampStyle.AMBER_DIM, d.lamp)
        assertEquals(0.55f, d.dim)
    }

    @Test fun `idle drifts at full brightness with the lamp off`() {
        val e = CrtExpressions.forState(BubbleState.IDLE)
        assertEquals(ScreenStyle.DRIFT, e.screen)
        assertEquals(LampStyle.OFF, e.lamp)
        assertEquals(1f, e.dim)
    }

    @Test fun `static states stop the frame loop, animated ones keep it running`() {
        for (s in listOf(BubbleState.DISABLED, BubbleState.ERROR))
            assertFalse("$s must not animate", CrtExpressions.forState(s).animates)
        for (s in listOf(BubbleState.IDLE, BubbleState.WAKING, BubbleState.RECORDING,
                         BubbleState.RECORDING_RAW, BubbleState.PROCESSING))
            assertTrue("$s must animate", CrtExpressions.forState(s).animates)
    }
}
