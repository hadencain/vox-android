package com.hadencain.vox.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Procedural Vox CRT — port of desktop status_widget.py's _draw_face. Geometry is
 *  authored on the 24-block grid and scaled to view size. Frame loop runs only while
 *  attached AND visible. No per-frame allocation: one reused Paint, all rects. */
class CrtView(context: Context) : View(context) {
    private var expression = CrtExpressions.forState(BubbleState.IDLE)
    private val ball = BallSim()
    private var lastFrameMs = 0L
    private var running = false

    private val paint = Paint()  // deliberately no AA: crisp pixel blocks
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CrtFace.AMBER
        isFakeBoldText = true
        letterSpacing = 0.15f
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setCrtState(state: BubbleState) {
        expression = CrtExpressions.forState(state)
        invalidate()
    }

    /** Called from the audio capture thread; BallSim's target is @Volatile. */
    fun setAudioLevel(rms: Float) = ball.onLevel(rms)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE
        if (shouldRun && !running) {
            running = true
            removeCallbacks(frameTick)  // avoid double-scheduling on rapid off->on toggles
            postOnAnimation(frameTick)
        }
        else if (!shouldRun) running = false
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1, 100)
        lastFrameMs = now
        val dt = dtMs / 1000f
        // Seconds for lamp/orbit/sweep phases. Wrapped hourly so Float keeps millisecond
        // precision at large uptimes (one visual jump per hour, invisible in practice).
        val t = (now % 3_600_000L) / 1000f
        val b = width / 24f
        val e = expression

        ball.step(dt, e.screen)

        val dimmed = e.dim < 1f
        if (dimmed) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (e.dim * 255).toInt())

        // --- chassis: rounded monitor shell, chin with one button + lamp ---
        px(canvas, b, 4f, 4.5f, CrtFace.BODY_L, 15f, 1f)
        var row = 5.5f
        while (row <= 15.5f) { px(canvas, b, 3f, row, CrtFace.BODY, 17f, 1f); row += 1f }
        px(canvas, b, 3.4f, 16.5f, CrtFace.BODY, 16.2f, 1f)
        px(canvas, b, 4f, 17.3f, CrtFace.BODY_D, 15f, 1f)
        px(canvas, b, CrtFace.SX - .4f, CrtFace.SY - .4f, CrtFace.BODY_D, CrtFace.SW + .8f, CrtFace.SH + .8f)
        px(canvas, b, CrtFace.SX, CrtFace.SY, CrtFace.GLASS, CrtFace.SW, CrtFace.SH)
        px(canvas, b, 5.6f, 15.9f, CrtFace.BODY_D, 1.5f, 1.5f)
        px(canvas, b, 5.95f, 16.25f, CrtFace.BODY_L, .8f, .8f)

        // status lamp
        val lampColor = when (e.lamp) {
            LampStyle.RED_BLINK -> if (t % 0.8f < 0.4f) CrtFace.RED else LAMP_OFF
            LampStyle.RED_SOLID -> CrtFace.RED
            LampStyle.AMBER_BLINK -> if (t % 1.6f < 0.8f) CrtFace.AMBER else LAMP_OFF
            LampStyle.AMBER_DIM -> withAlpha(CrtFace.AMBER, 120)
            LampStyle.OFF -> LAMP_OFF
        }
        px(canvas, b, 17.5f, 16.2f, lampColor, .8f, .8f)

        // RAW badge on the chin (verbatim take in flight)
        if (e.raw) {
            textPaint.textSize = 1.9f * b
            canvas.drawText("RAW", 9f * b, 17.4f * b, textPaint)
        }

        // --- the Bounce screen ---
        when (e.screen) {
            ScreenStyle.RIDE_LEVEL -> {
                val squash = if (ball.y > CrtFace.FLOOR - 0.4f) 0.45f else 0f
                px(canvas, b, ball.x - .7f, ball.y - .5f + squash, CrtFace.BALL,
                   1.4f + squash, 1f - squash * .5f)
                for (i in 1..3) {  // motion trail
                    px(canvas, b, ball.x - .35f, ball.y + i * .9f,
                       withAlpha(CrtFace.BALL, ((0.3f - i * 0.08f) * 255).toInt()), .7f, .5f)
                }
            }
            ScreenStyle.ORBIT -> {
                val a = t * 7f
                px(canvas, b, 11f + sin(a) * 3f - .5f, 10.75f + cos(a) * 2f - .5f, CrtFace.BALL, 1f, 1f)
                px(canvas, b, 11f + sin(a - .8f) * 3f - .35f, 10.75f + cos(a - .8f) * 2f - .35f,
                   withAlpha(CrtFace.BALL, 115), .7f, .7f)
            }
            ScreenStyle.SWEEP -> {
                px(canvas, b, 10f, CrtFace.SY2 - 1.9f, withAlpha(CrtFace.BALL, 179), 2f, .8f)  // deflated
                px(canvas, b, CrtFace.SX + 1, CrtFace.SY2 - 1f, CrtFace.TRACK, CrtFace.SW - 2, .7f)
                val w = (CrtFace.SW - 2) * .28f
                val x = CrtFace.SX + 1 + (CrtFace.SW - 2 - w) * ((t * .8f) % 1f)
                px(canvas, b, x, CrtFace.SY2 - 1f, CrtFace.AMBER, w, .7f)
            }
            ScreenStyle.FLATLINE -> px(canvas, b, CrtFace.SX + 1, 10.4f, CrtFace.RED, CrtFace.SW - 2, .7f)
            ScreenStyle.DRIFT -> px(canvas, b, ball.x - .6f, ball.y - .6f, CrtFace.BALL, 1.2f, 1.2f)
            ScreenStyle.BLANK -> {}
        }

        if (dimmed) canvas.restore()
    }

    private fun px(canvas: Canvas, b: Float, x: Float, y: Float, color: Int, w: Float, h: Float) {
        paint.color = color
        canvas.drawRect(x * b, y * b, (x + w) * b, (y + h) * b, paint)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        val LAMP_OFF = Color.argb(30, 255, 255, 255)
    }
}
