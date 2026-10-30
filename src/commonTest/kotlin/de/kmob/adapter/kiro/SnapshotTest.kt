package de.kmob.adapter.kiro

import de.kmob.domain.handover.ExportException
import de.kmob.domain.handover.Snapshot
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotTest {
    private val fs = FakeFileSystem()
    private val meta = "/s/id.json".toPath()
    private val log = "/s/id.jsonl".toPath()
    private val tmp = "/repo/.git/kmob/tmp".toPath()

    init {
        fs.createDirectories("/s".toPath())
        fs.write(meta) { writeUtf8("  {\"session_id\":\"id\",\"x\":{\"y\":[1,2]}}\n") }
    }

    private fun snapshot(logText: String): Snapshot {
        fs.write(log) { writeUtf8(logText) }
        return SnapshotWriter.write(fs, "id", meta, log, tmp)
    }

    @Test
    fun copiesCompleteLinesOnly() {
        val s = snapshot("{\"a\":1}\n\n{\"b\":\"ü\"}\n{\"c\":")
        assertEquals(2, s.entries)
        assertEquals("{\"a\":1}\n{\"b\":\"ü\"}\n", fs.read(s.log) { readUtf8() })
        assertEquals("{\"session_id\":\"id\",\"x\":{\"y\":[1,2]}}", fs.read(s.metadata) { readUtf8() })
    }

    @Test
    fun lineNumberIsEntryNumberEvenWithBlankLines() {
        val e = assertFailsWith<ExportException> { snapshot("{\"a\":1}\n\n{\"b\":2}\nnot json\n") }
        assertTrue(e.message!!.contains("log entry #3 is not valid JSON"), e.message)
        assertTrue(e.message!!.contains("--no-session"))
    }

    @Test
    fun noCompleteEntriesFails() {
        assertFailsWith<ExportException> { snapshot("{\"a\":1}") }
    }

    @Test
    fun invalidMetadataFails() {
        fs.write(meta) { writeUtf8("[]") }
        val e = assertFailsWith<ExportException> { snapshot("{}\n") }
        assertTrue(e.message!!.contains("metadata is not a JSON object"))
    }

    @Test
    fun largeLine() {
        val big = "{\"t\":\"" + "x".repeat(12 * 1024 * 1024) + "\"}"
        val s = snapshot("$big\n{}\n")
        assertEquals(2, s.entries)
        assertEquals(big.length + 4L, fs.metadata(s.log).size)
    }
}
