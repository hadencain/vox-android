package com.hadencain.vox.core

/** Voice commands, checked against the RAW transcript before cleanup. A phrase cancels the
 *  take when it IS the entire (normalized) utterance, or when it's the trailing clause --
 *  e.g. "I'm testing my new tool. Scratch that." Mid-sentence occurrences don't count
 *  (port + extension of desktop commands.py). */
object Commands {
    private val CANCEL = setOf(
        "scratch that", "cancel that", "delete that", "forget that",
        "never mind", "nevermind", "cancel", "stop stop stop",
    )
    private fun normalize(raw: String) = raw.trim().lowercase().trim('.', '!', '?', ',', ' ')
    fun isCancel(raw: String, enabled: Boolean): Boolean {
        if (!enabled || raw.isBlank()) return false
        val normalized = normalize(raw)
        if (normalized in CANCEL) return true
        return CANCEL.any { phrase -> normalized.endsWith(" $phrase") }
    }
}
