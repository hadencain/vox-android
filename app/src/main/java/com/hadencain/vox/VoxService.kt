package com.hadencain.vox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.hadencain.vox.core.VoxSettings
import com.hadencain.vox.inject.VoxAccessibilityService
import com.hadencain.vox.ui.BubbleOverlay
import com.hadencain.vox.ui.BubbleState

class VoxService : Service() {
    companion object {
        const val ACTION_STOP = "com.hadencain.vox.STOP"
    }

    lateinit var bubble: BubbleOverlay
    private lateinit var pipeline: Pipeline

    // Guards onDestroy against touching lateinit `pipeline`/`bubble` when onCreate bailed
    // before constructing them (missing overlay permission, startForeground rejected).
    private var initialized = false

    private val handler = Handler(Looper.getMainLooper())

    /** Polls a11y-service revocation every 5s so the bubble reflects it without waiting for
     *  a tap. Only repaints while the pipeline is IDLE -- never stomps an in-flight take. */
    private val a11yWatch = object : Runnable {
        override fun run() {
            if (VoxAccessibilityService.instance == null && pipelineIdle()) {
                bubble.setState(BubbleState.DISABLED)
            } else if (pipelineIdle() && bubble.currentState == BubbleState.DISABLED) {
                bubble.setState(BubbleState.IDLE)
            }
            handler.postDelayed(this, 5000)
        }
    }

    /** Cross-thread check of the pipeline's @Volatile state only -- see Pipeline.isIdle. */
    fun pipelineIdle(): Boolean = pipeline.isIdle

    override fun onCreate() {
        super.onCreate()
        // Sticky-restart crash-loop guard: onCreate can run with none of the setup
        // preconditions still true (e.g. `am kill` restart, or the user revoked overlay
        // access while Vox was backgrounded). Without the overlay permission the bubble can
        // never show, so bail before touching it rather than crash-loop on every restart.
        if (!Settings.canDrawOverlays(this)) {
            Log.e("Vox", "onCreate: overlay permission missing -- refusing to start")
            stopSelf()
            return
        }
        try {
            startForeground(1, buildNotification())
        } catch (e: Exception) {
            // API 34+ restricts background starts of mic-using foreground services; a
            // sticky-restart landing in a disallowed state throws here instead of crashing
            // the whole process.
            Log.e("Vox", "onCreate: startForeground failed", e)
            stopSelf()
            return
        }
        val settings = VoxSettings.load(java.io.File(filesDir, "settings.json"))
        pipeline = Pipeline(this, settings)
        bubble = BubbleOverlay(this,
            onTap = pipeline::onTap, onLongPress = pipeline::onLongPress)
        bubble.onCaptionTap = pipeline::onCaptionTap
        initialized = true
        try {
            bubble.show()
        } catch (e: Exception) {
            // WindowManager.BadTokenException and friends -- can't add the overlay view even
            // though canDrawOverlays() said yes (window token invalidated, etc).
            Log.e("Vox", "onCreate: bubble.show failed", e)
            stopSelf()
            return
        }
        bubble.setState(BubbleState.IDLE)
        handler.post(a11yWatch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Stop the watcher before tearing down the pipeline -- shutdown() blocks briefly on
        // stateDispatcher, and a queued watcher tick firing mid-teardown (or after bubble is
        // torn down) would touch a half-destroyed pipeline/bubble.
        handler.removeCallbacksAndMessages(null)
        if (initialized) {
            pipeline.shutdown()
            bubble.hide()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("vox", "Vox", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VoxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "vox")
            .setContentTitle("Vox is listening for taps")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop Vox", stopIntent).build())
            .build()
    }
}
