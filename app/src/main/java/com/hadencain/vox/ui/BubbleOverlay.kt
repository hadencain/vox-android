package com.hadencain.vox.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.hadencain.vox.R
import kotlin.math.abs

enum class BubbleState { IDLE, WAKING, RECORDING, RECORDING_RAW, PROCESSING, ERROR, DISABLED }

/** Persistent draggable chat-head. Tap = dictate toggle, long-press = AI edit.
 *  Caption view (live partials) floats beside it; tapping the caption toggles raw mode. */
class BubbleOverlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onCloseRequested: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    var onCaptionTap: (() -> Unit)? = null

    @Volatile var currentState: BubbleState = BubbleState.IDLE; private set

    private val bubble = CrtView(context)
    private val caption = TextView(context).apply {
        setBackgroundColor(Color.argb(200, 20, 20, 20))
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(24, 16, 24, 16)
        visibility = View.GONE
        setOnClickListener { onCaptionTap?.invoke() }
    }
    private val closeTarget = TextView(context).apply {
        text = "✕"
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.close_target_bg)
        visibility = View.GONE
    }

    private val size = (56 * context.resources.displayMetrics.density).toInt()
    private val density = context.resources.displayMetrics.density
    private val closeTargetSize = (64 * density).toInt()
    private val closeTargetBottomMargin = 120
    private val closeThreshold = 140 * density
    private val bubbleLp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 300 }
    private val captionLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private val closeTargetLp = WindowManager.LayoutParams(
        closeTargetSize, closeTargetSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = closeTargetBottomMargin }

    // Close-target center in screen coords -- BOTTOM|CENTER_HORIZONTAL with bottom margin m
    // and height h puts its center at (screenWidth/2, screenHeight - m - h/2). Good enough;
    // the threshold is generous and this doesn't need to survive rotation mid-drag.
    private val closeTargetCenterX: Float
        get() = context.resources.displayMetrics.widthPixels / 2f
    private val closeTargetCenterY: Float
        get() = context.resources.displayMetrics.heightPixels - closeTargetBottomMargin - closeTargetSize / 2f

    private var shown = false
    private var longPressed = false
    private val longPressRunnable = Runnable { longPressed = true; onLongPress() }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) return
        bubble.setOnTouchListener(DragTouchListener())
        wm.addView(bubble, bubbleLp)
        wm.addView(caption, captionLp)
        wm.addView(closeTarget, closeTargetLp)
        shown = true
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (!shown) return
        wm.removeView(bubble); wm.removeView(caption); wm.removeView(closeTarget)
        shown = false
    }

    fun setState(state: BubbleState) {
        currentState = state
        handler.post { bubble.setCrtState(state) }
    }

    fun setCaption(text: String?) = handler.post {
        if (text.isNullOrBlank()) { caption.visibility = View.GONE; return@post }
        caption.text = text.takeLast(120)
        captionLp.x = bubbleLp.x + size + 12
        captionLp.y = bubbleLp.y
        if (shown) wm.updateViewLayout(caption, captionLp)
        caption.visibility = View.VISIBLE
    }

    /** Mic level passthrough -- called from the audio capture thread; CrtView handles
     *  cross-thread safety internally. */
    fun setAudioLevel(rms: Float) = bubble.setAudioLevel(rms)

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var touchX = 0f; private var touchY = 0f
        private var dragging = false
        private var inCloseZone = false
        private val slop = 20

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleLp.x; startY = bubbleLp.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false; longPressed = false; inCloseZone = false
                    handler.postDelayed(longPressRunnable, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        if (!dragging) {
                            dragging = true
                            handler.removeCallbacks(longPressRunnable)
                            closeTarget.visibility = View.VISIBLE
                        }
                        bubbleLp.x = startX + dx; bubbleLp.y = startY + dy
                        wm.updateViewLayout(bubble, bubbleLp)

                        val bubbleCenterX = bubbleLp.x + size / 2f
                        val bubbleCenterY = bubbleLp.y + size / 2f
                        val distX = bubbleCenterX - closeTargetCenterX
                        val distY = bubbleCenterY - closeTargetCenterY
                        val dist = kotlin.math.sqrt(distX * distX + distY * distY)
                        inCloseZone = dist < closeThreshold
                        val scale = if (inCloseZone) 1.3f else 1.0f
                        closeTarget.scaleX = scale; closeTarget.scaleY = scale
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        closeTarget.visibility = View.GONE
                        closeTarget.scaleX = 1.0f; closeTarget.scaleY = 1.0f
                        if (inCloseZone && e.action == MotionEvent.ACTION_UP) onCloseRequested()
                    } else if (!longPressed && e.action == MotionEvent.ACTION_UP) {
                        onTap()
                    }
                }
            }
            return true
        }
    }
}
