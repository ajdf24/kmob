package de.kmob.kiro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactWriterTest {
    private val fs = FakeFileSystem()
    private val tmp = "/r/.git/kmob/tmp".toPath()
    private val kmob = "/r/.kmob".toPath()
    private val meta = """{"session_id":"s1","cwd":"/r","unknown_field":{"keep":[1,"ü"]}}"""
    private val lines = listOf("""{"version":"v1","kind":"Prompt","data":{"x":1}}""", """{"version":"v1","kind":"AssistantMessage","data":{}}""")

    init {
        fs.createDirectories(tmp)
        fs.write(tmp / "meta.json") { writeUtf8(meta) }
        fs.write(tmp / "log.jsonl") { writeUtf8(lines.joinToString("\n", postfix = "\n")) }
    }

    private val snapshot = Snapshot(tmp / "meta.json", tmp / "log.jsonl", 2)
    private fun info(bytes: Long) = HandoverInfo(
        kmob_version = "0.1.0", session_id = "s1", title = "T", freshness = "2026-10-29T13:18:01Z",
        log_entries = 2, artifact_bytes = bytes, from = "Anna", handed_over_at = "2026-10-29T13:20:00Z",
    )

    @Test
    fun writesExportFormatByteExact() {
        val w = ArtifactWriter(fs).write(snapshot, kmob, tmp, ::info)
        val text = fs.read(kmob / ARTIFACT_FILE) { readUtf8() }
        assertEquals("""{"format":"kiro-session-export-v1","metadata":$meta,"log_entries":[${lines.joinToString(",")}]}""", text)
        assertEquals(text.encodeToByteArray().size.toLong(), w.bytes)
        // gültiges JSON in Spike-S1-Struktur
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("kiro-session-export-v1", obj["format"]!!.jsonPrimitive.content)
        assertEquals(2, obj["log_entries"]!!.jsonArray.size)
        val handover = HandoverInfo.decodeOrNull(fs.read(kmob / HANDOVER_FILE) { readUtf8() })!!
        assertEquals(w.bytes, handover.artifact_bytes)
        assertFalse(fs.exists(tmp / ARTIFACT_FILE), "tmp cleaned")
    }

    @Test
    fun overwritesExistingFiles() {
        fs.createDirectories(kmob)
        fs.write(kmob / ARTIFACT_FILE) { writeUtf8("old") }
        fs.write(kmob / HANDOVER_FILE) { writeUtf8("old") }
        ArtifactWriter(fs).write(snapshot, kmob, tmp, ::info)
        assertTrue(fs.read(kmob / ARTIFACT_FILE) { readUtf8() }.startsWith("{\"format\""))
        assertEquals("s1", HandoverInfo.decodeOrNull(fs.read(kmob / HANDOVER_FILE) { readUtf8() })!!.session_id)
    }

    @Test
    fun existingArtifactUntouchedOnFailure() {
        fs.createDirectories(kmob)
        fs.write(kmob / ARTIFACT_FILE) { writeUtf8("old") }
        fs.delete(snapshot.log)
        assertFailsWith<de.kmob.KmobException> { ArtifactWriter(fs).write(snapshot, kmob, tmp, ::info) }
        assertEquals("old", fs.read(kmob / ARTIFACT_FILE) { readUtf8() })
        assertFalse(fs.exists(tmp / ARTIFACT_FILE))
    }

    @Test
    fun handoverDecodeIsTolerant() {
        assertNull(HandoverInfo.decodeOrNull(null))
        assertNull(HandoverInfo.decodeOrNull("{broken"))
        assertNull(HandoverInfo.decodeOrNull("""{"schema":1}"""))
        val withExtra = HandoverInfo.encode(info(5)).replace("\"schema\"", "\"future\": true, \"schema\"")
        assertEquals("Anna", HandoverInfo.decodeOrNull(withExtra)!!.from)
    }
}
