package de.kmob.platform.posix

import de.kmob.platform.ProcessStartException
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosixPlatformTest {
    private val platform = PosixPlatform()
    private val runner = PosixProcessRunner()
    private val fs = FileSystem.SYSTEM
    private val tmp = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kmob-test-${kotlin.random.Random.nextLong().toULong()}")
        .also { fs.createDirectories(it) }

    @AfterTest
    fun cleanup() = fs.deleteRecursively(tmp)

    @Test
    fun realPathResolvesSymlinks() {
        val target = tmp / "target"
        fs.createDirectories(target)
        val link = tmp / "link"
        fs.createSymlink(link, target)
        val resolved = platform.realPath(link.toString())!!
        assertEquals(platform.realPath(target.toString()), resolved)
        assertFalse(resolved.endsWith("/link"))
    }

    @Test
    fun realPathOfMissingIsNull() {
        assertNull(platform.realPath((tmp / "missing").toString()))
    }

    @Test
    fun isExecutable() {
        val script = tmp / "tool"
        fs.write(script) { writeUtf8("#!/bin/sh\n") }
        assertFalse(platform.isExecutable(script.toString()), "not yet chmod +x")
        runner.runCapture(listOf("chmod", "+x", script.toString()))
        assertTrue(platform.isExecutable(script.toString()))
        val link = tmp / "tool-link"
        fs.createSymlink(link, script)
        assertTrue(platform.isExecutable(link.toString()), "symlink to executable")
        assertFalse(platform.isExecutable(tmp.toString()), "directory is not executable file")
        assertFalse(platform.isExecutable((tmp / "missing").toString()))
    }

    @Test
    fun findInPathUsesPathVariable() {
        assertEquals(null, platform.findInPath("kmob-definitely-not-existing"))
        val sh = platform.findInPath("sh")
        assertTrue(sh != null && sh.endsWith("/sh"), "sh found: $sh")
    }

    @Test
    fun captureExitCodeAndOutput() {
        val r = runner.runCapture(listOf("sh", "-c", "echo out; echo err >&2; exit 3"))
        assertEquals(3, r.exitCode)
        assertEquals("out\n", r.stdout)
        assertEquals("err\n", r.stderr)
    }

    @Test
    fun captureLargeOutputOnBothStreamsWithoutDeadlock() {
        // je ~1 MB auf stdout und stderr gleichzeitig: würde ohne poll in einer vollen Pipe hängen
        val r = runner.runCapture(
            listOf("sh", "-c", "i=0; while [ \$i -lt 20000 ]; do echo 0123456789012345678901234567890123456789012345; echo abcdefghijabcdefghijabcdefghijabcdefghijabcdef >&2; i=\$((i+1)); done")
        )
        assertEquals(0, r.exitCode)
        assertEquals(20000 * 47, r.stdout.length)
        assertEquals(20000 * 47, r.stderr.length)
    }

    @Test
    fun captureStdinIsDevNull() {
        val r = runner.runCapture(listOf("sh", "-c", "cat; echo done"))
        assertEquals("done\n", r.stdout)
    }

    @Test
    fun signalExitCode() {
        assertEquals(128 + 15, runner.runCapture(listOf("sh", "-c", "kill -TERM \$\$")).exitCode)
        assertEquals(128 + 15, runner.runInherit(listOf("sh", "-c", "kill -TERM \$\$")))
    }

    @Test
    fun childGetsDefaultSigint() {
        // Das Kind darf SIGINT nicht geerbt ignorieren (K5-02): es beendet sich selbst per SIGINT.
        assertEquals(128 + 2, runner.runInherit(listOf("sh", "-c", "kill -INT \$\$; sleep 5")))
    }

    @Test
    fun inheritExitCode() {
        assertEquals(7, runner.runInherit(listOf("sh", "-c", "exit 7")))
    }

    @Test
    fun missingProgramThrows() {
        assertFailsWith<ProcessStartException> { runner.runCapture(listOf("kmob-definitely-not-existing")) }
        assertFailsWith<ProcessStartException> { runner.runInherit(listOf("kmob-definitely-not-existing")) }
    }

    @Test
    fun argumentsArePassedVerbatim() {
        val args = listOf("a b", "", "--yes", "ü\$HOME*")
        val r = runner.runCapture(listOf("sh", "-c", "for a in \"\$@\"; do printf '[%s]' \"\$a\"; done", "sh") + args)
        assertEquals("[a b][][--yes][ü\$HOME*]", r.stdout)
    }
}
