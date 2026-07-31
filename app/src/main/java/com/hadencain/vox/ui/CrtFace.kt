package com.hadencain.vox.ui

enum class LampStyle { OFF, RED_BLINK, RED_SOLID, AMBER_BLINK, AMBER_DIM }
enum class ScreenStyle { DRIFT, RIDE_LEVEL, ORBIT, SWEEP, FLATLINE, BLANK }

/** Pure display parameters per pipeline state. dim multiplies the whole face's opacity
 *  (desktop dims to 0.7 while loading; standby is darker still). */
data class CrtExpression(
    val screen: ScreenStyle,
    val lamp: LampStyle,
    val dim: Float = 1f,
    val raw: Boolean = false,
) {
    /** Whether anything on the face moves — static states stop the frame loop entirely. */
    val animates: Boolean
        get() = screen != ScreenStyle.BLANK && screen != ScreenStyle.FLATLINE ||
                lamp == LampStyle.RED_BLINK || lamp == LampStyle.AMBER_BLINK
}

/** Shared palette + screen geometry, copied from desktop status_widget.py. 24-block grid.
 *  0x literals, not Color.parseColor — JVM unit tests can't call android.jar. */
object CrtFace {
    val BODY = 0xFF6B4A35.toInt()
    val BODY_L = 0xFF7D5843.toInt()
    val BODY_D = 0xFF553A29.toInt()
    val GLASS = 0xFF10141F.toInt()
    val BALL = 0xFFF5EFDF.toInt()
    val AMBER = 0xFFFFB946.toInt()
    val RED = 0xFFFF5F56.toInt()
    val TRACK = 0xFF242B3D.toInt()

    const val SX = 4.5f
    const val SY = 6.5f
    const val SW = 13f
    const val SH = 8.5f
    const val SX2 = SX + SW
    const val SY2 = SY + SH
    const val FLOOR = SY2 - 1.3f
}

object CrtExpressions {
    fun forState(state: BubbleState): CrtExpression = when (state) {
        BubbleState.IDLE          -> CrtExpression(ScreenStyle.DRIFT, LampStyle.OFF)
        BubbleState.WAKING        -> CrtExpression(ScreenStyle.SWEEP, LampStyle.AMBER_BLINK, dim = 0.7f)
        BubbleState.RECORDING     -> CrtExpression(ScreenStyle.RIDE_LEVEL, LampStyle.RED_BLINK)
        BubbleState.RECORDING_RAW -> CrtExpression(ScreenStyle.RIDE_LEVEL, LampStyle.RED_BLINK, raw = true)
        BubbleState.PROCESSING    -> CrtExpression(ScreenStyle.ORBIT, LampStyle.AMBER_BLINK)
        BubbleState.ERROR         -> CrtExpression(ScreenStyle.FLATLINE, LampStyle.RED_SOLID)
        BubbleState.DISABLED      -> CrtExpression(ScreenStyle.BLANK, LampStyle.AMBER_DIM, dim = 0.55f)
    }
}
