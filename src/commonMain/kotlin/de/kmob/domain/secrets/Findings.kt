package de.kmob.domain.secrets

import de.kmob.domain.KmobException

/** Fundort eines Treffers: `session metadata` oder `log entry #n` – nie der Wert selbst (R5.2). */
data class Hit(val ruleIds: List<String>, val location: String)

sealed interface ScanOutcome {
    data object Clean : ScanOutcome
    data class Findings(val hits: List<Hit>) : ScanOutcome
}

/** IDs aller Regeln, die in einem Text einen nicht erlaubten Treffer haben. */
class SecretMatcher(private val rules: List<SecretRule> = MandatoryRules.all) {
    fun matchingRuleIds(text: String): List<String> = rules.filter { it.matches(text) }.map { it.id }
}

class SecretsFoundException(val hits: List<Hit>) :
    KmobException("handover aborted. Start a new session (/chat new) or use --no-session; use --allow-secrets only after checking.")

private const val MAX_REPORTED_HITS = 10

/** Meldungszeilen zu Treffern (max. 10, design §5.6). */
fun describeHits(hits: List<Hit>, max: Int = MAX_REPORTED_HITS): List<String> {
    val lines = hits.take(max).map { "possible secret (${it.ruleIds.joinToString(", ")}) in ${it.location}" }
    return if (hits.size > max) lines + "… and ${hits.size - max} more" else lines
}
