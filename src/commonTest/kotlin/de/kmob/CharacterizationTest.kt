package de.kmob

import de.kmob.testing.KmobHarness
import de.kmob.testing.KmobHarness.Companion.MOB
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Charakterisierungstests (R10.4, tasks 2r.1): halten das Verhalten von M2 über die öffentliche Kante
 * `App.run` fest – vollständige Meldungen, Exit-Codes, Prozessaufrufe und Dateien.
 * Beim Refactoring dürfen sich hier nur Aufbau und Imports ändern, nie die Assertions.
 */
class CharacterizationTest {
    private val h = KmobHarness()

    // ------------------------------------------------------------------ next

    @Test
    fun nextFirstHandoverWithYesPrintsSelectionAndNoticeExactly() {
        h.session("s1")
        assertEquals(0, h.run("next", "--yes"))
        assertEquals(
            listOf(
                "kmob: handing over Kiro session s1 \"Refactoring\" (last activity 2026-10-29T13:18:01Z; most recent)",
                "kmob: kmob next pushes your Kiro session to the git remote (branch mob/main):",
                "kmob:   prompts, answers and all tool output (file contents, command output, local paths).",
                "kmob:   Secret scan: built-in patterns only. It is a safety net, not a guarantee.",
            ),
            h.stderr,
        )
    }

    @Test
    fun nextSecondHandoverPrintsOnlySelection() {
        h.session("s1")
        h.run("next", "--yes")
        h.stderr.clear()
        assertEquals(0, h.run("next"))
        assertEquals(
            listOf("kmob: handing over Kiro session s1 \"Refactoring\" (last activity 2026-10-29T13:18:01Z; most recent)"),
            h.stderr,
        )
    }

    @Test
    fun nextWritesHandoverInfoExactly() {
        h.session("s1", logLines = listOf(KmobHarness.PROMPT_LINE, """{"kind":"AssistantMessage","data":{}}"""))
        h.run("next", "--yes")
        val artifactBytes = h.fs.metadata(h.artifact).size
        // IST-Stand M2: "schema" fehlt (Default wird nicht serialisiert), Abweichung von design §5.8 – siehe tasks 2r.5
        assertEquals(
            """
            |{
            |    "kmob_version": "${BuildInfo.VERSION}",
            |    "session_id": "s1",
            |    "title": "Refactoring",
            |    "freshness": "2026-10-29T13:18:01Z",
            |    "log_entries": 2,
            |    "artifact_bytes": $artifactBytes,
            |    "from": "Anna",
            |    "handed_over_at": "2026-10-29T13:20:00Z"
            |}
            |""".trimMargin(),
            h.read(h.handover),
        )
    }

    @Test
    fun nextWritesArtifactExactly() {
        h.session("s1", logLines = listOf(KmobHarness.PROMPT_LINE, """{"kind":"AssistantMessage","data":{}}"""))
        h.run("next", "--yes")
        assertEquals(
            """{"format":"kiro-session-export-v1","metadata":${h.metadataJson("s1")},"log_entries":[${KmobHarness.PROMPT_LINE},{"kind":"AssistantMessage","data":{}}]}""",
            h.read(h.artifact),
        )
    }

    @Test
    fun nextReturnsExitCodeOfMobAfterHandover() {
        h.session("s1")
        h.mobExit = 7
        assertEquals(7, h.run("next", "--yes"))
        assertTrue(h.fs.exists(h.artifact))
    }

    @Test
    fun nextWritesArtifactBeforeMobRuns() {
        h.session("s1")
        var artifactDuringMob = false
        h.onMob = { artifactDuringMob = h.fs.exists(h.artifact) }
        h.run("next", "--yes")
        assertTrue(artifactDuringMob)
    }

    @Test
    fun nextClearsLeftoversInTmpAndRemovesTmpAfterwards() {
        h.session("s1")
        h.fs.createDirectories(h.tmpDir)
        h.fs.write(h.tmpDir / "stale") { writeUtf8("x") }
        h.run("next", "--yes")
        assertFalse(h.fs.exists(h.tmpDir))
    }

