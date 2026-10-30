package de.kmob.cli

import de.kmob.App
import de.kmob.kiro.HandoverInfo
import de.kmob.platform.Captured
import de.kmob.secrets.Fixtures
import de.kmob.testing.FakePlatform
import de.kmob.testing.FakeRunner
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandsTest {
    private val fs = FakeFileSystem(de.kmob.kiro.MutableClock(kotlin.time.Instant.parse("2026-10-29T12:00:00Z")))
    private val platform = FakePlatform(home = "/home/u")
    private val runner = FakeRunner()
    private val app = App(platform, runner, fs)

    private val root = "/work/repo"
    private val sessions = "/home/u/.kiro/sessions/cli".toPath()
    private var branch = "mob/main"
    private var isRepo = true
    private var kmobInIndex = false
    private var mobExit = 0
    private val mobCalls get() = runner.inheritCalls.filter { it[0] == "/usr/bin/mob" }

    init {
        fs.createDirectories("$root/.git".toPath())
        fs.createDirectories(sessions)
        runner.handler = { cmd ->
            when {
                cmd[0] == "/usr/bin/mob" -> Captured(mobExit, "", "")
                cmd == listOf("git", "rev-parse", "--show-toplevel") ->
                    if (isRepo) Captured(0, "$root\n", "") else Captured(128, "", "fatal")
                cmd.take(3) == listOf("git", "-C", root) -> git(cmd.drop(3))
                else -> Captured(127, "", "unexpected $cmd")
            }
        }
    }

    private fun git(args: List<String>): Captured = when {
        args == listOf("branch", "--show-current") -> Captured(0, "$branch\n", "")
        args == listOf("rev-parse", "--absolute-git-dir") -> Captured(0, "$root/.git\n", "")
        args.take(2) == listOf("rev-parse", "--path-format=absolute") -> Captured(0, "$root/.git\n", "")
        args == listOf("config", "user.name") -> Captured(0, "Anna\n", "")
        args.firstOrNull() == "ls-files" -> Captured(0, if (kmobInIndex) ".kmob/kiro-session.json\n" else "", "")
        args.firstOrNull() == "rm" -> { kmobInIndex = false; Captured(0, "", "") }
        else -> Captured(1, "", "unexpected git $args")
    }

    private fun session(id: String, logLines: List<String> = listOf("""{"kind":"Prompt","data":{"t":"hi"}}"""), cwd: String = "$root/sub") {
        fs.write(sessions / "$id.json") { writeUtf8("""{"session_id":"$id","cwd":"$cwd","title":"Refactoring","updated_at":"2026-10-29T13:18:01Z"}""") }
        fs.write(sessions / "$id.jsonl") { writeUtf8(logLines.joinToString("\n", postfix = "\n")) }
    }

    private val artifact = "$root/.kmob/kiro-session.json".toPath()
    private val handover = "$root/.kmob/handover.json".toPath()

    // ---------------------------------------------------------------- next

    @Test
    fun nextWritesArtifactThenCallsMob() {
        session("s1")
        assertEquals(0, app.run(listOf("next", "--yes", "--stay")))
        assertTrue(fs.exists(artifact))
        val info = HandoverInfo.decodeOrNull(fs.read(handover) { readUtf8() })
        assertNotNull(info)
        assertEquals("Anna", info.from)
        assertEquals(1, info.log_entries)
        assertEquals(listOf("/usr/bin/mob", "next", "--stay"), mobCalls.single())
        assertTrue(platform.errText.contains("handing over Kiro session s1 \"Refactoring\""), platform.errText)
        assertFalse(fs.exists("$root/.git/kmob/tmp".toPath()), "tmp removed")
        assertTrue(fs.exists("$root/.git/kmob/notice-accepted".toPath()))
    }

    @Test
    fun nextOnBaseBranchOnlyPassesThrough() {
        session("s1")
        branch = "main"
        assertEquals(0, app.run(listOf("next", "--yes")))
        assertFalse(fs.exists("$root/.kmob".toPath()))
        assertEquals(listOf("/usr/bin/mob", "next"), mobCalls.single())
    }

    @Test
    fun nextOutsideRepoPassesThrough() {
        isRepo = false
        assertEquals(0, app.run(listOf("next")))
        assertEquals(1, mobCalls.size)
    }

    @Test
    fun nextWithoutSessionWarnsAndHandsOverCode() {
        session("elsewhere", cwd = "/work/other")
        fs.write(sessions / "broken.json") { writeUtf8("{") }
        assertEquals(0, app.run(listOf("next")))
        assertTrue(platform.errText.contains("warning: no Kiro session for $root found (1 session files unreadable) – handing over code only."), platform.errText)
        assertFalse(fs.exists(artifact))
        assertEquals(1, mobCalls.size)
    }

    @Test
    fun nextNeedsConfirmationWithoutTty() {
        session("s1")
        assertEquals(1, app.run(listOf("next")))
        assertTrue(platform.errText.contains("needs confirmation"))
        assertFalse(fs.exists(artifact))
        assertTrue(mobCalls.isEmpty())
    }

    @Test
    fun nextAbortsOnSecretWithoutLeakingIt() {
        val key = Fixtures.awsKey
        session("s1", listOf("""{"kind":"Prompt"}""", """{"kind":"ToolResults","data":{"stdout":"$key"}}"""))
        fs.createDirectories("$root/.kmob".toPath())
        fs.write(artifact) { writeUtf8("old") }
        assertEquals(1, app.run(listOf("next", "--yes")))
        assertTrue(platform.errText.contains("possible secret (kmob-aws-access-key-id) in log entry #2"), platform.errText)
        assertFalse(platform.errText.contains(key))
        assertEquals("old", fs.read(artifact) { readUtf8() }, "existing artifact unchanged (R5.4)")
        assertTrue(mobCalls.isEmpty())
    }

    @Test
    fun nextAbortsOnExportError() {
        session("s1", listOf("not json"))
        assertEquals(1, app.run(listOf("next", "--yes")))
        assertTrue(platform.errText.contains("could not export session s1"), platform.errText)
        assertTrue(mobCalls.isEmpty())
    }

    @Test
    fun nextWithoutMobWritesNothing() {
        session("s1")
        platform.pathBinaries.clear()
        assertEquals(1, app.run(listOf("next", "--yes")))
        assertFalse(fs.exists(artifact))
    }

    // ---------------------------------------------------------------- start

    @Test
    fun startShowsInfoAndAbsoluteLoadCommand() {
        session("s1")
        app.run(listOf("next", "--yes"))
        platform.stderr.clear()
        assertEquals(0, app.run(listOf("start")))
        assertEquals(
            listOf(
                "kmob: Kiro session from Anna, handed over 2026-10-29T13:20:00Z: \"Refactoring\" (1 entries, last activity 2026-10-29T13:18:01Z)",
                "kmob: load it in Kiro with:  /chat load $root/.kmob/kiro-session.json",
            ),
            platform.stderr,
        )
    }

    @Test
    fun startWithMismatchingHandoverShowsOnlyLoadCommand() {
        session("s1")
        app.run(listOf("next", "--yes"))
        fs.write(artifact) { writeUtf8("changed by someone") }
        platform.stderr.clear()
        app.run(listOf("start"))
        assertEquals(listOf("kmob: load it in Kiro with:  /chat load $root/.kmob/kiro-session.json"), platform.stderr)
    }

    @Test
    fun startPrintsNothingWithoutArtifactOrOnMobFailure() {
        assertEquals(0, app.run(listOf("start")))
        assertTrue(platform.stderr.isEmpty())
        session("s1")
        app.run(listOf("next", "--yes"))
        platform.stderr.clear()
        mobExit = 3
        assertEquals(3, app.run(listOf("start")))
        assertTrue(platform.stderr.isEmpty())
    }

    // ---------------------------------------------------------------- done

    @Test
    fun doneRemovesKmobBeforeMob() {
        fs.createDirectories("$root/.kmob".toPath())
        fs.write(artifact) { writeUtf8("x") }
        var existedDuringMob = true
        val base = runner.handler
        runner.handler = { cmd ->
            if (cmd[0] == "/usr/bin/mob") existedDuringMob = fs.exists("$root/.kmob".toPath())
            base(cmd)
        }
        assertEquals(0, app.run(listOf("done", "--yes")))
        assertFalse(existedDuringMob)
        assertEquals(listOf("/usr/bin/mob", "done"), mobCalls.single())
    }

    @Test
    fun doneCleansUpKmobInIndex() {
        kmobInIndex = true
        assertEquals(1, app.run(listOf("done")))
        assertTrue(platform.errText.contains(".kmob/ was staged in the result"))
        assertTrue(runner.calls.any { it.containsAll(listOf("rm", "-r", "-q", "--cached", "--", ".kmob")) })
    }

    @Test
    fun doneMobFailureIsPassedThrough() {
        mobExit = 1
        assertEquals(1, app.run(listOf("done")))
        assertTrue(runner.calls.none { "ls-files" in it })
    }

    @Test
    fun doneOnBaseBranchPassesThrough() {
        branch = "main"
        fs.createDirectories("$root/.kmob".toPath())
        app.run(listOf("done"))
        assertTrue(fs.exists("$root/.kmob".toPath()), "nothing touched outside WIP branch")
    }
}
