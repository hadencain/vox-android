package com.hadencain.vox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hadencain.vox.core.VoxSettings
import com.hadencain.vox.ui.BubbleOverlay
import com.hadencain.vox.ui.BubbleState

class VoxService : Service() {
    lateinit var bubble: BubbleOverlay
    private lateinit var pipeline: Pipeline

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
    }

    override fun onDestroy() { pipeline.shutdown(); bubble.hide(); super.onDestroy() }
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
