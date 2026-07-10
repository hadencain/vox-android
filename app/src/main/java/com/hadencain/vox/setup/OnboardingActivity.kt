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
    private val dlFailed = mutableSetOf<String>()
    private var started = false
    private var ramBlocked = false

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
            ramBlocked = true
            setContentView(TextView(this).apply {
                text = "Vox needs a phone with at least 6GB of RAM to run its on-device " +
                    "speech models. This device has ${mi.totalMem / (1024 * 1024 * 1024)}GB."
                textSize = 18f; setPadding(48, 96, 48, 48)
            })
            return
        }
        // 3. kick off model downloads immediately (concurrent with 2/4/5 — spec)
        startMissingDownloads()
    }

    /** For every model missing from disk: adopt an in-flight download, finalize a
     *  finished one, leave a failed one for retry-tap, or enqueue fresh. */
    private fun startMissingDownloads() {
        var anyPending = false
        for (spec in ModelDownloader.MODELS) {
            if (java.io.File(ModelDownloader.modelsDir(this), spec.fileName).exists()) continue
            anyPending = true
            val existingId = ModelDownloader.knownId(this, spec)
            if (existingId == -1L) {
                downloadIds[spec.fileName] = ModelDownloader.enqueue(this, spec)
                continue
            }
            val (state, _) = ModelDownloader.status(this, existingId)
            when (state) {
                ModelDownloader.DlState.NONE -> {
                    downloadIds[spec.fileName] = ModelDownloader.enqueue(this, spec)
                }
                ModelDownloader.DlState.RUNNING -> {
                    downloadIds[spec.fileName] = existingId
                }
                ModelDownloader.DlState.SUCCESS -> {
                    if (ModelDownloader.finalize(this, spec)) {
                        ModelDownloader.forget(this, spec)
                    } else {
                        ModelDownloader.forget(this, spec)
                        dlFailed.add(spec.fileName)
                    }
                }
                ModelDownloader.DlState.FAILED -> {
                    ModelDownloader.forget(this, spec)
                    dlFailed.add(spec.fileName)
                }
            }
        }
        if (anyPending) pollDownloads()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!ramBlocked) refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun pollDownloads() {
        handler.postDelayed({
            if (isDestroyed || isFinishing) return@postDelayed
            var anyRunning = false
            for ((name, id) in downloadIds.toMap()) {
                val spec = ModelDownloader.MODELS.first { it.fileName == name }
                val (state, _) = ModelDownloader.status(this, id)
                when (state) {
                    ModelDownloader.DlState.SUCCESS -> {
                        if (!ModelDownloader.finalize(this, spec)) dlFailed.add(name)
                        ModelDownloader.forget(this, spec)
                        downloadIds.remove(name)
                    }
                    ModelDownloader.DlState.FAILED -> {
                        ModelDownloader.forget(this, spec)
                        dlFailed.add(name)
                        downloadIds.remove(name)
                    }
                    ModelDownloader.DlState.RUNNING -> anyRunning = true
                    ModelDownloader.DlState.NONE -> {
                        ModelDownloader.forget(this, spec)
                        dlFailed.add(name)
                        downloadIds.remove(name)
                    }
                }
            }
            refresh()
            if (anyRunning) pollDownloads()
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
        rows["Models"]!!.apply {
            when {
                ModelDownloader.allPresent(this@OnboardingActivity) -> {
                    text = "✓ Models downloaded"
                    setOnClickListener(null)
                }
                dlFailed.isNotEmpty() -> {
                    text = "✗ Model download failed — tap to retry"
                    setOnClickListener {
                        dlFailed.clear()
                        startMissingDownloads()
                    }
                }
                else -> {
                    text = "⇣ Downloading models over Wi-Fi (~740MB)…"
                    setOnClickListener(null)
                }
            }
        }
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
        if (started) return
        val ready = Settings.canDrawOverlays(this) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
            ModelDownloader.allPresent(this)
        if (ready) {
            started = true
            startForegroundService(Intent(this, VoxService::class.java))
            finish()
        } else refresh()
    }
}
