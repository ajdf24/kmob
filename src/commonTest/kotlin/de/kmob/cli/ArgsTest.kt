package de.kmob.cli

import de.kmob.UsageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArgsTest {
    private fun parse(vararg a: String, env: Map<String, String> = emptyMap()) = Args.parse(a.toList()) { env[it] }

    @Test
    fun aliases() {
        assertEquals(Command.NEXT, parse("n").command)
        assertEquals(Command.START, parse("s", "10").command)
        assertEquals(Command.DONE, parse("d").command)
        assertEquals(Command.NEXT, parse("next").command)
    }

    @Test
    fun noArgsIsHelp() {
        assertEquals(Command.HELP, parse().command)
        assertEquals(Command.HELP, parse("--help").command)
        assertEquals(Command.HELP, parse("-h").command)
        assertEquals(Command.VERSION, parse("version").command)
    }

    @Test
    fun passthroughKeepsArgumentsIdentical() {
        val args = arrayOf("status", "--yes", "--debug", "--", "x")
        val inv = parse(*args, env = mapOf("KMOB_YES" to "garbage"))
        assertEquals(Command.PASSTHROUGH, inv.command)
        assertEquals(args.toList(), inv.mobArgs)
    }

    @Test
    fun passthroughIgnoresInvalidEnv() {
        // K2-05: KMOB_* werden bei Passthrough nicht validiert
        parse("status", env = mapOf("KMOB_DEBUG" to "garbage"))
    }

    @Test
    fun leadingDebugDoesNotBecomeCommand() {
        val inv = parse("--debug", "next")
        assertEquals(Command.NEXT, inv.command)
        assertTrue(inv.debug)
        assertEquals(listOf("--debug", "next"), inv.mobArgs, "--debug stays for mob (R7.3)")
    }

    @Test
    fun yesIsRemovedForNextAndDone() {
        val inv = parse("next", "--yes", "--stay")
        assertTrue(inv.yes)
        assertEquals(listOf("next", "--stay"), inv.mobArgs)
        assertTrue(parse("done", "--yes").yes)
    }

    @Test
    fun yesIsNotInterpretedForStart() {
        val inv = parse("start", "--yes")
        assertFalse(inv.yes)
        assertEquals(listOf("start", "--yes"), inv.mobArgs)
    }

    @Test
    fun valuesOfMobOptionsAreNotInterpreted() {
        val inv = parse("next", "-m", "--yes", "--message", "--yes")
        assertFalse(inv.yes)
        assertEquals(listOf("next", "-m", "--yes", "--message", "--yes"), inv.mobArgs)
    }

    @Test
    fun argumentsAfterTerminatorAreNotInterpreted() {
        val inv = parse("next", "--", "--yes")
        assertFalse(inv.yes)
        assertEquals(listOf("next", "--", "--yes"), inv.mobArgs)
    }

    @Test
    fun helpAfterExtendedCommandIsPassthrough() {
        val inv = parse("next", "--yes", "--help")
        assertEquals(Command.PASSTHROUGH, inv.command)
        assertEquals(listOf("next", "--yes", "--help"), inv.mobArgs)
    }

    @Test
    fun orderOfMobArgumentsIsKept() {
        assertEquals(listOf("start", "10", "-b", "feat", "--join"), parse("start", "10", "-b", "feat", "--join").mobArgs)
    }

    @Test
    fun envBooleans() {
        assertTrue(parse("next", env = mapOf("KMOB_YES" to "TRUE")).yes)
        assertTrue(parse("done", env = mapOf("KMOB_YES" to "1")).yes)
        assertFalse(parse("next", env = mapOf("KMOB_YES" to "0")).yes)
        assertTrue(parse("start", env = mapOf("KMOB_DEBUG" to "true")).debug)
        assertFailsWith<UsageException> { parse("next", env = mapOf("KMOB_YES" to "yes")) }
    }
}
