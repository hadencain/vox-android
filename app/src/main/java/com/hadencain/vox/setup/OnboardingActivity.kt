package com.hadencain.vox.setup

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hadencain.vox.VoxService
import com.hadencain.vox.inject.VoxAccessibilityService

class OnboardingActivity : AppCompatActivity() {
    private lateinit var rows: Map<String, TextView>
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var downloadIds = mutableMapOf<String, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48)
        }
        rows = listOf("RAM", "Microphone", "Models", "Overlay", "Accessibility")
            .associateWith { name ->
                TextView(this).apply { textSize = 18f; setPadding(0, 24, 0, 24); root.addView(this) }
            }
        root.addView(Button(this).apply {
            text = "Start Vox"
            setOnClickListener { maybeStart() }
        })
        setContentView(root)

        // 1. RAM gate — hard stop below floor
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        if (mi.totalMem < 6L * 1024 * 1024 * 1024) {
            setContentView(TextView(this).apply {
                text = "Vox needs a phone with at least 6GB of RAM to run its on-device " +
                    "speech models. This device has ${mi.totalMem / (1024 * 1024 * 1024)}GB."
                textSize = 18f; setPadding(48, 96, 48, 48)
            })
            return
        }
        // 3. kick off model downloads immediately (concurrent with 2/4/5 — spec)
        if (!ModelDownloader.allPresent(this)) {
            for (spec in ModelDownloader.MODELS) {
                if (!java.io.File(ModelDownloader.modelsDir(this), spec.fileName).exists())
                    downloadIds[spec.fileName] = ModelDownloader.enqueue(this, spec)
            }
            pollDownloads()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun pollDownloads() {
        handler.postDelayed({
            for ((name, id) in downloadIds) {
                val (done, total) = ModelDownloader.progress(this, id)
                if (total in 1..done) {
                    ModelDownloader.MODELS.first { it.fileName == name }
                        .let { ModelDownloader.finalize(this, it) }
                }
            }
            refresh()
            if (!ModelDownloader.allPresent(this)) pollDownloads()
        }, 1000)
    }

    private fun refresh() {
        rows["RAM"]!!.text = "✓ Device check passed"
        val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        rows["Microphone"]!!.text = if (mic) "✓ Microphone" else "○ Microphone — tap to grant"
        rows["Microphone"]!!.setOnClickListener {
            if (!mic) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
        rows["Models"]!!.text = if (ModelDownloader.allPresent(this)) "✓ Models downloaded"
            else "⇣ Downloading models over Wi-Fi (~740MB)…"
        val overlay = Settings.canDrawOverlays(this)
        rows["Overlay"]!!.text = if (overlay) "✓ Display over other apps"
            else "○ Display over other apps — tap to open settings"
        rows["Overlay"]!!.setOnClickListener {
            if (!overlay) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        val a11y = VoxAccessibilityService.instance != null
        rows["Accessibility"]!!.text = if (a11y) "✓ Accessibility (lets Vox type for you)"
            else "○ Accessibility — tap to open settings. This is what lets Vox type your " +
                 "words into other apps, the same access any keyboard has. Nothing leaves your phone." +
                 " On Samsung: Settings > Accessibility > Installed apps > Vox."
        rows["Accessibility"]!!.setOnClickListener {
            if (!a11y) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun maybeStart() {
        val ready = Settings.canDrawOverlays(this) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
            ModelDownloader.allPresent(this)
        if (ready) {
            startForegroundService(Intent(this, VoxService::class.java))
            finish()
        } else refresh()
    }
}
