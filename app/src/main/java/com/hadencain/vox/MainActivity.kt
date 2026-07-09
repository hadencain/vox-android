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

        // TEMPORARY spike harness (Task 6) — removed in Task 9.
        Thread {
            val model = java.io.File(filesDir, "models/ggml-small-q5_1.bin")
            if (!model.exists()) { android.util.Log.e("VoxSpike", "model missing: ${model.path}"); return@Thread }
            // decode bundled 16kHz mono 16-bit wav -> float
            val bytes = assets.open("jfk.wav").readBytes()
            val pcm = FloatArray((bytes.size - 44) / 2)
            for (i in pcm.indices) {
                val lo = bytes[44 + 2 * i].toInt() and 0xFF
                val hi = bytes[45 + 2 * i].toInt()
                pcm[i] = ((hi shl 8) or lo) / 32768f
            }
            val t0 = System.currentTimeMillis()
            val h = com.hadencain.vox.asr.WhisperBridge.init(model.path)
            val loadMs = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            val text = com.hadencain.vox.asr.WhisperBridge.transcribe(h, pcm, null)
            android.util.Log.i("VoxSpike", "load=${loadMs}ms infer=${System.currentTimeMillis() - t1}ms text=$text")
            com.hadencain.vox.asr.WhisperBridge.release(h)
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
