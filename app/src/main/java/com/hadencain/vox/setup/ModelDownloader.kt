package com.hadencain.vox.setup

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** System DownloadManager wrapper: resumable, survives app death, Wi-Fi-only.
 *  Downloads land in getExternalFilesDir then move to filesDir/models. */
object ModelDownloader {
    data class ModelSpec(val url: String, val fileName: String, val sizeBytes: Long)

    // Host these yourself (bucket / HF repo you control) — Gemma's HF distribution is
    // license-gated and can't be hot-linked. URLs are the only deploy-time config.
    // TODO(deploy): real hosting URLs required before release
    val MODELS = listOf(
        ModelSpec("https://REPLACE-WITH-YOUR-HOST/ggml-small-q5_1.bin",
            "ggml-small-q5_1.bin", 190_000_000L),
        ModelSpec("https://REPLACE-WITH-YOUR-HOST/gemma3-1b-it-int4.task",
            "gemma3-1b-it-int4.task", 550_000_000L),
    )

    fun modelsDir(ctx: Context) = File(ctx.filesDir, "models")
    fun allPresent(ctx: Context) = MODELS.all { File(modelsDir(ctx), it.fileName).exists() }

    fun enqueue(ctx: Context, spec: ModelSpec): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle("Vox model: ${spec.fileName}")
            .setDestinationInExternalFilesDir(ctx, null, spec.fileName)
        return dm.enqueue(req)
    }

    /** (downloadedBytes, totalBytes); total -1 while unknown. */
    fun progress(ctx: Context, id: Long): Pair<Long, Long> {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return 0L to -1L
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return done to total
        }
    }

    /** Move a finished download from external files into filesDir/models. */
    fun finalize(ctx: Context, spec: ModelSpec): Boolean {
        val src = File(ctx.getExternalFilesDir(null), spec.fileName)
        if (!src.exists()) return false
        modelsDir(ctx).mkdirs()
        return src.renameTo(File(modelsDir(ctx), spec.fileName))
    }
}
