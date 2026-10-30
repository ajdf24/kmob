package de.kmob.adapter.secrets

import de.kmob.application.port.SecretScanner
import de.kmob.domain.handover.Snapshot
import de.kmob.domain.secrets.Hit
import de.kmob.domain.secrets.ScanOutcome
import de.kmob.domain.secrets.SecretMatcher
import okio.FileSystem
import okio.buffer
import okio.use

/** Fallback ohne gitleaks (R5.7): Pflichtmuster zeilenweise über Metadaten und Log. */
class BuiltinScanner(
    private val fs: FileSystem,
    private val matcher: SecretMatcher = SecretMatcher(),
) : SecretScanner {
    override val name = "built-in patterns only"

    override fun scan(snapshot: Snapshot): ScanOutcome {
        val hits = metadataHits(snapshot) + logHits(snapshot)
        return if (hits.isEmpty()) ScanOutcome.Clean else ScanOutcome.Findings(hits)
    }

    /** IDs aller Regeln, die in [text] einen nicht erlaubten Treffer haben. */
    fun matchRules(text: String): List<String> = matcher.matchingRuleIds(text)

    private fun metadataHits(snapshot: Snapshot): List<Hit> {
        val ids = matchRules(fs.read(snapshot.metadata) { readUtf8() })
        return if (ids.isEmpty()) emptyList() else listOf(Hit(ids, "session metadata"))
    }

    private fun logHits(snapshot: Snapshot): List<Hit> = fs.source(snapshot.log).buffer().use { log ->
        val hits = mutableListOf<Hit>()
        var entry = 0
        while (true) {
            val line = log.readUtf8Line() ?: break
            entry++
            val ids = matchRules(line)
            if (ids.isNotEmpty()) hits += Hit(ids, "log entry #$entry")
        }
        hits
    }
}
