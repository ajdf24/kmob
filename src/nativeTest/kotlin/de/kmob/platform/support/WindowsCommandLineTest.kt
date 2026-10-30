package de.kmob.platform.support

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsCommandLineTest {

    private fun roundtrip(vararg args: String) {
        val line = WindowsCommandLine.quote(args.toList())
        assertEquals(args.toList(), WindowsCommandLine.parse(line), "command line: $line")
    }

    @Test
    fun simpleArgumentsStayUnquoted() {
        assertEquals("mob next --stay", WindowsCommandLine.quote(listOf("mob", "next", "--stay")))
    }

    @Test
    fun spacesEmptyAndQuotes() {
        assertEquals("\"a b\" \"\" \"say \\\"hi\\\"\"", WindowsCommandLine.quote(listOf("a b", "", "say \"hi\"")))
    }

    @Test
    fun backslashesOnlyDoubledBeforeQuoteOrAtEndOfQuotedArgument() {
        assertEquals("C:\\dir\\file", WindowsCommandLine.quoteArgument("C:\\dir\\file"))
        assertEquals("\"C:\\my dir\\\\\"", WindowsCommandLine.quoteArgument("C:\\my dir\\"))
        assertEquals("\"a\\\\\\\"b\"", WindowsCommandLine.quoteArgument("a\\\"b"))
    }

    @Test
    fun roundtrips() {
        roundtrip("C:\\Program Files\\mob\\mob.exe", "next", "--message", "wip: tests \"quoted\"")
        roundtrip("", "a", "", "b")
        roundtrip("trailing\\", "trailing space\\", "\\\\server\\share\\x y")
        roundtrip("ü ö ä – ß", "/chat load C:\\Users\\Jürgen Müller\\repo\\.kmob\\kiro-session.json")
        roundtrip("tab\there", "new\nline", "\"", "\\\"", "a\\\\\"b")
        roundtrip("--yes", "-m", "--yes", "--", "a b")
    }

    @Test
    fun parserFollowsCRuntimeRules() {
        assertEquals(listOf("a\\\\b", "c d", "e\"f"), WindowsCommandLine.parse("a\\\\b \"c d\" e\\\"f"))
        assertEquals(listOf("ab", "c"), WindowsCommandLine.parse("  \"a\"b\t c  "))
        assertEquals(listOf("say \"hi\""), WindowsCommandLine.parse("\"say \"\"hi\"\"\""))
    }
}
