package de.kmob

import de.kmob.platform.Captured
import de.kmob.testing.FakePlatform
import de.kmob.testing.FakeRunner
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppPassthroughTest {
    private val platform = FakePlatform()
    private val runner = FakeRunner()
    private val app = App(platform, runner, FakeFileSystem())

    @Test
    fun passthroughCallsMobWithIdenticalArgsAndExitCode() {
        runner.handler = { Captured(5, "", "") }
        assertEquals(5, app.run(listOf("status", "--yes")))
        assertEquals(listOf(listOf("/usr/bin/mob", "status", "--yes")), runner.inheritCalls)
    }

    @Test
    fun mobNotFound() {
        platform.pathBinaries.clear()
        assertEquals(1, app.run(listOf("status")))
        assertTrue(platform.errText.contains("'mob' not found in PATH"), platform.errText)
    }

    @Test
    fun invalidMobBin() {
        platform.envVars["KMOB_MOB_BIN"] = "/nope/mob"
        assertEquals(1, app.run(listOf("status")))
        assertTrue(platform.errText.contains("KMOB_MOB_BIN=/nope/mob is not an executable file"), platform.errText)
    }

    @Test
    fun mobBinFromEnv() {
        platform.envVars["KMOB_MOB_BIN"] = "/opt/mob"
        platform.executables += "/opt/mob"
        app.run(listOf("status"))
        assertEquals("/opt/mob", runner.inheritCalls.single()[0])
    }

    @Test
    fun versionPrintsKmobThenMob() {
        assertEquals(0, app.run(listOf("version")))
        assertEquals("kmob ${BuildInfo.VERSION}", platform.stdout.first())
        assertEquals(listOf("/usr/bin/mob", "version"), runner.inheritCalls.single())
    }

    @Test
    fun helpPrintsKmobSectionThenMobHelp() {
        assertEquals(0, app.run(emptyList()))
        assertTrue(platform.stdout.any { it.contains("--yes") })
        assertEquals(listOf("/usr/bin/mob", "help"), runner.inheritCalls.single())
    }

    @Test
    fun usageErrorExitCode2() {
        platform.envVars["KMOB_YES"] = "maybe"
        assertEquals(2, app.run(listOf("done")))
        assertTrue(platform.errText.contains("See kmob help."))
        assertTrue(runner.calls.isEmpty())
    }
}
