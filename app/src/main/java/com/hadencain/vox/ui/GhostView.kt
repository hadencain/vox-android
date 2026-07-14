package com.hadencain.vox.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** Procedural ghost character. Geometry is authored in a 112-unit viewBox
 *  (docs/specs/assets/ghost-reference.svg is the design of record) and scaled to the
 *  view size. Frame loop runs only while attached AND visible.
 *
 *  Uniqueness hard rules (spec): face drawn in every state; asymmetric wavy hem;
 *  white outline in every state; eyes are whites-with-pupils. Do not drift. */
class GhostView(context: Context) : View(context) {
    private var expression = GhostExpressions.forState(BubbleState.IDLE)
    private val mouthDriver = MouthDriver()

    private var currentTint = expression.tint
    private var lastFrameMs = 0L
    private var nextBlinkMs = 0L
    private var blinkStartMs = -1L
    private var running = false

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 255, 255)  // ~90% white, every state (spec hard rule)
        strokeJoin = Paint.Join.ROUND
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A2E.toInt() }
    private val strokeDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF1A1A2E.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val strokeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val zzzPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255); isFakeBoldText = true
    }
    private val bodyPath = Path()
    private val scratchPath = Path()

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setGhostState(state: BubbleState) {
        expression = GhostExpressions.forState(state)
        invalidate()
    }

    /** Called from the audio capture thread; MouthDriver fields are @Volatile. */
    fun setAudioLevel(rms: Float) = mouthDriver.onLevel(rms, SystemClock.uptimeMillis())

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE
        if (shouldRun && !running) { running = true; postOnAnimation(frameTick) }
        else if (!shouldRun) running = false
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16 else (now - lastFrameMs).coerceIn(1, 100)
        lastFrameMs = now
        val s = width / 112f
        val e = expression

        // Tint lerp toward the state color (~150ms transition per spec).
        val k = 1f - Math.exp(-dt / 60.0).toFloat()
        currentTint = lerpColor(currentTint, e.tint, k)
        bodyPaint.color = currentTint
        outlinePaint.strokeWidth = 4f * s
        strokeDark.strokeWidth = 3.4f * s
        strokeWhite.strokeWidth = 3f * s
        zzzPaint.textSize = 13f * s

        // Idle bob + optional processing wobble.
        val bob = 2f * s * sin(2.0 * PI * (now % e.bobPeriodMs) / e.bobPeriodMs).toFloat()
        canvas.save()
        canvas.translate(0f, bob)
        if (e.wobble) canvas.rotate(3f * sin(now / 180.0).toFloat(), width / 2f, height / 2f)

        drawBody(canvas, s, now)
        drawEyes(canvas, s, now, e)
        if (e.brows) {
            canvas.drawLine(37f*s, 35f*s, 51f*s, 37f*s, strokeWhite)
            canvas.drawLine(75f*s, 35f*s, 61f*s, 37f*s, strokeWhite)
        }
        drawMouth(canvas, s, now, dt, e)
        if (e.zzz) canvas.drawText("zZ", 84f * s, 26f * s, zzzPaint)
        canvas.restore()
    }

    /** Asymmetric 3-wave hem, phase-animated. Matches ghost-reference.svg at phase 0. */
    private fun drawBody(canvas: Canvas, s: Float, now: Long) {
        val ph = (now / 260.0).toFloat()
        fun hy(base: Float, amp: Float, off: Float) = (base + amp * sin(ph + off)) * s
        bodyPath.rewind()
        bodyPath.moveTo(56f*s, 14f*s)
        bodyPath.cubicTo(34f*s, 14f*s, 22f*s, 30f*s, 22f*s, 52f*s)
        bodyPath.lineTo(22f*s, hy(82f, 1.5f, 0f))
        bodyPath.quadTo(27f*s, hy(74f, 2.5f, 1.1f), 33f*s, hy(82f, 1.5f, 2.3f))
        bodyPath.quadTo(40f*s, hy(92f, 2.5f, 3.1f), 48f*s, hy(83f, 1.5f, 4.2f))
        bodyPath.quadTo(56f*s, hy(74f, 2.5f, 5.0f), 65f*s, hy(84f, 1.5f, 0.7f))
        bodyPath.quadTo(74f*s, hy(93f, 2.5f, 1.9f), 79f*s, hy(83f, 1.5f, 2.8f))
        bodyPath.quadTo(84f*s, hy(74f, 2.5f, 3.9f), 90f*s, hy(80f, 1.5f, 5.3f))
        bodyPath.lineTo(90f*s, 52f*s)
        bodyPath.cubicTo(90f*s, 30f*s, 78f*s, 14f*s, 56f*s, 14f*s)
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawPath(bodyPath, outlinePaint)
    }

    private fun drawEyes(canvas: Canvas, s: Float, now: Long, e: GhostExpression) {
        val y = 48f * s
        when (e.eyes) {
            EyeStyle.CLOSED -> {
                drawArcEye(canvas, 38f*s, y, 50f*s, s, strokeWhite)
                drawArcEye(canvas, 62f*s, y, 74f*s, s, strokeWhite)
            }
            EyeStyle.X -> { drawX(canvas, 44f*s, y, 5f*s); drawX(canvas, 68f*s, y, 5f*s) }
            EyeStyle.NORMAL, EyeStyle.WIDE -> {
                val ry = (if (e.eyes == EyeStyle.WIDE) 11f else 9f) * s * blinkScale(now)
                val pr = (if (e.eyes == EyeStyle.WIDE) 4f else 3.2f) * s
                canvas.drawOval(44f*s - 6.5f*s, y - ry, 44f*s + 6.5f*s, y + ry, whitePaint)
                if (ry > 3f * s) canvas.drawCircle(44f*s + 1.5f*s, y + 2f*s, pr, darkPaint)
                canvas.drawOval(68f*s - 6.5f*s, y - ry, 68f*s + 6.5f*s, y + ry, whitePaint)
                if (ry > 3f * s) canvas.drawCircle(68f*s + 1.5f*s, y + 2f*s, pr, darkPaint)
            }
        }
    }

    /** Periodic blink: every 2.8-4s (deterministic jitter), 150ms squeeze to near-closed. */
    private fun blinkScale(now: Long): Float {
        if (blinkStartMs < 0 && now >= nextBlinkMs) blinkStartMs = now
        if (blinkStartMs >= 0) {
            val t = (now - blinkStartMs) / 150f
            if (t >= 1f) {
                blinkStartMs = -1
                nextBlinkMs = now + 2800 + (now % 1200)  // deterministic jitter, no Random needed
            } else return 1f - 0.9f * sin(PI * t.toDouble()).toFloat()
        }
        return 1f
    }

    private fun drawX(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, strokeWhite)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, strokeWhite)
    }

    private fun drawMouth(canvas: Canvas, s: Float, now: Long, dt: Long, e: GhostExpression) {
        if (e.mouth == MouthStyle.FROWN) {
            scratchPath.rewind()
            scratchPath.moveTo(48f*s, 70f*s)
            scratchPath.quadTo(56f*s, 63f*s, 64f*s, 70f*s)
            canvas.drawPath(scratchPath, strokeDark)
            return
        }
        val open = mouthDriver.openness(now, dt, e.mouth == MouthStyle.TRACK_LEVEL, e.mouthFloor)
        val rx = (5f + open * 3.5f) * s
        val ry = (1.5f + open * 8f) * s
        val cy = (66f + open * 2f) * s
        canvas.drawOval(56f*s - rx, cy - ry, 56f*s + rx, cy + ry, darkPaint)
    }

    private fun lerpColor(from: Int, to: Int, k: Float): Int {
        fun ch(a: Int, b: Int) = (a + ((b - a) * k)).toInt()
        return Color.argb(255,
            ch(Color.red(from), Color.red(to)),
            ch(Color.green(from), Color.green(to)),
            ch(Color.blue(from), Color.blue(to)))
    }

    /** Closed-eye arc: gentle downward curve between x0 and x1 at height y. */
    private fun drawArcEye(canvas: Canvas, x0: Float, y: Float, x1: Float, s: Float, paint: Paint) {
        scratchPath.rewind()
        scratchPath.moveTo(x0, y)
        scratchPath.quadTo((x0 + x1) / 2f, y + 5f * s, x1, y)
        canvas.drawPath(scratchPath, paint)
    }
}
