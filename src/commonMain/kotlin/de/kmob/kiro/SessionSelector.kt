package de.kmob.kiro

enum class Reason { MOST_RECENT }

sealed interface Selection {
    data class Chosen(val session: SessionInfo, val reason: Reason, val candidates: Int) : Selection
    data object None : Selection
}

/**
 * Auswahl der zu übergebenden Session (design §5.4). Stand M2: nur Regel 4 „zuletzt aktiv“.
 * Gleichstand → ID lexikographisch (deterministisch).
 */
object SessionSelector {
    fun select(candidates: List<SessionInfo>): Selection {
        val best = candidates.sortedWith(compareByDescending<SessionInfo> { it.freshness }.thenBy { it.id })
            .firstOrNull() ?: return Selection.None
        return Selection.Chosen(best, Reason.MOST_RECENT, candidates.size)
    }

    /** Auswahl-Meldung (R2.7, design §6.1). */
    fun describe(c: Selection.Chosen): String {
        val s = c.session
        val title = s.title?.let { " \"${it.lineSequence().first().take(80)}\"" } ?: ""
        val rule = when (c.reason) {
            Reason.MOST_RECENT -> "most recent"
        }
        val hint = if (c.candidates > 1) ", ${c.candidates} sessions found – use --pick-session to choose" else ""
        return "handing over Kiro session ${s.id}$title (last activity ${s.freshness}; $rule$hint)"
    }
}
