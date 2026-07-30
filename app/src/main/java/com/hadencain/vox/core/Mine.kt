package com.hadencain.vox.core

/** Mines dictation history for dictionary suggestions (port of desktop mine.py).
 *  Two signals, both human-in-the-loop (the Suggestions UI shows them, the user decides
 *  whether to add each one):
 *  - corrections: repeated single-word raw->cleaned substitutions (a consistent mishearing).
 *  - bias terms: recurring capitalized/hyphenated words in cleaned output -- proper-noun
 *    candidates worth adding as Whisper bias words so they're spelled right up front.
 *  Noisy by nature (cleanup also strips filler / fixes casing), so nothing is auto-applied. */
object Mine {
    private val WORD = Regex("[A-Za-z][A-Za-z'\\-]+")

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "so", "to", "of", "in", "on", "for", "with",
        "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those",
        "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your",
        "um", "uh", "like", "know", "mean", "well", "okay", "just", "really", "actually",
        "if", "then", "than", "as", "at", "by", "from", "not", "no", "yes", "do", "does", "did",
        "have", "has", "had", "will", "would", "can", "could", "should", "there", "here", "what",
        "when", "where", "who", "how", "why", "which", "about", "into", "out", "up", "down",
    )

    private fun tokens(s: String): List<String> = WORD.findAll(s).map { it.value }.toList()

    data class Correction(val heard: String, val canonical: String, val count: Int)
    data class BiasTerm(val term: String, val count: Int)
    data class Suggestions(val corrections: List<Correction>, val biasTerms: List<BiasTerm>)

    /** Repeated single-word substitutions raw->cleaned, sorted by count desc. Skips
     *  casing-only changes (those surface as bias terms instead). */
    fun mineCorrections(entries: List<HistoryEntry>, minCount: Int = 2): List<Correction> {
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        for (e in entries) {
            for ((heard, canon) in alignedReplacements(tokens(e.raw), tokens(e.cleaned))) {
                if (heard.equals(canon, ignoreCase = true)) continue  // casing-only
                if (heard in STOP || canon.lowercase() in STOP) continue
                if (heard.length < 3 || canon.length < 3) continue
                val key = heard to canon
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries
            .map { (k, n) -> Correction(k.first, k.second, n) }
            .filter { it.count >= minCount }
            .sortedByDescending { it.count }
    }

    /** Recurring capitalized / ALLCAPS / hyphenated tokens in cleaned output. */
    fun mineBiasTerms(entries: List<HistoryEntry>, minCount: Int = 2): List<BiasTerm> {
        val counts = LinkedHashMap<String, Int>()
        for (e in entries) {
            for (t in tokens(e.cleaned)) {
                if (t.length < 3 || t.lowercase() in STOP) continue
                val isProper = t == t.uppercase() ||
                    (t[0].isUpperCase() && t.substring(1) == t.substring(1).lowercase()) ||
                    t.contains('-')
                if (isProper) counts[t] = (counts[t] ?: 0) + 1
            }
        }
        return counts.entries
            .map { (t, n) -> BiasTerm(t, n) }
            .filter { it.count >= minCount }
            .sortedByDescending { it.count }
    }

    /** Both lists, with anything already in the user's dictionary filtered out. */
    fun suggest(
        entries: List<HistoryEntry>,
        existingVocab: List<String>,
        existingCorrections: Map<String, String>,
        minCount: Int = 2,
    ): Suggestions {
        val haveVocab = existingVocab.map { it.lowercase() }.toSet()
        val haveCorr = existingCorrections.keys.map { it.lowercase() }.toSet()
        return Suggestions(
            corrections = mineCorrections(entries, minCount).filter { it.heard.lowercase() !in haveCorr },
            biasTerms = mineBiasTerms(entries, minCount).filter { it.term.lowercase() !in haveVocab },
        )
    }

    /** Word-level alignment between raw and cleaned tokens (LCS-based diff), yielding only
     *  single-word-for-single-word replacement gaps -- a from-scratch equivalent of Python's
     *  difflib.SequenceMatcher opcode filtering (op == "replace", 1-for-1 spans only), since
     *  the JVM has no difflib. Matching is case-insensitive (mirrors desktop lowercasing both
     *  sides before diffing); the returned canonical form keeps cleaned's original casing. */
    private fun alignedReplacements(a: List<String>, b: List<String>): List<Pair<String, String>> {
        val al = a.map { it.lowercase() }
        val bl = b.map { it.lowercase() }
        val n = al.size; val m = bl.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (al[i] == bl[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = mutableListOf<Pair<String, String>>()
        var i = 0; var j = 0
        var gapStartI = 0; var gapStartJ = 0
        fun flushGap(endI: Int, endJ: Int) {
            if (endI - gapStartI == 1 && endJ - gapStartJ == 1) out.add(al[gapStartI] to b[gapStartJ])
        }
        while (i < n && j < m) {
            if (al[i] == bl[j]) { flushGap(i, j); i++; j++; gapStartI = i; gapStartJ = j }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        flushGap(n, m)
        return out
    }
}
