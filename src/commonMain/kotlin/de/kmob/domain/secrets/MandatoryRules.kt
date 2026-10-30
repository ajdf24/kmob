package de.kmob.domain.secrets

import kotlin.math.ln

/**
 * Pflichtregeln aus R5.2, eine Definition für den eingebauten Scanner und (ab M3) für gitleaks.
 * Regexe nutzen nur den gemeinsamen Teil von RE2 und Kotlin/Native-Regex: keine Lookarounds, keine Backreferences.
 * Gruppe 1 ist immer der Secret-Wert.
 */
data class SecretRule(
    val id: String,
    val description: String,
    /** Vorfilter: Regex läuft nur, wenn eins der Keywords vorkommt. */
    val keywords: List<String>,
    val keywordsIgnoreCase: Boolean,
    val regex: String,
    /** Mindest-Shannon-Entropie des Werts, 0 = keine Prüfung. */
    val minEntropy: Double = 0.0,
    /** Treffer verwerfen, wenn der Wert auf eins dieser Muster passt. */
    val allowSecret: List<String> = emptyList(),
) {
    val compiled: Regex by lazy { Regex(regex) }
    private val allow: List<Regex> by lazy { allowSecret.map(::Regex) }

    fun isAllowed(secret: String): Boolean =
        (minEntropy > 0 && shannonEntropy(secret) < minEntropy) || allow.any { it.containsMatchIn(secret) }

    /** Mindestens ein nicht erlaubter Treffer in [text]; die Regex läuft nur nach erfolgreichem Vorfilter. */
    fun matches(text: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = keywordsIgnoreCase) } &&
            compiled.findAll(text).any { match -> !isAllowed(secretOf(match)) }

    private fun secretOf(match: MatchResult): String = match.groupValues.getOrElse(1) { match.value }
}

/** Davor kein Wortzeichen bzw. ein JSON-Escape wie `\n` (Session-Log ist JSON-kodiert). */
private const val LEAD = """(?:^|[^A-Za-z0-9_]|\\[nrt])"""
private const val NO_DIGIT = """^[^0-9]*$"""

object MandatoryRules {
    val all: List<SecretRule> = listOf(
        SecretRule(
            id = "kmob-aws-access-key-id",
            description = "AWS access key ID",
            keywords = listOf("AKIA", "ASIA"), keywordsIgnoreCase = false,
            regex = """$LEAD((?:AKIA|ASIA)[0-9A-Z]{16})(?:[^0-9A-Za-z]|$)""",
            allowSecret = listOf("EXAMPLE$"),
        ),
        SecretRule(
            id = "kmob-private-key",
            description = "private key block",
            keywords = listOf("PRIVATE KEY-----"), keywordsIgnoreCase = false,
            regex = """(-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY-----[A-Za-z0-9+/=\\\s]{64,}-----END[ A-Z0-9_-]{0,100}PRIVATE KEY-----)""",
        ),
        SecretRule(
            id = "kmob-bearer-token",
            description = "bearer token",
            keywords = listOf("bearer"), keywordsIgnoreCase = true,
            regex = """(?i)(?:^|[^A-Za-z0-9_])bearer\s+([A-Za-z0-9_~+/=.-]{20,})""",
            minEntropy = 3.0,
            allowSecret = listOf(NO_DIGIT),
        ),
        SecretRule(
            id = "kmob-github-token",
            description = "GitHub token",
            keywords = listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_"), keywordsIgnoreCase = false,
            regex = """$LEAD(gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,})""",
            minEntropy = 3.0,
        ),
        SecretRule(
            id = "kmob-slack-token",
            description = "Slack token",
            keywords = listOf("xox"), keywordsIgnoreCase = false,
            regex = """$LEAD(xox[abprs]-[A-Za-z0-9-]{10,})""",
            minEntropy = 3.0,
            allowSecret = listOf(NO_DIGIT),
        ),
    )
}

fun shannonEntropy(s: String): Double {
    if (s.isEmpty()) return 0.0
    val counts = HashMap<Char, Int>()
    for (c in s) counts[c] = (counts[c] ?: 0) + 1
    val n = s.length.toDouble()
    return counts.values.sumOf { val p = it / n; -p * ln(p) / ln(2.0) }
}
