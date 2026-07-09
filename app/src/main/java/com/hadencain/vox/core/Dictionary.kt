package com.hadencain.vox.core

/** Custom vocabulary (port of desktop vocab.py): bias terms for Whisper's initial_prompt
 *  plus word-boundary find/replace corrections applied after cleanup. */
object Dictionary {
    private const val MAX_PROMPT_CHARS = 320  // long prompts make Whisper hallucinate/loop

    fun biasPrompt(vocab: List<String>): String? {
        val terms = vocab.map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        val out = mutableListOf<String>()
        var total = 0
        for (t in terms) {
            val add = t.length + 2
            if (total + add > MAX_PROMPT_CHARS) break
            out.add(t); total += add
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun applyCorrections(text: String, corrections: Map<String, String>): String {
        var result = text
        for ((heard, canon) in corrections) {
            if (heard.isBlank()) continue
            result = Regex("\\b${Regex.escape(heard)}\\b", RegexOption.IGNORE_CASE)
                .replace(result, Regex.escapeReplacement(canon))
        }
        return result
    }
}
