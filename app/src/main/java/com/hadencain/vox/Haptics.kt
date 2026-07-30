package com.hadencain.vox

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** Haptic cues on take state transitions (port of desktop sounds.py). The bubble is always
 *  visible while Vox runs, unlike desktop's hidden pythonw window, so a short tick is a
 *  confirmation here rather than the primary signal the audio cues were on desktop. */
object Haptics {
    private fun vibrator(ctx: Context) =
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    private fun oneShot(ctx: Context, ms: Long) {
        vibrator(ctx).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun start(ctx: Context) = oneShot(ctx, 20)
    fun stop(ctx: Context) = oneShot(ctx, 20)
    fun done(ctx: Context) = oneShot(ctx, 15)
    fun error(ctx: Context) = vibrator(ctx).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1))
}
