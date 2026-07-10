package com.hadencain.vox.setup

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** System DownloadManager wrapper: resumable, survives app death, Wi-Fi-only.
 *  Downloads land in getExternalFilesDir then move to filesDir/models. */
object ModelDownloader {
    data class ModelSpec(val url: String, val fileName: String, val sizeBytes: Long)

    enum class DlState { NONE, RUNNING, SUCCESS, FAILED }

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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vox_downloads", Context.MODE_PRIVATE)

    /** Persisted DownloadManager id for this spec, or -1 if none is on record. */
    fun knownId(ctx: Context, spec: ModelSpec): Long =
        prefs(ctx).getLong(spec.fileName, -1L)

    /** Clear the persisted id for this spec (call after finalize or a terminal failure). */
    fun forget(ctx: Context, spec: ModelSpec) {
        prefs(ctx).edit().remove(spec.fileName).apply()
    }

    fun enqueue(ctx: Context, spec: ModelSpec): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle("Vox model: ${spec.fileName}")
            .setDestinationInExternalFilesDir(ctx, null, spec.fileName)
        val id = dm.enqueue(req)
        prefs(ctx).edit().putLong(spec.fileName, id).apply()
        return id
    }

    /** Download status plus (downloadedBytes, totalBytes); total -1 while unknown. */
    fun status(ctx: Context, id: Long): Pair<DlState, Pair<Long, Long>> {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return DlState.NONE to (0L to -1L)
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val state = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DlState.SUCCESS
                DownloadManager.STATUS_FAILED -> DlState.FAILED
                else -> DlState.RUNNING
            }
            return state to (done to total)
        }
    }

    /** (downloadedBytes, totalBytes); total -1 while unknown. */
    fun progress(ctx: Context, id: Long): Pair<Long, Long> = status(ctx, id).second

    /** Move a finished download from external files into filesDir/models. */
    fun finalize(ctx: Context, spec: ModelSpec): Boolean {
        val src = File(ctx.getExternalFilesDir(null), spec.fileName)
        if (!src.exists()) return false
        modelsDir(ctx).mkdirs()
        val dest = File(modelsDir(ctx), spec.fileName)
        if (src.renameTo(dest)) return true
        // renameTo fails silently across volumes (external-files -> filesDir on many devices).
        // Fall back to an explicit copy + delete.
        return try {
            src.copyTo(dest, overwrite = true)
            src.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}
