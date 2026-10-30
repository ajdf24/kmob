package de.kmob.domain.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsWithinTest {
    /** POSIX-Trenner wie in [de.kmob.testing.FakePlatform]. */
    private fun isWithin(path: String, root: String) = isWithin(path, root, '/')

    @Test
    fun isWithinIsSegmentBased() {
        assertTrue(isWithin("/repo", "/repo"))
        assertTrue(isWithin("/repo/sub/x", "/repo"))
        assertTrue(isWithin("/repo/", "/repo"))
        assertTrue(isWithin("/repo", "/repo/"))
        assertFalse(isWithin("/repo-x", "/repo"))
        assertFalse(isWithin("/rep", "/repo"))
        assertTrue(isWithin("/Users/a b/Mobile Documents/com~apple/x", "/Users/a b/Mobile Documents"))
    }
}
