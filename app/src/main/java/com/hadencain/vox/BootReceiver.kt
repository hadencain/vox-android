package com.hadencain.vox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.hadencain.vox.setup.ModelDownloader

/** Android 14/15 forbid a mic-type foreground service (or an Activity) from auto-starting
 *  off a BOOT_COMPLETED broadcast. The compliant path: post a notification here; tapping it
 *  opens MainActivity (a visible, user-initiated launch), which is allowed to start the
 *  service. See MainActivity's EXTRA_AUTOSTART handling for the other half of this. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // Skip if setup isn't complete-ish -- nothing useful to resume.
        val setupLooksComplete = ModelDownloader.allPresent(context) && Settings.canDrawOverlays(context)
        if (!setupLooksComplete) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("vox_boot", "Vox", NotificationManager.IMPORTANCE_DEFAULT))

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTOSTART, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = Notification.Builder(context, "vox_boot")
            .setContentTitle("Vox is ready")
            .setContentText("Tap to resume voice typing")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(1001, notification)
    }
}
