package de.kmob.platform.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutableSearchTest {

    @Test
    fun posixSearchesPathInOrder() {
        val existing = setOf("/opt/bin/mob", "/usr/bin/mob")
        assertEquals("/opt/bin/mob", ExecutableSearch.POSIX.find("mob", "/nope::/opt/bin/:/usr/bin", existing::contains))
        assertNull(ExecutableSearch.POSIX.find("mob", null, existing::contains))
        assertNull(ExecutableSearch.POSIX.find("git", "/opt/bin", existing::contains))
    }

    @Test
    fun posixNameWithDirectoryIsUsedAsIs() {
        assertEquals("./tools/mob", ExecutableSearch.POSIX.find("./tools/mob", "/usr/bin", { it == "./tools/mob" }))
        assertNull(ExecutableSearch.POSIX.find("bin/mob", "/usr", { it == "/usr/bin/mob" }))
    }

    @Test
    fun windowsAppendsExecutableExtensionsAndSplitsOnSemicolon() {
        val existing = setOf("C:\\tools\\mob.exe", "C:\\Windows\\git.com")
        val search = ExecutableSearch.windows(".COM;.EXE;.BAT;.CMD")
        assertEquals("C:\\tools\\mob.exe", search.find("mob", "C:\\nope;C:\\tools\\", existing::contains))
        assertEquals("C:\\Windows\\git.com", search.find("git", "C:\\Windows", existing::contains))
    }

    @Test
    fun windowsNeverUsesBatOrCmd() {
        val search = ExecutableSearch.windows(".BAT;.CMD")
        assertNull(search.find("mob", "C:\\scoop\\shims", { it == "C:\\scoop\\shims\\mob.cmd" }))
        assertEquals("C:\\s\\mob.exe", search.find("mob", "C:\\s", { it == "C:\\s\\mob.exe" }), "Default .COM;.EXE")
    }

    @Test
    fun windowsStripsQuotesAndKeepsGivenExtension() {
        val search = ExecutableSearch.windows(null)
        val existing = setOf("C:\\Program Files\\mob\\mob.exe")
        assertEquals(existing.single(), search.find("mob", "\"C:\\Program Files\\mob\"", existing::contains))
        assertEquals(existing.single(), search.find("mob.EXE".lowercase(), "\"C:\\Program Files\\mob\"", existing::contains))
    }

    @Test
    fun windowsPathWithDriveOrSeparatorIsUsedDirectly() {
        val search = ExecutableSearch.windows(null)
        assertEquals("D:mob.exe", search.find("D:mob", "C:\\x", { it == "D:mob.exe" }))
        assertEquals("C:/tools/mob.exe", search.find("C:/tools/mob.exe", "C:\\x", { it == "C:/tools/mob.exe" }))
        assertNull(search.find(".\\mob", null, { false }))
    }

    @Test
    fun windowsExecutableExtension() {
        assertTrue(ExecutableSearch.hasWindowsExecutableExtension("C:\\a\\MOB.EXE"))
        assertTrue(ExecutableSearch.hasWindowsExecutableExtension("x.com"))
        assertFalse(ExecutableSearch.hasWindowsExecutableExtension("mob.cmd"))
        assertFalse(ExecutableSearch.hasWindowsExecutableExtension("mob"))
    }
}
