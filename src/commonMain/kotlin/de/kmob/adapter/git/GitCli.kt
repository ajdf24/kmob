package de.kmob.adapter.git

import de.kmob.application.port.GitRepositories
import de.kmob.application.port.GitRepository
import de.kmob.domain.KmobException
import de.kmob.platform.Captured
import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner

/**
 * Git über die Kommandozeile (design §5.2). Nur [root] läuft im aktuellen Verzeichnis,
 * alle anderen Aufrufe mit `git -C <root>`, damit kmob auch aus Unterverzeichnissen korrekt arbeitet (K1-01).
 */
class GitCli(private val platform: Platform, private val runner: ProcessRunner) : GitRepositories {

    override fun root(): String? {
        val result = runner.runCapture(listOf("git", "rev-parse", "--show-toplevel"))
        if (!result.ok) return null
        val raw = result.stdout.trim().ifEmpty { return null }
        return platform.realPath(raw) ?: raw
    }

    override fun repo(root: String): GitRepository = Repository(root)

    private inner class Repository(override val root: String) : GitRepository {

        override fun gitDir(): String = required("rev-parse", "--absolute-git-dir")

        override fun commonDir(): String = required("rev-parse", "--path-format=absolute", "--git-common-dir")

        override fun currentBranch(): String = git("branch", "--show-current").let { if (it.ok) it.stdout.trim() else "" }

        override fun isIgnored(relPath: String): Boolean = git("check-ignore", "-q", "--", relPath).exitCode == 0

        override fun inIndex(relPath: String): Boolean = git("ls-files", "--", relPath).hasOutput()

        override fun inHead(relPath: String): Boolean =
            git("ls-tree", "-r", "--name-only", "HEAD", "--", relPath).hasOutput()

        override fun removeFromIndex(relPath: String) {
            required("rm", "-r", "-q", "--cached", "--", relPath)
        }

        override fun userName(): String = git("config", "user.name").stdout.trim().ifEmpty { "unknown" }

        private fun git(vararg args: String): Captured = runner.runCapture(listOf("git", "-C", root) + args)

        /** Aufruf, der gelingen muss; sonst Abbruch mit der Meldung von git. */
        private fun required(vararg args: String): String {
            val result = git(*args)
            if (!result.ok) throw KmobException("git ${args.joinToString(" ")} failed: ${result.stderr.trim()}")
            return result.stdout.trim()
        }

        private fun Captured.hasOutput(): Boolean = ok && stdout.isNotBlank()
    }
}
