package com.hadencain.vox.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(n: Int) = HistoryEntry(n.toLong(), "raw $n", "clean $n", null, "dictate")

    @Test fun `append then read round-trips`() {
        val h = History(File(tmp.root, "h.jsonl"), maxEntries = 10)
        h.append(entry(1)); h.append(entry(2))
        assertEquals(listOf("clean 1", "clean 2"), h.readAll().map { it.cleaned })
    }
    @Test fun `trims to maxEntries keeping newest`() {
        val h = History(File(tmp.root, "h.jsonl"), maxEntries = 2)
        h.append(entry(1)); h.append(entry(2)); h.append(entry(3))
        assertEquals(listOf(2L, 3L), h.readAll().map { it.timestampMs })
    }
    @Test fun `skips corrupt lines`() {
        val f = File(tmp.root, "h.jsonl").apply { writeText("garbage\n") }
        val h = History(f, maxEntries = 10)
        h.append(entry(1))
        assertEquals(1, h.readAll().size)
    }
    @Test fun `redacts raw-mode entries before writing`() {
        val h = History(File(tmp.root, "h.jsonl"), maxEntries = 10)
        h.append(HistoryEntry(1L, "my password is hunter2", "my password is hunter2", null, "raw"))
        val stored = h.readAll().single()
        assertEquals(History.REDACTED, stored.raw)
        assertEquals(History.REDACTED, stored.cleaned)
    }
    @Test fun `does not redact dictate or aiedit entries`() {
        val h = History(File(tmp.root, "h.jsonl"), maxEntries = 10)
        h.append(entry(1))
        h.append(HistoryEntry(2L, "raw text", "cleaned text", null, "aiedit"))
        val all = h.readAll()
        assertEquals("clean 1", all[0].cleaned)
        assertEquals("cleaned text", all[1].cleaned)
    }
}
