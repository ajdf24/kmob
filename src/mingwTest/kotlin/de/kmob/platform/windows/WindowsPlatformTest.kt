package de.kmob.platform.windows

import de.kmob.platform.ProcessStartException
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Läuft nur unter Windows (CI-Job windows-latest, tasks 2w.5). Kindprozesse über cmd.exe und powershell.exe. */
class WindowsPlatformTest {
    private val platform = WindowsPlatform()
    private val runner = WindowsProcessRunner(platform)
    private val fs = FileSystem.SYSTEM
    private val tmp = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kmob-test-${kotlin.random.Random.nextLong().toULong()}")
        .also { fs.createDirectories(it) }

    @AfterTest
    fun cleanup() = fs.deleteRecursively(tmp)

    @Test
    fun envAndHome() {
        assertNotNull(platform.env("PATH"))
        assertNull(platform.env("KMOB_DEFINITELY_NOT_SET"))
        assertEquals(platform.env("USERPROFILE"), platform.homeDir())
    }

    @Test
    fun realPathIsCanonicalWithoutExtendedPrefix() {
        val dir = tmp / "Mixed Case"
        fs.createDirectories(dir)
        val resolved = platform.realPath(dir.toString().lowercase())!!
        assertFalse(resolved.startsWith("\\\\?\\"), resolved)
        assertTrue(resolved.endsWith("Mixed Case"), "canonical case: $resolved")
        assertNull(platform.realPath((tmp / "missing").toString()))
    }

    @Test
    fun realPathResolvesJunction() {
        val target = tmp / "target"
        fs.createDirectories(target)
        val link = tmp / "junction"
        assertEquals(0, runner.runCapture(listOf("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())).exitCode)
        assertEquals(platform.realPath(target.toString()), platform.realPath(link.toString()))
    }

    @Test
    fun findInPathUsesPathExtAndNotCurrentDirectory() {
        val cmd = platform.findInPath("cmd")
        assertTrue(cmd != null && cmd.endsWith("cmd.exe", ignoreCase = true), "cmd found: $cmd")
        assertNull(platform.findInPath("kmob-definitely-not-existing"))
        assertFalse(platform.isExecutable(tmp.toString()), "directory")
    }

    @Test
    fun quotingRoundtripsThroughCommandLineToArgvW() {
        // Der echte Windows-Parser (wie die C-Runtime der Kindprozesse) zerlegt unsere Kommandozeile wieder in die Argumente.
        val args = listOf(
            "C:\\Program Files\\mob\\mob.exe", "a b", "", "--yes", "say \"hi\"", "trailing\\",
            "trailing space\\", "a\\\\\"b", "tab\there", "ü ö – ß", "/chat load C:\\Users\\Jürgen Müller\\.kmob\\kiro-session.json",
        )
        assertEquals(args, WindowsPlatform.splitCommandLine(de.kmob.platform.support.WindowsCommandLine.quote(args)))
    }

    @Test
    fun programArgumentsComeFromTheUnicodeCommandLine() {
        assertTrue(WindowsPlatform.programArguments().none { it.contains('\uFFFD') })
    }

    @Test
    fun argumentsArriveAtChildProcess() {
        // cmd.exe nutzt eigene Regeln; geprüft wird nur, dass ein einfaches Argument mit Leerzeichen als ein Stück ankommt.
        val r = runner.runCapture(listOf("cmd.exe", "/c", "echo", "a b"))
        assertEquals("\"a b\"\r\n", r.stdout)
    }

    @Test
    fun captureExitCodeAndBothStreams() {
        val r = runner.runCapture(listOf("cmd.exe", "/c", "echo out& echo err 1>&2& exit /b 3"))
        assertEquals(3, r.exitCode)
        assertEquals("out\r\n", r.stdout)
        assertEquals("err \r\n", r.stderr)
    }

    @Test
    fun captureLargeOutputOnBothStreamsWithoutDeadlock() {
        val r = runner.runCapture(
            listOf("powershell.exe", "-NoProfile", "-Command", "1..20000 | % { [Console]::Out.WriteLine('0123456789'); [Console]::Error.WriteLine('abcdefghij') }")
        )
        assertEquals(0, r.exitCode)
        assertEquals(20000 * 12, r.stdout.length)
        assertEquals(20000 * 12, r.stderr.length)
    }

    @Test
    fun captureStdinIsEmpty() {
        val r = runner.runCapture(listOf("cmd.exe", "/c", "more & echo done"))
        assertEquals("done\r\n", r.stdout)
    }

    @Test
    fun inheritExitCode() {
        assertEquals(7, runner.runInherit(listOf("cmd.exe", "/c", "exit /b 7")))
    }

    @Test
    fun missingProgramThrows() {
        assertFailsWith<ProcessStartException> { runner.runCapture(listOf("kmob-definitely-not-existing")) }
        assertFailsWith<ProcessStartException> { runner.runInherit(listOf("kmob-definitely-not-existing")) }
    }

    @Test
    fun extendedPrefixIsRemoved() {
        assertEquals("C:\\x", WindowsPlatform.withoutExtendedPrefix("\\\\?\\C:\\x"))
        assertEquals("\\\\server\\share\\x", WindowsPlatform.withoutExtendedPrefix("\\\\?\\UNC\\server\\share\\x"))
        assertEquals("C:\\x", WindowsPlatform.withoutExtendedPrefix("C:\\x"))
    }
}
