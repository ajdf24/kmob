package de.kmob.application

import de.kmob.adapter.console.PlatformConsole
import de.kmob.adapter.state.FileLocalState
import de.kmob.application.port.LocalState
import de.kmob.domain.KmobException
import de.kmob.testing.FakePlatform
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstHandoverNoticeTest {
    private val fs = FakeFileSystem()
    private val platform = FakePlatform()
    private val state = FileLocalState(fs, "/r/.git/kmob".toPath())
    private val notice = FirstHandoverNotice(PlatformConsole(platform))

    private fun ensure(yes: Boolean = false) = notice.ensureAccepted(state, yes, "mob/main", "built-in patterns only")

    @Test
    fun noTtyWithoutYesAborts() {
        val e = assertFailsWith<KmobException> { ensure() }
        assertTrue(e.message!!.contains("--yes"))
        assertFalse(state.has(LocalState.NOTICE_ACCEPTED))
    }

    @Test
    fun yesAcceptsAndShowsNotice() {
        ensure(yes = true)
        assertTrue(state.has(LocalState.NOTICE_ACCEPTED))
        assertTrue(platform.errText.contains("pushes your Kiro session"))
        assertFalse(platform.errText.contains("[y/N]"))
    }

    @Test
    fun ttyYes() {
        platform.interactive = true
        platform.input += "y"
        ensure()
        assertTrue(state.has(LocalState.NOTICE_ACCEPTED))
        assertTrue(platform.errText.contains("built-in patterns only"))
    }

    @Test
    fun ttyNoOrEofAborts() {
        platform.interactive = true
        platform.input += ""
        assertFailsWith<KmobException> { ensure() }
        assertFailsWith<KmobException> { ensure() } // EOF
        assertFalse(state.has(LocalState.NOTICE_ACCEPTED))
    }

    @Test
    fun askedOnlyOnce() {
        ensure(yes = true)
        platform.stderr.clear()
        ensure()
        assertTrue(platform.stderr.isEmpty())
    }
}
