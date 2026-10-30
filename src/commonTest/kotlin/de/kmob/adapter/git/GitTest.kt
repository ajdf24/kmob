package de.kmob.adapter.git

import de.kmob.platform.Captured
import de.kmob.testing.FakePlatform
import de.kmob.testing.FakeRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitTest {
    private val platform = FakePlatform()
    private val runner = FakeRunner()
    private val git = GitCli(platform, runner)

    @Test
    fun rootIsResolvedAndRunsInCwd() {
        runner.handler = { Captured(0, "/tmp/repo\n", "") }
        platform.realPaths["/tmp/repo"] = "/private/tmp/repo"
        assertEquals("/private/tmp/repo", git.root())
        assertEquals(listOf("git", "rev-parse", "--show-toplevel"), runner.calls.single())
    }

    @Test
    fun noRepo() {
        runner.handler = { Captured(128, "", "fatal: not a git repository") }
        assertNull(git.root())
    }

    @Test
    fun allRepoCallsUseDashC() {
        runner.handler = { cmd ->
            when {
                "ls-files" in cmd -> Captured(0, ".kmob/kiro-session.json\n", "")
                "ls-tree" in cmd -> Captured(0, "", "")
                "check-ignore" in cmd -> Captured(1, "", "")
                "user.name" in cmd -> Captured(0, "\n", "")
                else -> Captured(0, "mob/main\n", "")
            }
        }
        val repo = git.repo("/r")
        assertTrue(repo.inIndex(".kmob"))
        assertFalse(repo.inHead(".kmob"))
        assertFalse(repo.isIgnored(".kmob/kiro-session.json"))
        assertEquals("unknown", repo.userName())
        assertEquals("mob/main", repo.currentBranch())
        assertTrue(runner.calls.all { it.take(3) == listOf("git", "-C", "/r") }, runner.calls.toString())
        assertTrue(runner.calls.any { it.containsAll(listOf("ls-tree", "-r", "--name-only", "HEAD", "--", ".kmob")) })
    }

    @Test
    fun commonDirUsesAbsolutePathFormat() {
        runner.handler = { Captured(0, "/r/.git\n", "") }
        assertEquals("/r/.git", git.repo("/r").commonDir())
        assertEquals(listOf("git", "-C", "/r", "rev-parse", "--path-format=absolute", "--git-common-dir"), runner.calls.single())
    }
}
