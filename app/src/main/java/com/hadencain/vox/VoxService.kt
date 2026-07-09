package com.hadencain.vox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.hadencain.vox.ui.BubbleOverlay
import com.hadencain.vox.ui.BubbleState

class VoxService : Service() {
    private lateinit var bubble: BubbleOverlay

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        bubble = BubbleOverlay(this,
            onTap = { Log.i("Vox", "bubble tap"); Toast.makeText(this, "tap", Toast.LENGTH_SHORT).show() },
            onLongPress = { Log.i("Vox", "bubble long-press"); Toast.makeText(this, "long-press", Toast.LENGTH_SHORT).show() })
        bubble.show()
        bubble.setState(BubbleState.IDLE)
    }

    override fun onDestroy() { bubble.hide(); super.onDestroy() }
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
