package com.hadencain.vox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.hadencain.vox.core.VoxSettings
import com.hadencain.vox.inject.VoxAccessibilityService
import com.hadencain.vox.ui.BubbleOverlay
import com.hadencain.vox.ui.BubbleState

class VoxService : Service() {
    lateinit var bubble: BubbleOverlay
    private lateinit var pipeline: Pipeline

    private val handler = Handler(Looper.getMainLooper())

    /** Polls a11y-service revocation every 5s so the bubble reflects it without waiting for
     *  a tap. Only repaints while the pipeline is IDLE -- never stomps an in-flight take. */
    private val a11yWatch = object : Runnable {
        override fun run() {
            if (VoxAccessibilityService.instance == null && pipelineIdle()) {
                bubble.setState(BubbleState.DISABLED)
            } else if (pipelineIdle()) {
                bubble.setState(BubbleState.IDLE)
            }
            handler.postDelayed(this, 5000)
        }
    }

    /** Cross-thread check of the pipeline's @Volatile state only -- see Pipeline.isIdle. */
    fun pipelineIdle(): Boolean = pipeline.isIdle

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        val settings = VoxSettings.load(java.io.File(filesDir, "settings.json"))
        pipeline = Pipeline(this, settings)
        bubble = BubbleOverlay(this,
            onTap = pipeline::onTap, onLongPress = pipeline::onLongPress)
        bubble.onCaptionTap = pipeline::onCaptionTap
        bubble.show()
        bubble.setState(BubbleState.IDLE)
        handler.post(a11yWatch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Stop the watcher before tearing down the pipeline -- shutdown() blocks briefly on
        // stateDispatcher, and a queued watcher tick firing mid-teardown (or after bubble is
        // torn down) would touch a half-destroyed pipeline/bubble.
        handler.removeCallbacksAndMessages(null)
        pipeline.shutdown()
        bubble.hide()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("vox", "Vox", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "vox")
            .setContentTitle("Vox is listening for taps")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }
}
