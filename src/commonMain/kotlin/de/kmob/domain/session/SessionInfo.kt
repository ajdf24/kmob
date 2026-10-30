package de.kmob.domain.session

import kotlin.time.Instant

/** Eine Kiro-CLI-Session (design §5.3). */
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

    /** Kandidat für die Übergabe aus [root] (R2.4, R2.5); [separator] ist der Verzeichnistrenner der Pfade. */
    fun isCandidateFor(root: String, separator: Char): Boolean =
        isWithin(cwd, root, separator) && !isSubagent && hasCompleteEntry
}

/** Ergebnis des Einlesens: lesbare Sessions und Anzahl übersprungener Dateien (R7.2). */
data class SessionScan(val sessions: List<SessionInfo>, val skipped: Int) {
    fun candidatesFor(root: String, separator: Char): List<SessionInfo> =
        sessions.filter { it.isCandidateFor(root, separator) }
}

/**
 * Segmentweiser Pfadvergleich auf aufgelösten Pfaden: `/repo` matcht nicht `/repo-x` (R2.4).
 * Der Verzeichnistrenner kommt von der Plattform, damit der Kern keine Pfadkonvention kennt (R10.2).
 */
fun isWithin(path: String, root: String, separator: Char): Boolean {
    val candidate = withoutTrailingSeparators(path, separator)
    val base = withoutTrailingSeparators(root, separator)
    val baseAsPrefix = if (base.length == 1 && base[0] == separator) base else base + separator
    return candidate == base || candidate.startsWith(baseAsPrefix)
}

/** `"/repo/"` → `"/repo"`; die Wurzel bleibt erhalten. */
private fun withoutTrailingSeparators(path: String, separator: Char): String =
    path.trimEnd(separator).ifEmpty { separator.toString() }
