package de.kmob.adapter.kiro

import de.kmob.application.port.Console
import de.kmob.application.port.SessionRepository
import de.kmob.domain.handover.Snapshot
import de.kmob.domain.session.SessionInfo
import de.kmob.domain.session.SessionScan
import de.kmob.platform.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.time.Instant

/**
 * Sessions der Kiro CLI im lokalen Format (design §5.3):
 * `{id}.json` (Metadaten), `{id}.jsonl` (Log), `{id}.lock` (aktiv).
 */
class SessionStore(
    private val fs: FileSystem,
    private val platform: Platform,
    private val console: Console,
) : SessionRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** Session-Verzeichnis: `KMOB_KIRO_SESSIONS_DIR` oder `<home>/.kiro/sessions/cli` (R6.2). */
    val directory: Path
        get() = platform.env("KMOB_KIRO_SESSIONS_DIR")?.takeIf { it.isNotEmpty() }?.toPath()
            ?: (platform.homeDir().toPath() / ".kiro" / "sessions" / "cli")

    override fun scan(): SessionScan {
        val dir = directory
        val metadataFiles = runCatching { fs.list(dir) }.getOrElse {
            console.debug { "session directory $dir not readable: ${it.message}" }
            return SessionScan(emptyList(), 0)
        }.filter { it.name.endsWith(".json") }

        var skipped = 0
        val sessions = metadataFiles.mapNotNull { file ->
            runCatching { read(file) }.getOrElse {
                console.debug { "skipping ${file.name}: ${it.message}" }
                skipped++
                null
            }
        }
        return SessionScan(sessions, skipped)
    }

    override fun snapshot(sessionId: String, tmpDir: Path): Snapshot =
        SnapshotWriter.write(fs, sessionId, metadataFile(sessionId), logFile(sessionId), tmpDir)

    private fun metadataFile(id: String): Path = directory / "$id.json"
    private fun logFile(id: String): Path = directory / "$id.jsonl"
    private fun lockFile(id: String): Path = directory / "$id.lock"

    private fun read(metadataFile: Path): SessionInfo {
        val id = metadataFile.name.removeSuffix(".json")
        val metadata = json.parseToJsonElement(fs.read(metadataFile) { readUtf8() }) as? JsonObject
            ?: error("not a JSON object")
        val cwd = metadata.string("cwd") ?: error("no cwd")
        val log = logFile(id)
        val logMetadata = fs.metadataOrNull(log)
        return SessionInfo(
            id = id,
            cwd = platform.realPath(cwd) ?: cwd,
            title = metadata.stringOrNull("title"),
            updatedAt = metadata.stringOrNull("updated_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            logModified = logMetadata?.lastModifiedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            hasCompleteEntry = logMetadata != null && hasCompleteEntry(log),
            isSubagent = metadata["parent_session_id"].let { it != null && it != JsonNull },
            isActive = fs.exists(lockFile(id)),
        )
    }

    /** Nur echte JSON-Strings. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Auch Zahlen o. ä. als Text, JSON-null als null (wie bisher toleriert). */
    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Mindestens eine nicht-leere, mit `\n` abgeschlossene Zeile (N-02). Liest nur bis zum ersten Treffer. */
    private fun hasCompleteEntry(log: Path): Boolean = fs.source(log).buffer().use { source ->
        var found = false
        while (!found) {
            val newline = source.indexOf('\n'.code.toByte())
            if (newline < 0) break
            found = source.readUtf8(newline).isNotBlank()
            source.skip(1)
        }
        found
    }
}
