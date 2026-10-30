package de.kmob.testing

import de.kmob.App
import de.kmob.platform.Captured
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Testaufbau für Charakterisierungstests über die öffentliche Kante `App.run` (R10.4):
 * Fake-Dateisystem, Fake-Plattform und ein Fake-git, das ein Repo unter [root] simuliert.
 */
class KmobHarness {
    val fs = FakeFileSystem(FixedClock(Instant.parse("2026-10-29T12:00:00Z")))
    val platform = FakePlatform(home = "/home/u")
    val runner = FakeRunner()
    val app = App(platform, runner, fs)

    val root = "/work/repo"
    val sessions: Path = "/home/u/.kiro/sessions/cli".toPath()
    val kmobDir: Path = "$root/.kmob".toPath()
    val artifact: Path = kmobDir / "kiro-session.json"
    val handover: Path = kmobDir / "handover.json"
    val tmpDir: Path = "$root/.git/kmob/tmp".toPath()
    val noticeMarker: Path = "$root/.git/kmob/notice-accepted".toPath()

    var branch = "mob/main"
    var isRepo = true
    var kmobInIndex = false
    var mobExit = 0
    var userName = "Anna"

    /** Wird bei jedem mob-Aufruf ausgeführt (z. B. um Dateien zu prüfen oder anzulegen). */
    var onMob: (List<String>) -> Unit = {}

    val mobCalls get() = runner.inheritCalls.filter { it[0] == MOB }
    val stderr get() = platform.stderr
    val stdout get() = platform.stdout

    init {
        fs.createDirectories("$root/.git".toPath())
        fs.createDirectories(sessions)
        runner.handler = { cmd ->
            when {
                cmd[0] == MOB -> { onMob(cmd); Captured(mobExit, "", "") }
                cmd == listOf("git", "rev-parse", "--show-toplevel") ->
                    if (isRepo) Captured(0, "$root\n", "") else Captured(128, "", "fatal")
                cmd.take(3) == listOf("git", "-C", root) -> git(cmd.drop(3))
                else -> Captured(127, "", "unexpected $cmd")
            }
        }
    }

    fun run(vararg args: String): Int = app.run(args.toList())

    private fun git(args: List<String>): Captured = when {
        args == listOf("branch", "--show-current") -> Captured(0, "$branch\n", "")
        args == listOf("rev-parse", "--absolute-git-dir") -> Captured(0, "$root/.git\n", "")
        args.take(2) == listOf("rev-parse", "--path-format=absolute") -> Captured(0, "$root/.git\n", "")
        args == listOf("config", "user.name") -> Captured(0, "$userName\n", "")
        args.firstOrNull() == "ls-files" -> Captured(0, if (kmobInIndex) ".kmob/kiro-session.json\n" else "", "")
        args.firstOrNull() == "rm" -> { kmobInIndex = false; Captured(0, "", "") }
        else -> Captured(1, "", "unexpected git $args")
    }

    fun session(
        id: String,
        logLines: List<String> = listOf(PROMPT_LINE),
        cwd: String = "$root/sub",
        title: String = "Refactoring",
        updatedAt: String = "2026-10-29T13:18:01Z",
        dir: Path = sessions,
    ) {
        fs.createDirectories(dir)
        fs.write(dir / "$id.json") { writeUtf8(metadataJson(id, cwd, title, updatedAt)) }
        fs.write(dir / "$id.jsonl") { writeUtf8(logLines.joinToString("\n", postfix = "\n")) }
    }

    fun metadataJson(id: String, cwd: String = "$root/sub", title: String = "Refactoring", updatedAt: String = "2026-10-29T13:18:01Z") =
        """{"session_id":"$id","cwd":"$cwd","title":"$title","updated_at":"$updatedAt"}"""

    fun read(path: Path): String = fs.read(path) { readUtf8() }

    companion object {
        const val MOB = "/usr/bin/mob"
        const val PROMPT_LINE = """{"kind":"Prompt","data":{"t":"hi"}}"""
    }
}

class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}
