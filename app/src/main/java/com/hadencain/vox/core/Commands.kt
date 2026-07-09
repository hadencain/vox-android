package com.hadencain.vox.core

/** Whole-utterance voice commands, checked against the RAW transcript before cleanup.
 *  Only phrases that ARE the entire utterance count (port of desktop commands.py). */
object Commands {
    private val CANCEL = setOf(
        "scratch that", "cancel that", "delete that", "forget that",
        "never mind", "nevermind", "cancel", "stop stop stop",
    )
    private fun normalize(raw: String) = raw.trim().lowercase().trim('.', '!', '?', ',', ' ')
    fun isCancel(raw: String, enabled: Boolean): Boolean =
        enabled && raw.isNotBlank() && normalize(raw) in CANCEL
}