    @Test
    fun nextRemovesTmpAlsoAfterExportError() {
        h.session("s1", logLines = listOf("not json"))
        assertEquals(1, h.run("next", "--yes"))
        assertFalse(h.fs.exists(h.tmpDir))
        assertEquals(
            listOf(
                "kmob: handing over Kiro session s1 \"Refactoring\" (last activity 2026-10-29T13:18:01Z; most recent)",
                "kmob: kmob next pushes your Kiro session to the git remote (branch mob/main):",
                "kmob:   prompts, answers and all tool output (file contents, command output, local paths).",
                "kmob:   Secret scan: built-in patterns only. It is a safety net, not a guarantee.",
                "kmob: could not export session s1: log entry #1 is not valid JSON. Use --no-session to hand over without it.",
            ),
            h.stderr,
        )
    }

    @Test
    fun nextSecretAbortPrintsHitsThenAbortMessage() {
        h.session("s1", logLines = listOf("""{"t":"AK""" + """IAABCDEFGHIJKLMNOP"}"""))
        h.run("next", "--yes")
        h.stderr.clear()
        assertEquals(1, h.run("next"))
        assertEquals(
            listOf(
                "kmob: handing over Kiro session s1 \"Refactoring\" (last activity 2026-10-29T13:18:01Z; most recent)",
                "kmob: possible secret (kmob-aws-access-key-id) in log entry #1",
                "kmob: handover aborted. Start a new session (/chat new) or use --no-session; use --allow-secrets only after checking.",
            ),
            h.stderr,
        )
        assertFalse(h.fs.exists(h.tmpDir))
    }

    @Test
    fun nextChoosesMostRecentOfSeveralCandidatesAndHintsPickSession() {
        h.session("old", updatedAt = "2026-10-29T10:00:00Z", title = "Alt")
        h.session("new", updatedAt = "2026-10-29T13:18:01Z", title = "Neu\nzweite Zeile")
        h.session("foreign", cwd = "/work/repo-x", updatedAt = "2026-10-30T00:00:00Z")
        h.session("sub", updatedAt = "2026-10-30T00:00:00Z").also {
            h.fs.write(h.sessions / "sub.json") {
                writeUtf8("""{"cwd":"${h.root}","updated_at":"2026-10-30T00:00:00Z","parent_session_id":"new"}""")
            }
        }
        // mtime aller .jsonl = Uhr des FakeFileSystem (12:00) → updated_at von "new" ist jünger
        h.run("next", "--yes")
        assertEquals(
            "kmob: handing over Kiro session new \"Neu\" (last activity 2026-10-29T13:18:01Z; most recent, 2 sessions found – use --pick-session to choose)",
            h.stderr.first(),
        )
    }

    @Test
    fun nextLongTitleIsCutTo80Characters() {
        val title = "x".repeat(100)
        h.session("s1", title = title)
        h.run("next", "--yes")
        assertTrue(h.stderr.first().contains("\"${"x".repeat(80)}\" (last"), h.stderr.first())
    }

    @Test
    fun nextOnDetachedHeadPassesThrough() {
        h.session("s1")
        h.branch = ""
        assertEquals(0, h.run("next", "--yes"))
        assertFalse(h.fs.exists(h.kmobDir))
        assertEquals(listOf(listOf(MOB, "next")), h.mobCalls)
        assertTrue(h.stderr.isEmpty())
    }

    @Test
    fun nextUsesSessionsDirFromEnvironment() {
        h.platform.envVars["KMOB_KIRO_SESSIONS_DIR"] = "/alt/sessions"
        h.session("alt", dir = "/alt/sessions".toPath())
        h.run("next", "--yes")
        assertTrue(h.stderr.first().startsWith("kmob: handing over Kiro session alt "), h.stderr.first())
    }

    @Test
    fun nextWithEmptySessionsDirFromEnvironmentUsesDefault() {
        h.platform.envVars["KMOB_KIRO_SESSIONS_DIR"] = ""
        h.session("s1")
        h.run("next", "--yes")
        assertTrue(h.stderr.first().startsWith("kmob: handing over Kiro session s1 "), h.stderr.first())
    }

    @Test
    fun nextWithoutSessionPrintsWarningOnlyAndKeepsOldKmob() {
        h.fs.createDirectories(h.kmobDir)
        h.fs.write(h.artifact) { writeUtf8("old") }
        assertEquals(0, h.run("next"))
        assertEquals(listOf("kmob: warning: no Kiro session for /work/repo found – handing over code only."), h.stderr)
        assertEquals("old", h.read(h.artifact), "M2: alte .kmob/ bleibt (Grenze, kommt in 4.4)")
    }

