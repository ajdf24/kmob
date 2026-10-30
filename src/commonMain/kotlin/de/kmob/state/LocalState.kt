package de.kmob.state

import de.kmob.KmobException
import de.kmob.Output
import de.kmob.platform.Platform
import okio.FileSystem
import okio.Path

/** Merker unter `<git-common-dir>/kmob/` – gilt für einen Clone inkl. aller Worktrees, nie committet (design §5.10). */
class LocalState(private val fs: FileSystem, commonDir: Path) {
    private val dir = commonDir / "kmob"
    private fun marker(name: String) = dir / name

    fun has(name: String): Boolean = fs.exists(marker(name))

    fun set(name: String) {
        fs.createDirectories(dir)
        fs.write(marker(name)) { writeUtf8("") }
    }

    companion object {
        const val NOTICE_ACCEPTED = "notice-accepted"
    }
}

/**
 * Bestätigung vor dem ersten Push einer Session je Clone (R5.1, K1-08).
 * `--yes` bestätigt ohne Frage; ohne TTY und ohne `--yes` → Abbruch.
 */
class FirstHandoverNotice(private val platform: Platform, private val out: Output) {
    fun ensureAccepted(state: LocalState, yes: Boolean, wipBranch: String, scannerName: String) {
        if (state.has(LocalState.NOTICE_ACCEPTED)) return
        if (!yes && !platform.isInteractive()) {
            throw KmobException("first handover from this clone needs confirmation. Run in a terminal or pass --yes.")
        }
        // Hinweis auch mit --yes einmal zeigen (informativ), gefragt wird nur ohne --yes.
        out.info("kmob next pushes your Kiro session to the git remote (branch $wipBranch):")
        out.info("  prompts, answers and all tool output (file contents, command output, local paths).")
        out.info("  Secret scan: $scannerName. It is a safety net, not a guarantee.")
        if (!yes) {
            out.info("Continue and don't ask again for this clone? [y/N]")
            val answer = platform.readLine()?.trim()?.lowercase()
            if (answer != "y" && answer != "yes") throw KmobException("handover not confirmed – nothing was written or pushed.")
        }
        state.set(LocalState.NOTICE_ACCEPTED)
    }
}
