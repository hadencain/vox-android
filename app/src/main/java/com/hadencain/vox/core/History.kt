package com.hadencain.vox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val timestampMs: Long,
    val raw: String,
    val cleaned: String,
    val appPackage: String?,
    val mode: String,  // "dictate" | "raw" | "aiedit"
)

/** Local JSONL dictation log, trimmed to maxEntries on append (port of desktop history.py). */
class History(private val file: File, private val maxEntries: Int) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun append(entry: HistoryEntry) {
        val all = readAll() + entry
        val kept = all.takeLast(maxEntries)
        file.parentFile?.mkdirs()
        file.writeText(kept.joinToString("\n") { json.encodeToString(it) } + "\n")
    }

    @Synchronized
    fun readAll(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try { if (line.isBlank()) null else json.decodeFromString<HistoryEntry>(line) }
            catch (_: Exception) { null }
        }
    }
}
