package de.kmob.adapter.kiro

import de.kmob.domain.handover.ExportException
import de.kmob.domain.handover.Snapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/** Konsistente Kopie einer laufenden Session nach `tmp/` (design §5.5). */
object SnapshotWriter {
    private const val METADATA_FILE = "meta.json"
    private const val LOG_FILE = "log.jsonl"

    /**
     * Kopiert Metadaten und vollständige Log-Zeilen nach [tmpDir]. Zeile n in `log.jsonl` = Log-Eintrag n.
     * Unvollständige letzte Zeile wird verworfen (R2.3), Leerzeilen übersprungen, ungültige Zeilen → Fehler (R2.11).
     */
    fun write(fs: FileSystem, id: String, metadataSource: Path, logSource: Path, tmpDir: Path): Snapshot {
        fs.createDirectories(tmpDir)
        val metadataText = readMetadata(fs, id, metadataSource)
        val metadata = tmpDir / METADATA_FILE
        fs.write(metadata) { writeUtf8(metadataText) }

        val log = tmpDir / LOG_FILE
        val source = runCatching { fs.source(logSource) }
            .getOrElse { throw ExportException(id, "cannot read log (${it.message})") }
        val entries = source.buffer().use { from ->
            fs.sink(log).buffer().use { to -> copyCompleteEntries(id, from, to) }
        }
        if (entries == 0) throw ExportException(id, "no complete log entries")
        return Snapshot(metadata, log, entries)
    }

    /** Getrimmter Originaltext der Metadaten; muss ein JSON-Objekt sein. */
    private fun readMetadata(fs: FileSystem, id: String, metadataSource: Path): String {
        val text = runCatching { fs.read(metadataSource) { readUtf8() } }
            .getOrElse { throw ExportException(id, "cannot read metadata (${it.message})") }.trim()
        if (!isJsonObject(text)) throw ExportException(id, "metadata is not a JSON object")
        return text
    }

    /** Liefert die Anzahl kopierter Einträge. Der Rest ohne abschließendes `\n` ist unvollständig. */
    private fun copyCompleteEntries(id: String, from: BufferedSource, to: BufferedSink): Int {
        var entries = 0
        while (true) {
            val newline = from.indexOf('\n'.code.toByte())
            if (newline < 0) break
            val line = from.readUtf8(newline)
            from.skip(1)
            if (line.isBlank()) continue
            entries++
            if (!isJsonObject(line)) throw ExportException(id, "log entry #$entries is not valid JSON")
            to.writeUtf8(line).writeByte('\n'.code)
        }
        return entries
    }

    private fun isJsonObject(text: String): Boolean =
        runCatching { Json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)
}