    @Test
    fun nextDebugPrintsDiagnosticsAndPassesDebugToMob() {
        h.session("s1")
        h.run("--debug", "next", "--yes")
        assertEquals(listOf(MOB, "--debug", "next"), h.mobCalls.single())
        assertEquals("kmob: debug: command=NEXT mobArgs=[--debug, next]", h.stderr.first())
        assertTrue(h.stderr.contains("kmob: debug: 1 sessions read, 0 skipped, 1 candidates"), h.stderr.toString())
        assertTrue(h.stderr.any { it.startsWith("kmob: debug: wrote /work/repo/.kmob/kiro-session.json (") }, h.stderr.toString())
    }

    @Test
    fun nextDebugOnBaseBranch() {
        h.branch = "main"
        h.platform.envVars["KMOB_DEBUG"] = "1"
        h.run("next")
        assertEquals(
            listOf(
                "kmob: debug: command=NEXT mobArgs=[next]",
                "kmob: debug: branch 'main' is not a WIP branch – passing through",
            ),
            h.stderr,
        )
    }

    @Test
    fun nextConfirmationOnTty() {
        h.session("s1")
        h.platform.interactive = true
        h.platform.input += "yes"
        assertEquals(0, h.run("next"))
        assertEquals("kmob: Continue and don't ask again for this clone? [y/N]", h.stderr.last())
        assertTrue(h.fs.exists(h.noticeMarker))
    }

    @Test
    fun nextConfirmationDeclinedOnTty() {
        h.session("s1")
        h.platform.interactive = true
        h.platform.input += "n"
        assertEquals(1, h.run("next"))
        assertEquals("kmob: handover not confirmed – nothing was written or pushed.", h.stderr.last())
        assertFalse(h.fs.exists(h.kmobDir))
        assertFalse(h.fs.exists(h.noticeMarker))
        assertTrue(h.mobCalls.isEmpty())
    }

    @Test
    fun nextWithoutTtyNeedsConfirmationMessageExactly() {
        h.session("s1")
        assertEquals(1, h.run("next"))
        assertEquals("kmob: first handover from this clone needs confirmation. Run in a terminal or pass --yes.", h.stderr.last())
    }

    @Test
    fun nextGitCallsUseRootAndOrder() {
        h.session("s1")
        h.run("next", "--yes")
        val gitCalls = h.runner.calls.filter { it[0] == "git" }
        assertEquals(
            listOf(
                listOf("git", "rev-parse", "--show-toplevel"),
                listOf("git", "-C", "/work/repo", "branch", "--show-current"),
                listOf("git", "-C", "/work/repo", "rev-parse", "--absolute-git-dir"),
                listOf("git", "-C", "/work/repo", "rev-parse", "--path-format=absolute", "--git-common-dir"),
                listOf("git", "-C", "/work/repo", "config", "user.name"),
            ),
            gitCalls,
        )
    }

    @Test
    fun nextUnknownUserName() {
        h.session("s1")
        h.userName = ""
        h.run("next", "--yes")
        assertTrue(h.read(h.handover).contains("\"from\": \"unknown\""))
    }

    @Test
    fun nextGitDirFailureIsKmobError() {
        h.session("s1")
        val base = h.runner.handler
        h.runner.handler = { cmd ->
            if (cmd.contains("--absolute-git-dir")) de.kmob.platform.Captured(128, "", "fatal: boom\n") else base(cmd)
        }
        assertEquals(1, h.run("next", "--yes"))
        assertEquals(listOf("kmob: git rev-parse --absolute-git-dir failed: fatal: boom"), h.stderr)
        assertTrue(h.mobCalls.isEmpty())
    }

    // ------------------------------------------------------------------ start

    @Test
    fun startOutsideRepoOnlyRunsMob() {
        h.isRepo = false
        assertEquals(0, h.run("start", "10", "--join"))
        assertEquals(listOf(listOf(MOB, "start", "10", "--join")), h.mobCalls)
        assertTrue(h.stderr.isEmpty())
    }

    @Test
    fun startRunsMobBeforeReadingArtifact() {
        h.session("s1")
        h.run("next", "--yes")
        h.fs.deleteRecursively(h.kmobDir)
        h.stderr.clear()
        // mob start bringt das Artefakt erst mit (pull)
        var created = false
        h.onMob = {
            if (!created) {
                h.fs.createDirectories(h.kmobDir)
                h.fs.write(h.artifact) { writeUtf8("{}") }
                created = true
            }
        }
        h.run("start")
        assertEquals(listOf("kmob: load it in Kiro with:  /chat load /work/repo/.kmob/kiro-session.json"), h.stderr)
    }

