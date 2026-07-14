package com.hadencain.vox.ui

import kotlin.math.exp
import kotlin.math.sin

/** Smooths coarse (100ms) mic RMS into a continuous mouth-openness value.
 *  onLevel is called from the audio capture thread; openness from the UI frame loop.
 *  If no level event arrives for [fallbackAfterMs] while tracking, falls back to a
 *  gentle canned flap so the mouth is never frozen (spec: error handling). */
class MouthDriver(private val fallbackAfterMs: Long = 600) {
    // Typical speech RMS on VOICE_RECOGNITION source is ~0.02..0.1; 0.08 maps to fully open.
    private val fullOpenRms = 0.08f

    @Volatile private var targetLevel = 0f
    @Volatile private var lastLevelMs = Long.MIN_VALUE
    private var current = 0f  // UI-thread only

    fun onLevel(rms: Float, nowMs: Long) {
        targetLevel = (rms / fullOpenRms).coerceIn(0f, 1f)
        lastLevelMs = nowMs
    }

    fun openness(nowMs: Long, dtMs: Long, tracking: Boolean, floor: Float): Float {
        val target = when {
            !tracking -> floor
            lastLevelMs == Long.MIN_VALUE || nowMs - lastLevelMs > fallbackAfterMs ->
                // canned flap: 0..0.4 above floor on a ~0.9s cycle
                floor + 0.4f * (0.5f + 0.5f * sin(nowMs / 140.0).toFloat())
            else -> floor + targetLevel * (1f - floor)
        }
        // Exponential approach with ~60ms time constant -- smooth at any frame rate.
        val k = 1f - exp(-dtMs / 60.0).toFloat()
        current += (target - current) * k
        return current.coerceIn(0f, 1f)
    }
}
