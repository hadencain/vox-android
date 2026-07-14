package com.hadencain.vox.ui

enum class EyeStyle { NORMAL, WIDE, CLOSED, X }
enum class MouthStyle { FIXED, TRACK_LEVEL, FROWN }

/** Pure expression parameters per pipeline state. mouthFloor is the resting openness
 *  (0..1) for FIXED mouths, and the minimum openness while TRACK_LEVEL. */
data class GhostExpression(
    val tint: Int,
    val eyes: EyeStyle,
    val mouth: MouthStyle,
    val mouthFloor: Float,
    val brows: Boolean = false,
    val zzz: Boolean = false,
    val bobPeriodMs: Int = 2400,
    val wobble: Boolean = false,
)

object GhostExpressions {
    fun forState(state: BubbleState): GhostExpression = when (state) {
        BubbleState.IDLE          -> GhostExpression(0xFF3D5AFE.toInt(), EyeStyle.NORMAL, MouthStyle.FIXED, 0.15f)
        BubbleState.WAKING        -> GhostExpression(0xFFFFB300.toInt(), EyeStyle.WIDE, MouthStyle.FIXED, 0.35f, bobPeriodMs = 1200)
        BubbleState.RECORDING     -> GhostExpression(0xFFE53935.toInt(), EyeStyle.WIDE, MouthStyle.TRACK_LEVEL, 0.1f)
        BubbleState.RECORDING_RAW -> GhostExpression(0xFF8E24AA.toInt(), EyeStyle.WIDE, MouthStyle.TRACK_LEVEL, 0.1f, brows = true)
        BubbleState.PROCESSING    -> GhostExpression(0xFF00897B.toInt(), EyeStyle.CLOSED, MouthStyle.FIXED, 0.1f, wobble = true)
        BubbleState.ERROR         -> GhostExpression(0xFF616161.toInt(), EyeStyle.X, MouthStyle.FROWN, 0f, bobPeriodMs = 3600)
        BubbleState.DISABLED      -> GhostExpression(0xFF424242.toInt(), EyeStyle.CLOSED, MouthStyle.FIXED, 0.05f, zzz = true, bobPeriodMs = 4000)
    }
}
