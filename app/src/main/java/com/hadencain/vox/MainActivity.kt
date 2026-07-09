package com.hadencain.vox

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var started = false
    private var askedMic = false
    private var askedOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Vox"; textSize = 32f })

        // TEMPORARY spike harness (Task 7) — removed in Task 9.
        Thread {
            val model = java.io.File(filesDir, "models/gemma3-1b-it-int4.task")
            if (!model.exists()) { android.util.Log.e("VoxSpike", "gemma missing"); return@Thread }
            val t0 = System.currentTimeMillis()
            val engine = com.hadencain.vox.cleanup.CleanupEngine(this@MainActivity, model.path)
            val loadMs = System.currentTimeMillis() - t0
            val raw = "um so basically i think we should uh we should ship the the android version " +
                "no wait the ios version um actually no scratch that the android version first"
            val t1 = System.currentTimeMillis()
            val cleaned = engine.clean(raw, "a chat/messaging app (com.whatsapp)")
            android.util.Log.i("VoxSpike",
                "load=${loadMs}ms infer=${System.currentTimeMillis() - t1}ms cleaned=$cleaned")
            engine.close()
        }.start()
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
            !mic -> {
                if (!askedMic) {
                    askedMic = true
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
                }
            }
            !android.provider.Settings.canDrawOverlays(this) -> {
                if (!askedOverlay) {
                    askedOverlay = true
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
            else -> {
                started = true
                startForegroundService(android.content.Intent(this, VoxService::class.java))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            askedOverlay = false
            startVoxIfReady()
        }
    }
}
