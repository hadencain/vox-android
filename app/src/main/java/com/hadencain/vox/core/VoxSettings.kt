package com.hadencain.vox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Central config (port of desktop config.py). One dataclass; every stage reads from it. */
@Serializable
data class VoxSettings(
    // ASR
    val language: String = "en",
    // cleanup
    val enableCleanup: Boolean = true,
    val enableContext: Boolean = true,
    // dictionary
    val vocab: List<String> = emptyList(),
    val corrections: Map<String, String> = emptyMap(),
    // history
    val saveHistory: Boolean = true,
    val historyMax: Int = 500,
    // voice commands
    val enableCommands: Boolean = true,
    // capture
    val silenceTimeoutMs: Long = 4000,
    // model lifecycle
    val modelIdleUnloadMs: Long = 600_000,
) {
    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(this))
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun load(file: File): VoxSettings = try {
            if (file.exists()) json.decodeFromString(file.readText()) else VoxSettings()
        } catch (_: Exception) { VoxSettings() }
    }
}
