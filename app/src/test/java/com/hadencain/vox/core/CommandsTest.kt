package com.hadencain.vox.core

import org.junit.Assert.*
import org.junit.Test

class CommandsTest {
    @Test fun `whole-utterance cancel phrases match`() {
        assertTrue(Commands.isCancel("scratch that", true))
        assertTrue(Commands.isCancel("Scratch that.", true))
        assertTrue(Commands.isCancel(" never mind! ", true))
    }
    @Test fun `dictation containing a phrase is not a cancel`() {
        assertFalse(Commands.isCancel("please scratch that off the list", true))
    }
    @Test fun `disabled or empty never cancels`() {
        assertFalse(Commands.isCancel("scratch that", false))
        assertFalse(Commands.isCancel("", true))
    }
    @Test fun `utterance ending with a cancel phrase cancels`() {
        assertTrue(Commands.isCancel("I'm testing my new tool. Scratch that.", true))
        assertTrue(Commands.isCancel("set a meeting for thursday never mind", true))
    }
    @Test fun `cancel phrase mid-sentence does not cancel`() {
        assertFalse(Commands.isCancel("please scratch that off the list", true))
    }
}
