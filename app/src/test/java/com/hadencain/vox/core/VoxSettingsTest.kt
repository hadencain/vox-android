package com.hadencain.vox.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoxSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `defaults load when file missing`() {
        val s = VoxSettings.load(File(tmp.root, "nope.json"))
        assertTrue(s.enableCleanup)
        assertEquals("en", s.language)
    }
    @Test fun `round-trips through disk`() {
        val f = File(tmp.root, "settings.json")
        VoxSettings(enableCleanup = false, vocab = listOf("JUCE")).save(f)
        val s = VoxSettings.load(f)
        assertFalse(s.enableCleanup)
        assertEquals(listOf("JUCE"), s.vocab)
    }
    @Test fun `corrupt file falls back to defaults`() {
        val f = File(tmp.root, "settings.json").apply { writeText("{not json") }
        assertTrue(VoxSettings.load(f).enableCleanup)
    }
}
