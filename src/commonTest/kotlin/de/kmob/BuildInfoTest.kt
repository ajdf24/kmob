package de.kmob

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun versionIsSet() {
        assertTrue(BuildInfo.VERSION.matches(Regex("""\d+\.\d+\.\d+.*""")), BuildInfo.VERSION)
    }
}
