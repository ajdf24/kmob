package de.kmob.kiro

import de.kmob.Output
import de.kmob.testing.FakePlatform
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SessionStoreTest {
    private val clock = MutableClock(Instant.parse("2026-10-29T08:00:00Z"))
    private val fs = FakeFileSystem(clock)
    private val platform = FakePlatform(home = "/home/u")
    private val store = SessionStore(fs, platform, Output(platform))
    private val dir = "/home/u/.kiro/sessions/cli".toPath()

    init {
        fs.createDirectories(dir)
    }

    private fun session(
        id: String, cwd: String = "/work/repo", log: String? = "{\"a\":1}\n",
        extra: String = "", updatedAt: String = "2026-10-29T10:00:00Z",
    ) {
        fs.write(dir / "$id.json") {
            writeUtf8("""{"session_id":"$id","cwd":"$cwd","title":"T $id","updated_at":"$updatedAt"$extra}""")
        }
        if (log != null) fs.write(dir / "$id.jsonl") { writeUtf8(log) }
    }

    @Test
    fun isWithinIsSegmentBased() {
        assertTrue(isWithin("/repo", "/repo"))
        assertTrue(isWithin("/repo/sub/x", "/repo"))
        assertTrue(isWithin("/repo/", "/repo"))
        assertTrue(isWithin("/repo", "/repo/"))
        assertFalse(isWithin("/repo-x", "/repo"))
        assertFalse(isWithin("/rep", "/repo"))
        assertTrue(isWithin("/Users/a b/Mobile Documents/com~apple/x", "/Users/a b/Mobile Documents"))
    }

    @Test
    fun candidatesFilterRepoSubagentAndLog() {
        session("ok", cwd = "/work/repo/sub")
        session("other", cwd = "/work/repo-x")
        session("sub", extra = ""","parent_session_id":"ok"""")
        session("nullparent", extra = ""","parent_session_id":null""")
        session("nolog", log = null)
        session("emptylog", log = "")
        session("partial", log = "{\"a\":1}")
        session("blank", log = "\n\n")
        val ids = store.scan().candidatesFor("/work/repo").map { it.id }.sorted()
        assertEquals(listOf("nullparent", "ok"), ids)
    }

    @Test
    fun unreadableFilesAreSkippedAndCounted() {
        session("good")
        fs.write(dir / "broken.json") { writeUtf8("{not json") }
        fs.write(dir / "array.json") { writeUtf8("[1]") }
        fs.write(dir / "nocwd.json") { writeUtf8("""{"title":"x"}""") }
        fs.write(dir / "x.jsonl") { writeUtf8("ignored") }
        val scan = store.scan()
        assertEquals(listOf("good"), scan.sessions.map { it.id })
        assertEquals(3, scan.skipped)
    }

    @Test
    fun cwdIsResolved() {
        platform.realPaths["/tmp/repo"] = "/private/tmp/repo"
        session("s", cwd = "/tmp/repo")
        assertEquals(listOf("s"), store.scan().candidatesFor("/private/tmp/repo").map { it.id })
    }

    @Test
    fun freshnessUsesLogMtimeAndToleratesMissingValues() {
        clock.instant = Instant.parse("2026-10-29T12:00:00Z")
        session("a", updatedAt = "2026-10-29T10:00:00Z")
        session("b", updatedAt = "garbage")
        val byId = store.scan().sessions.associateBy { it.id }
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), byId.getValue("a").freshness)
        assertEquals(null, byId.getValue("b").updatedAt)
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), byId.getValue("b").freshness)
    }

    @Test
    fun activeAndSessionsDirFromEnv() {
        platform.envVars["KMOB_KIRO_SESSIONS_DIR"] = "/alt"
        fs.createDirectories("/alt".toPath())
        fs.write("/alt/s.json".toPath()) { writeUtf8("""{"cwd":"/r"}""") }
        fs.write("/alt/s.jsonl".toPath()) { writeUtf8("{}\n") }
        fs.write("/alt/s.lock".toPath()) { writeUtf8("") }
        val s = store.scan().sessions.single()
        assertTrue(s.isActive)
        assertEquals(null, s.title)
    }

    @Test
    fun missingDirectoryGivesEmptyScan() {
        platform.envVars["KMOB_KIRO_SESSIONS_DIR"] = "/nope"
        assertEquals(SessionScan(emptyList(), 0), store.scan())
    }
}

class MutableClock(var instant: Instant) : kotlin.time.Clock {
    override fun now(): Instant = instant
}