    @Test
    fun startWithTitleWithoutTitleAndMultiline() {
        h.session("s1", title = "Erste\nZweite")
        h.run("next", "--yes")
        h.stderr.clear()
        h.run("start")
        assertEquals(
            "kmob: Kiro session from Anna, handed over 2026-10-29T13:20:00Z: \"Erste\" (1 entries, last activity 2026-10-29T13:18:01Z)",
            h.stderr.first(),
        )
    }

    @Test
    fun startWithBrokenHandoverShowsOnlyLoadCommand() {
        h.fs.createDirectories(h.kmobDir)
        h.fs.write(h.artifact) { writeUtf8("{}") }
        h.fs.write(h.handover) { writeUtf8("{broken") }
        h.run("start")
        assertEquals(listOf("kmob: load it in Kiro with:  /chat load /work/repo/.kmob/kiro-session.json"), h.stderr)
    }

    @Test
    fun startDoesNotChangeFiles() {
        h.session("s1")
        h.run("next", "--yes")
        val before = h.read(h.artifact) to h.read(h.handover)
        h.run("start")
        assertEquals(before, h.read(h.artifact) to h.read(h.handover))
    }

    // ------------------------------------------------------------------ done

    @Test
    fun doneOutsideRepoOnlyRunsMob() {
        h.isRepo = false
        assertEquals(0, h.run("done", "--yes", "--no-squash"))
        assertEquals(listOf(listOf(MOB, "done", "--no-squash")), h.mobCalls)
    }

    @Test
    fun doneWithoutMobWritesNothing() {
        h.fs.createDirectories(h.kmobDir)
        h.platform.pathBinaries.clear()
        assertEquals(1, h.run("done"))
        assertTrue(h.fs.exists(h.kmobDir))
        assertEquals(listOf("kmob: 'mob' not found in PATH. Install from https://mob.sh or set KMOB_MOB_BIN."), h.stderr)
    }

    @Test
    fun doneMobFailureLeavesKmobRemoved() {
        h.fs.createDirectories(h.kmobDir)
        h.fs.write(h.artifact) { writeUtf8("x") }
        h.mobExit = 2
        assertEquals(2, h.run("done"))
        assertFalse(h.fs.exists(h.kmobDir), "M2: keine Wiederherstellung (kommt in 6.3)")
        assertTrue(h.stderr.isEmpty())
    }

    @Test
    fun doneIndexFindingRemovesDirectoryAndReportsExactly() {
        h.kmobInIndex = true
        h.onMob = {
            h.fs.createDirectories(h.kmobDir)
            h.fs.write(h.artifact) { writeUtf8("from mob") }
        }
        assertEquals(1, h.run("done"))
        assertFalse(h.fs.exists(h.kmobDir))
        assertEquals(
            listOf("kmob: .kmob/ was staged in the result – removed it from the index. Check with git status before committing."),
            h.stderr,
        )
        assertEquals(
            listOf("git", "-C", "/work/repo", "rm", "-r", "-q", "--cached", "--", ".kmob"),
            h.runner.calls.last { it[0] == "git" },
        )
    }

    @Test
    fun doneDebugReportsRemoval() {
        h.fs.createDirectories(h.kmobDir)
        h.run("done", "--debug")
        assertEquals(
            listOf("kmob: debug: command=DONE mobArgs=[done, --debug]", "kmob: debug: removed /work/repo/.kmob"),
            h.stderr,
        )
        assertEquals(listOf(MOB, "done", "--debug"), h.mobCalls.single())
    }

    // ------------------------------------------------------------------ version, help, passthrough

    @Test
    fun versionOutputExactly() {
        h.mobExit = 3
        assertEquals(3, h.run("--version"))
        assertEquals(listOf("kmob ${BuildInfo.VERSION}"), h.stdout)
        assertEquals(listOf(listOf(MOB, "version")), h.mobCalls)
    }

