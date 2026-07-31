package com.hadencain.vox.ui

/** The Bounce ball — pure physics, port of desktop BallSim + the widget's level smoothing.
 *  onLevel is called from the audio capture thread (@Volatile target); step runs on the
 *  UI frame loop. DRIFT: DVD-logo bounce off the glass edges. RIDE_LEVEL: x eases to
 *  center, y chases (floor - level*height) so the ball rides the voice. Other screens
 *  position their visuals from time in the view; the ball is parked. */
class BallSim {
    // ~0.08 RMS on VOICE_RECOGNITION reads as full-volume speech (empirically tuned).
    private val fullOpenRms = 0.08f

    var x = 8f; private set
    var y = 9f; private set
    var level = 0f; private set  // smoothed 0..1, read by the view for squash
    private var vx = 2.6f
    private var vy = 1.9f
    @Volatile private var target = 0f

    fun onLevel(rms: Float) {
        target = (rms / fullOpenRms).coerceIn(0f, 1f)
    }

    /** Called on state change away from RIDE_LEVEL so a new take never inherits the last
     *  take's lift. */
    fun reset() { level = 0f; target = 0f }

    fun step(dt: Float, screen: ScreenStyle) {
        when (screen) {
            ScreenStyle.RIDE_LEVEL -> {
                level += (target - level) * minOf(1f, dt * 18)  // desktop's dt*18 ease
                x += (11f - x) * minOf(1f, dt * 3)
                val ty = CrtFace.FLOOR - level * (CrtFace.SH - 2.6f)
                y += (ty - y) * minOf(1f, dt * 14)
            }
            ScreenStyle.DRIFT -> {
                x += vx * dt
                y += vy * dt
                if (x < CrtFace.SX + 1 || x > CrtFace.SX2 - 1.4f) {
                    vx = -vx
                    x = maxOf(CrtFace.SX + 1, minOf(CrtFace.SX2 - 1.4f, x))
                }
                if (y < CrtFace.SY + 1 || y > CrtFace.SY2 - 1.4f) {
                    vy = -vy
                    y = maxOf(CrtFace.SY + 1, minOf(CrtFace.SY2 - 1.4f, y))
                }
            }
            else -> {}  // parked; orbit/sweep/flatline are drawn from time, not the ball
        }
    }
}
