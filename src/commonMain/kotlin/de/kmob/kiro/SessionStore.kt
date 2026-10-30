package de.kmob.kiro

import de.kmob.Output
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

/** Eine Kiro-CLI-Session aus `~/.kiro/sessions/cli` (design §5.3). */
data class SessionInfo(
    val id: String,
    val cwd: String,
    val title: String?,
    val updatedAt: Instant?,
    val logModified: Instant?,
    val hasCompleteEntry: Boolean,
    val isSubagent: Boolean,
    val isActive: Boolean,
) {
    /** max(updated_at, mtime der .jsonl); null zählt als ältester Wert (K5-09). */
    val freshness: Instant
        get() = maxOf(updatedAt ?: Instant.DISTANT_PAST, logModified ?: Instant.DISTANT_PAST)
}

data class SessionScan(val sessions: List<SessionInfo>, val skipped: Int) {
    fun candidatesFor(root: String): List<SessionInfo> =
        sessions.filter { isWithin(it.cwd, root) && !it.isSubagent && it.hasCompleteEntry }
}

/** Segmentweiser Pfadvergleich auf aufgelösten Pfaden: `/repo` matcht nicht `/repo-x` (R2.4). */
fun isWithin(path: String, root: String): Boolean {
    val p = path.trimEnd('/').ifEmpty { "/" }
    val r = root.trimEnd('/').ifEmpty { "/" }
    return p == r || p.startsWith(if (r == "/") "/" else "$r/")
}

class SessionStore(
    private val fs: FileSystem,
    private val platform: Platform,
    private val out: Output,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Session-Verzeichnis: `KMOB_KIRO_SESSIONS_DIR` oder `~/.kiro/sessions/cli` (R6.2). */
    val directory: Path
        get() = (platform.env("KMOB_KIRO_SESSIONS_DIR")?.takeIf { it.isNotEmpty() }
            ?: "${platform.homeDir().trimEnd('/')}/.kiro/sessions/cli").toPath()

    fun scan(): SessionScan {
        val dir = directory
        val files = runCatching { fs.list(dir) }.getOrElse {
            out.debug { "session directory $dir not readable: ${it.message}" }
            return SessionScan(emptyList(), 0)
        }.filter { it.name.endsWith(".json") }

        var skipped = 0
        val sessions = files.mapNotNull { file ->
            runCatching { read(file) }.getOrElse {
                out.debug { "skipping ${file.name}: ${it.message}" }
                null
            }.also { if (it == null) skipped++ }
        }
        return SessionScan(sessions, skipped)
    }

    /** Pfade der Quelldateien einer Session. */
    fun metadataFile(id: String): Path = directory / "$id.json"
    fun logFile(id: String): Path = directory / "$id.jsonl"

    private fun read(file: Path): SessionInfo {
        val id = file.name.removeSuffix(".json")
        val obj = json.parseToJsonElement(fs.read(file) { readUtf8() }) as? JsonObject
            ?: error("not a JSON object")
        val cwdRaw = (obj["cwd"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("no cwd")
        val log = logFile(id)
        val logMeta = fs.metadataOrNull(log)
        return SessionInfo(
            id = id,
            cwd = platform.realPath(cwdRaw) ?: cwdRaw,
            title = (obj["title"] as? JsonPrimitive)?.contentOrNull,
            updatedAt = (obj["updated_at"] as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() },
            logModified = logMeta?.lastModifiedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            hasCompleteEntry = logMeta != null && hasCompleteEntry(log),
            isSubagent = obj["parent_session_id"].let { it != null && it != JsonNull },
            isActive = fs.exists(directory / "$id.lock"),
        )
    }

    /** Mindestens eine nicht-leere, mit `\n` abgeschlossene Zeile (N-02). Liest nur bis zum ersten Treffer. */
    private fun hasCompleteEntry(log: Path): Boolean = fs.source(log).buffer().use { src ->
        var found = false
        while (!found) {
            val nl = src.indexOf('\n'.code.toByte())
            if (nl < 0) break
            found = src.readUtf8(nl).isNotBlank()
            src.skip(1)
        }
        found
    }
}
