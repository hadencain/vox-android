package com.hadencain.vox.core

import org.junit.Assert.*
import org.junit.Test

class MineTest {
    private fun e(raw: String, cleaned: String) = HistoryEntry(0L, raw, cleaned, null, "dictate")

    @Test fun `finds a repeated single-word mishearing`() {
        val entries = listOf(
            e("I use juce for embedded audio", "I use deuce for embedded audio"),
            e("open the juce project", "open the deuce project"),
        )
        val result = Mine.mineCorrections(entries)
        assertEquals(listOf(Mine.Correction("juce", "deuce", 2)), result)
    }

    @Test fun `ignores casing-only changes`() {
        val entries = listOf(e("hello world", "Hello world"), e("hello there", "Hello there"))
        assertTrue(Mine.mineCorrections(entries).isEmpty())
    }

    @Test fun `respects minCount threshold`() {
        val entries = listOf(e("I use juce here", "I use deuce here"))
        assertTrue(Mine.mineCorrections(entries, minCount = 2).isEmpty())
        assertEquals(1, Mine.mineCorrections(entries, minCount = 1).size)
    }

    @Test fun `alignment survives filler-word removal`() {
        val entries = listOf(
            e("um I use juce", "I use deuce"),
            e("uh open juce now", "open deuce now"),
        )
        assertEquals(listOf(Mine.Correction("juce", "deuce", 2)), Mine.mineCorrections(entries))
    }

    @Test fun `finds recurring capitalized proper nouns in cleaned text`() {
        val entries = listOf(
            e("meet me at the place tomorrow", "Meet me at Overlook tomorrow"),
            e("is the place closed", "Is Overlook closed"),
        )
        val result = Mine.mineBiasTerms(entries)
        assertEquals(listOf(Mine.BiasTerm("Overlook", 2)), result)
    }

    @Test fun `bias terms filter stopwords and short words`() {
        val entries = listOf(e("hi", "Hi there"), e("hi", "Hi there"))
        assertTrue(Mine.mineBiasTerms(entries).none { it.term == "Hi" })
    }

    @Test fun `suggest filters out terms already known`() {
        val entries = listOf(
            e("I use juce here", "I use deuce here"),
            e("open juce now", "open deuce now"),
            e("meet at Overlook", "meet at Overlook"),
            e("Overlook is closed", "Overlook is closed"),
        )
        val known = Mine.suggest(entries, existingVocab = listOf("overlook"), existingCorrections = mapOf("juce" to "deuce"))
        assertTrue(known.corrections.isEmpty())
        assertTrue(known.biasTerms.isEmpty())
        val fresh = Mine.suggest(entries, existingVocab = emptyList(), existingCorrections = emptyMap())
        assertEquals(1, fresh.corrections.size)
        assertEquals(1, fresh.biasTerms.size)
    }
}
