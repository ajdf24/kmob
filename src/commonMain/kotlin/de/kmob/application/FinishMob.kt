package de.kmob.application

import de.kmob.application.port.ArtifactStore
import de.kmob.application.port.Console
import de.kmob.application.port.GitRepositories
import de.kmob.application.port.GitRepository
import de.kmob.application.port.MobCli
import de.kmob.domain.mob.isWipBranch
import okio.Path

/**
 * `kmob done` (design §6.3, Teilmenge M2): `.kmob/` vor `mob done` entfernen und danach sicherstellen,
 * dass nichts davon im Ergebnis gestaged ist (R4.1, R4.2, R4.4).
 * Sicherung, Wiederherstellung und Done-Modus folgen in M6.
 */
class FinishMob(
    private val mob: MobCli,
    private val git: GitRepositories,
    private val artifacts: ArtifactStore,
    private val console: Console,
) {
    fun run(mobArgs: List<String>): Int {
        mob.ensureAvailable()
        val repo = git.root()?.let(git::repo)
        if (repo == null || !isWipBranch(repo.currentBranch())) return mob.run(mobArgs)
        val kmobDir = KmobPaths.kmobDir(repo.root)
        if (artifacts.removeKmobDir(kmobDir)) console.debug { "removed $kmobDir" }
        val exitCode = mob.run(mobArgs)
        if (exitCode != 0) return exitCode
        return if (removedStagedKmob(repo, kmobDir)) 1 else exitCode
    }

    /** `.kmob/` im Index nach `mob done` → aus Index und Working Tree entfernen und melden (R4.4). */
    private fun removedStagedKmob(repo: GitRepository, kmobDir: Path): Boolean {
        if (!repo.inIndex(KmobPaths.KMOB_DIR)) return false
        repo.removeFromIndex(KmobPaths.KMOB_DIR)
        artifacts.removeKmobDir(kmobDir)
        console.error(".kmob/ was staged in the result – removed it from the index. Check with git status before committing.")
        return true
    }
}
