package de.kmob.domain.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Instant

class SessionSelectorTest {
    private fun s(id: String, at: String?, log: String? = null, title: String? = "Title $id") = SessionInfo(
        id = id, cwd = "/r", title = title,
        updatedAt = at?.let { Instant.parse(it) }, logModified = log?.let { Instant.parse(it) },
        hasCompleteEntry = true, isSubagent = false, isActive = false,
    )

    @Test
    fun noCandidates() = assertEquals(Selection.None, SessionSelector.select(emptyList()))

    @Test
    fun mostRecentByFreshness() {
        val c = SessionSelector.select(
            listOf(
                s("a", "2026-10-29T10:00:00Z"),
                s("b", "2026-10-29T09:00:00Z", log = "2026-10-29T11:00:00Z"),
                s("c", null),
            )
        )
        assertIs<Selection.Chosen>(c)
        assertEquals("b", c.session.id)
        assertEquals(3, c.candidates)
    }

    @Test
    fun tieBreakIsDeterministic() {
        val c = SessionSelector.select(listOf(s("z", "2026-10-29T10:00:00Z"), s("m", "2026-10-29T10:00:00Z")))
        assertEquals("m", (c as Selection.Chosen).session.id)
    }

    @Test
    fun describeContainsFullIdTitleFreshnessAndHint() {
        val one = SessionSelector.describe(SessionSelector.select(listOf(s("7e6d-full-id", "2026-10-29T13:18:01Z"))) as Selection.Chosen)
        assertEquals("handing over Kiro session 7e6d-full-id \"Title 7e6d-full-id\" (last activity 2026-10-29T13:18:01Z; most recent)", one)
        val two = SessionSelector.describe(SessionSelector.select(listOf(s("a", "2026-10-29T13:18:01Z"), s("b", null))) as Selection.Chosen)
        assertTrue(two.endsWith("most recent, 2 sessions found – use --pick-session to choose)"), two)
    }

    @Test
    fun describeUsesFirstTitleLineAndHandlesMissingTitle() {
        val d = SessionSelector.describe(Selection.Chosen(s("x", null, title = "line1\nline2"), Reason.MOST_RECENT, 1))
        assertTrue(d.contains("\"line1\""))
        assertFalse(d.contains("line2"))
        val n = SessionSelector.describe(Selection.Chosen(s("x", null, title = null), Reason.MOST_RECENT, 1))
        assertTrue(n.startsWith("handing over Kiro session x (last activity"), n)
    }
}
