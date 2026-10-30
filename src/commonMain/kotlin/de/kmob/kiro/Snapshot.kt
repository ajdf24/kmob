package de.kmob.kiro

import de.kmob.KmobException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/** Konsistente Kopie einer Session (design §5.5): Scan und Artefakt sehen denselben Inhalt. */
data class Snapshot(val metadata: Path, val log: Path, val entries: Int)

class ExportException(id: String, reason: String) :
    KmobException("could not export session $id: $reason. Use --no-session to hand over without it.")

object SnapshotWriter {
    private val json = Json

    /**
     * Kopiert Metadaten und vollständige Log-Zeilen nach [tmpDir]. Zeile n in `log.jsonl` = Log-Eintrag n.
     * Unvollständige letzte Zeile wird verworfen (R2.3), Leerzeilen übersprungen, ungültige Zeilen → Fehler (R2.11).
     */
    fun write(fs: FileSystem, id: String, metadataSrc: Path, logSrc: Path, tmpDir: Path): Snapshot {
        fs.createDirectories(tmpDir)
        val metaText = runCatching { fs.read(metadataSrc) { readUtf8() } }
            .getOrElse { throw ExportException(id, "cannot read metadata (${it.message})") }.trim()
        val metaOk = runCatching { json.parseToJsonElement(metaText) is JsonObject }.getOrDefault(false)
        if (!metaOk) throw ExportException(id, "metadata is not a JSON object")
        val meta = tmpDir / "meta.json"
        fs.write(meta) { writeUtf8(metaText) }

        val log = tmpDir / "log.jsonl"
        var entries = 0
        runCatching { fs.source(logSrc) }.getOrElse { throw ExportException(id, "cannot read log (${it.message})") }
            .buffer().use { src ->
                fs.sink(log).buffer().use { dst ->
                    while (true) {
                        val nl = src.indexOf('\n'.code.toByte())
                        if (nl < 0) break // Rest ohne \n = unvollständig
                        val line = src.readUtf8(nl)
                        src.skip(1)
                        if (line.isBlank()) continue
                        entries++
                        val valid = runCatching { json.parseToJsonElement(line) is JsonObject }.getOrDefault(false)
                        if (!valid) throw ExportException(id, "log entry #$entries is not valid JSON")
                        dst.writeUtf8(line).writeByte('\n'.code)
                    }
                }
            }
        if (entries == 0) throw ExportException(id, "no complete log entries")
        return Snapshot(meta, log, entries)
    }
}
