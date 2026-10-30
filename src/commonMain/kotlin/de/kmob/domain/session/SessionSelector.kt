package de.kmob.domain.session

/** Nach welcher Regel aus R2.6 die Session gewählt wurde. Stand M2: nur Regel 4. */
enum class Reason(val description: String) {
    MOST_RECENT("most recent"),
}

sealed interface Selection {
    data class Chosen(val session: SessionInfo, val reason: Reason, val candidates: Int) : Selection
    data object None : Selection
}

/**
 * Auswahl der zu übergebenden Session (design §5.4). Stand M2: nur Regel 4 „zuletzt aktiv“.
 * Gleichstand → ID lexikographisch (deterministisch).
 */
object SessionSelector {
    private val mostRecentFirst = compareByDescending<SessionInfo> { it.freshness }.thenBy { it.id }

    fun select(candidates: List<SessionInfo>): Selection {
        val best = candidates.minWithOrNull(mostRecentFirst) ?: return Selection.None
        return Selection.Chosen(best, Reason.MOST_RECENT, candidates.size)
    }

    /** Auswahl-Meldung (R2.7, design §6.1). */
    fun describe(chosen: Selection.Chosen): String {
        val session = chosen.session
        val pickHint = if (chosen.candidates > 1) {
            ", ${chosen.candidates} sessions found – use --pick-session to choose"
        } else {
            ""
        }
        return "handing over Kiro session ${session.id}${quotedTitle(session.title)} " +
            "(last activity ${session.freshness}; ${chosen.reason.description}$pickHint)"
    }
}
