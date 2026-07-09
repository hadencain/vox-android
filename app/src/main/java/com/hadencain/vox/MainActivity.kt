package com.hadencain.vox

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Vox"; textSize = 32f })

        if (android.provider.Settings.canDrawOverlays(this)) {
            startForegroundService(android.content.Intent(this, VoxService::class.java))
        } else {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        }
    }
}
