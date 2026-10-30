package de.kmob.application

import de.kmob.application.port.Console
import de.kmob.application.port.LocalState
import de.kmob.domain.KmobException

/**
 * Bestätigung vor dem ersten Push einer Session je Clone (R5.1, K1-08, design §5.10).
 * `--yes` bestätigt ohne Frage; ohne TTY und ohne `--yes` → Abbruch.
 */
class FirstHandoverNotice(private val console: Console) {

    fun ensureAccepted(state: LocalState, yes: Boolean, wipBranch: String, scannerName: String) {
        if (state.has(LocalState.NOTICE_ACCEPTED)) return
        if (!yes && !console.isInteractive()) {
            throw KmobException("first handover from this clone needs confirmation. Run in a terminal or pass --yes.")
        }
        showNotice(wipBranch, scannerName) // auch mit --yes einmal zeigen (informativ)
        if (!yes) askForConfirmation()
        state.set(LocalState.NOTICE_ACCEPTED)
    }

    private fun showNotice(wipBranch: String, scannerName: String) {
        console.info("kmob next pushes your Kiro session to the git remote (branch $wipBranch):")
        console.info("  prompts, answers and all tool output (file contents, command output, local paths).")
        console.info("  Secret scan: $scannerName. It is a safety net, not a guarantee.")
    }

    private fun askForConfirmation() {
        console.info("Continue and don't ask again for this clone? [y/N]")
        val answer = console.readLine()?.trim()?.lowercase()
        if (answer != "y" && answer != "yes") {
            throw KmobException("handover not confirmed – nothing was written or pushed.")
        }
    }
}
