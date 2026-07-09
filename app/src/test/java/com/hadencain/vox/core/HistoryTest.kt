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
}
