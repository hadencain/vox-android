package com.hadencain.vox

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Vox"; textSize = 32f })
    }

    override fun onResume() {
        super.onResume()
        startVoxIfReady()
    }

    private fun startVoxIfReady() {
        if (started) return
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            !mic -> requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            !android.provider.Settings.canDrawOverlays(this) ->
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            else -> {
                started = true
                startForegroundService(android.content.Intent(this, VoxService::class.java))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) startVoxIfReady()
    }
}
