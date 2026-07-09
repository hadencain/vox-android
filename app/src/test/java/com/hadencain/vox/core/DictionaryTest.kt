package com.hadencain.vox.core

import org.junit.Assert.*
import org.junit.Test

class DictionaryTest {
    @Test fun `bias prompt joins terms, null when empty`() {
        assertEquals("Vox, JUCE", Dictionary.biasPrompt(listOf("Vox", " JUCE ")))
        assertNull(Dictionary.biasPrompt(emptyList()))
    }
    @Test fun `bias prompt truncates whole terms at 320 chars`() {
        val terms = List(100) { "term$it" }
        val out = Dictionary.biasPrompt(terms)!!
        assertTrue(out.length <= 320)
        assertFalse(out.endsWith(","))
    }
    @Test fun `corrections respect word boundaries, case-insensitive`() {
        val corr = mapOf("cain" to "Cain")
        assertEquals("Cain wrote it", Dictionary.applyCorrections("cain wrote it", corr))
        assertEquals("cocaine stays", Dictionary.applyCorrections("cocaine stays", corr))
    }
}
