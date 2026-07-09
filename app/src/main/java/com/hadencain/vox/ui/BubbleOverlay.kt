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
import android.widget.ImageView
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
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    var onCaptionTap: (() -> Unit)? = null

    private val bubble = ImageView(context).apply {
        setBackgroundResource(R.drawable.bubble_bg)
    }
    private val caption = TextView(context).apply {
        setBackgroundColor(Color.argb(200, 20, 20, 20))
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(24, 16, 24, 16)
        visibility = View.GONE
        setOnClickListener { onCaptionTap?.invoke() }
    }

    private val size = (56 * context.resources.displayMetrics.density).toInt()
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

    private var shown = false
    private var longPressed = false
    private val longPressRunnable = Runnable { longPressed = true; onLongPress() }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) return
        bubble.setOnTouchListener(DragTouchListener())
        wm.addView(bubble, bubbleLp)
        wm.addView(caption, captionLp)
        shown = true
    }

    fun hide() {
        if (!shown) return
        wm.removeView(bubble); wm.removeView(caption)
        shown = false
    }

    fun setState(state: BubbleState) = handler.post {
        bubble.background.setTint(when (state) {
            BubbleState.IDLE -> Color.parseColor("#3D5AFE")
            BubbleState.WAKING -> Color.parseColor("#FFB300")
            BubbleState.RECORDING -> Color.parseColor("#E53935")
            BubbleState.RECORDING_RAW -> Color.parseColor("#8E24AA")
            BubbleState.PROCESSING -> Color.parseColor("#00897B")
            BubbleState.ERROR -> Color.parseColor("#616161")
            BubbleState.DISABLED -> Color.parseColor("#424242")
        })
    }

    fun setCaption(text: String?) = handler.post {
        if (text.isNullOrBlank()) { caption.visibility = View.GONE; return@post }
        caption.text = text.takeLast(120)
        captionLp.x = bubbleLp.x + size + 12
        captionLp.y = bubbleLp.y
        if (shown) wm.updateViewLayout(caption, captionLp)
        caption.visibility = View.VISIBLE
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var touchX = 0f; private var touchY = 0f
        private var dragging = false
        private val slop = 20

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleLp.x; startY = bubbleLp.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false; longPressed = false
                    handler.postDelayed(longPressRunnable, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                        bubbleLp.x = startX + dx; bubbleLp.y = startY + dy
                        wm.updateViewLayout(bubble, bubbleLp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!dragging && !longPressed && e.action == MotionEvent.ACTION_UP) onTap()
                }
            }
            return true
        }
    }
}