    @Test
    fun helpTextExactly() {
        assertEquals(0, h.run("help"))
        assertEquals(
            listOf(
                "kmob ${BuildInfo.VERSION} – mob.sh with Kiro session handover",
                "",
                "kmob extends 'next', 'start' and 'done' and passes every other command unchanged to mob.",
                "  kmob next    hand over your Kiro session (.kmob/ in the git root) together with the code",
                "  kmob start   after mob start: show how to load the handed-over session in Kiro",
                "  kmob done    remove .kmob/ before mob done, so no session files end up on the base branch",
                "",
                "kmob options (after the command):",
                "  --yes        next, done: skip confirmations             (KMOB_YES=true)",
                "  --debug      next, start, done: debug output, also passed to mob  (KMOB_DEBUG=true)",
                "",
                "Environment:",
                "  KMOB_MOB_BIN             path to the mob binary (default: mob in PATH)",
                "  KMOB_KIRO_SESSIONS_DIR   Kiro session directory (default: ~/.kiro/sessions/cli)",
                "",
                "mob help follows:",
                "",
            ),
            h.stdout,
        )
        assertEquals(listOf(listOf(MOB, "help")), h.mobCalls)
    }

    @Test
    fun helpWithLeadingDebugAndMobMissing() {
        h.platform.pathBinaries.clear()
        assertEquals(1, h.run("--debug", "--debug"))
        assertEquals("kmob: debug: command=HELP mobArgs=[]", h.stderr.first())
        assertEquals("kmob: 'mob' not found in PATH. Install from https://mob.sh or set KMOB_MOB_BIN.", h.stderr.last())
    }

    @Test
    fun passthroughDoesNotTouchGitOrFiles() {
        h.session("s1")
        assertEquals(0, h.run("status", "--debug"))
        assertEquals(listOf(listOf(MOB, "status", "--debug")), h.runner.calls)
        assertTrue(h.stderr.isEmpty(), "Passthrough: kmob-Debug bleibt aus")
    }

    @Test
    fun extendedCommandWithHelpIsPassthrough() {
        h.session("s1")
        assertEquals(0, h.run("done", "--help"))
        assertEquals(listOf(listOf(MOB, "done", "--help")), h.runner.calls)
    }

    @Test
    fun usageErrorMessageExactly() {
        h.platform.envVars["KMOB_YES"] = "maybe"
        assertEquals(2, h.run("next"))
        assertEquals(listOf("kmob: KMOB_YES must be true, false, 1 or 0 (is 'maybe'). See kmob help."), h.stderr)
    }

    @Test
    fun invalidKmobYesIsIgnoredForStartAndWhenFlagIsGiven() {
        h.platform.envVars["KMOB_YES"] = "maybe"
        assertEquals(0, h.run("start"))
        assertEquals(0, h.run("next", "--yes"))
        assertTrue(h.stderr.none { it.contains("KMOB_YES") }, h.stderr.toString())
    }

    @Test
    fun mobOptionWithoutValueAtTheEndIsKept() {
        h.isRepo = false
        h.run("next", "--yes", "-m")
        assertEquals(listOf(MOB, "next", "-m"), h.mobCalls.single())
    }

    @Test
    fun pathComparisonEdgeCasesOnPosix() {
        // Verhalten von M2 (String-Vergleich mit '/'), festgehalten nach dem Review des Refactorings
        val within = { path: String, root: String -> de.kmob.domain.session.isWithin(path, root, '/') }
        assertFalse(within("/repo\\x", "/repo"), "Backslash ist auf POSIX kein Trenner")
        assertFalse(within("//repo", "/repo"))
        assertFalse(within("/./repo", "/repo"))
        assertFalse(within("/repo/x", "/repo//x"))
        assertTrue(within("", "/"))
        assertTrue(within("/anything", "/"))
        assertTrue(within("/repo/../other", "/repo"), "nur aufgelöste Pfade werden verglichen")
    }

    @Test
    fun pathComparisonWithWindowsSeparator() {
        val within = { path: String, root: String -> de.kmob.domain.session.isWithin(path, root, '\\') }
        assertTrue(within("C:\\work\\repo\\sub", "C:\\work\\repo"))
        assertTrue(within("C:\\work\\repo\\", "C:\\work\\repo"))
        assertFalse(within("C:\\work\\repo-x", "C:\\work\\repo"))
        assertTrue(within("C:\\work", "C:\\"))
        assertTrue(within("\\\\server\\share\\repo\\x", "\\\\server\\share\\repo"))
    }
}
