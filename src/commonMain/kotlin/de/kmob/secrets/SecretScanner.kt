package de.kmob.secrets

import de.kmob.KmobException
import de.kmob.kiro.Snapshot
import okio.FileSystem
import okio.buffer
import okio.use

/** Fundort eines Treffers: `session metadata` oder `log entry #n` – nie der Wert selbst (R5.2). */
data class Hit(val ruleIds: List<String>, val location: String)

sealed interface ScanOutcome {
    data object Clean : ScanOutcome
    data class Findings(val hits: List<Hit>) : ScanOutcome
}

interface SecretScanner {
    /** z. B. "built-in patterns only" oder "gitleaks 8.30.1" (für Bestätigungstext). */
    val name: String
    fun scan(fs: FileSystem, snapshot: Snapshot): ScanOutcome
}

/** Fallback ohne gitleaks (R5.7): Pflichtmuster, Vorfilter per contains, Regex nur bei Vorfilter-Treffer. */
class BuiltinScanner(private val rules: List<SecretRule> = MandatoryRules.all) : SecretScanner {
    override val name = "built-in patterns only"

    override fun scan(fs: FileSystem, snapshot: Snapshot): ScanOutcome {
        val hits = mutableListOf<Hit>()
        matchRules(fs.read(snapshot.metadata) { readUtf8() }).let { if (it.isNotEmpty()) hits += Hit(it, "session metadata") }
        fs.source(snapshot.log).buffer().use { src ->
            var n = 0
            while (true) {
                val line = src.readUtf8Line() ?: break
                n++
                val ids = matchRules(line)
                if (ids.isNotEmpty()) hits += Hit(ids, "log entry #$n")
            }
        }
        return if (hits.isEmpty()) ScanOutcome.Clean else ScanOutcome.Findings(hits)
    }

    /** IDs aller Regeln, die in [text] einen nicht erlaubten Treffer haben. */
    fun matchRules(text: String): List<String> = rules.filter { rule ->
        rule.keywords.any { text.contains(it, ignoreCase = rule.keywordsIgnoreCase) } &&
            rule.compiled.findAll(text).any { m ->
                val secret = m.groupValues.getOrElse(1) { m.value }
                !rule.isAllowed(secret)
            }
    }.map { it.id }
}

class SecretsFoundException(val hits: List<Hit>) : KmobException("handover aborted. Start a new session (/chat new) or use --no-session; use --allow-secrets only after checking.")

/** Meldungszeilen zu Treffern (max. 10, design §5.6). */
fun describeHits(hits: List<Hit>, max: Int = 10): List<String> {
    val lines = hits.take(max).map { "possible secret (${it.ruleIds.joinToString(", ")}) in ${it.location}" }
    return if (hits.size > max) lines + "… and ${hits.size - max} more" else lines
}
