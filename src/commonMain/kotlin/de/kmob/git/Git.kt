package de.kmob.git

import de.kmob.KmobException
import de.kmob.platform.Captured
import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner

/**
 * Git-Aufrufe (design §5.2). Nur [root] läuft im aktuellen Verzeichnis,
 * alle anderen Aufrufe mit `git -C <root>`, damit kmob auch aus Unterverzeichnissen korrekt arbeitet (K1-01).
 */
class Git(private val platform: Platform, private val runner: ProcessRunner) {

    /** Aufgelöstes Git-Root oder null, wenn kein Repo. */
    fun root(): String? {
        val r = runner.runCapture(listOf("git", "rev-parse", "--show-toplevel"))
        if (!r.ok) return null
        val raw = r.stdout.trim().ifEmpty { return null }
        return platform.realPath(raw) ?: raw
    }

    fun repo(root: String) = Repo(root)

    inner class Repo(val root: String) {
        private fun git(vararg args: String): Captured = runner.runCapture(listOf("git", "-C", root) + args)

        private fun required(vararg args: String): String {
            val r = git(*args)
            if (!r.ok) throw KmobException("git ${args.joinToString(" ")} failed: ${r.stderr.trim()}")
            return r.stdout.trim()
        }

        /** git-dir des aktuellen Worktrees (temporäre Dateien). */
        fun gitDir(): String = required("rev-parse", "--absolute-git-dir")

        /** gemeinsames git-dir aller Worktrees eines Clones (Merker, K1-08). */
        fun commonDir(): String = required("rev-parse", "--path-format=absolute", "--git-common-dir")

        /** Aktueller Branch, leer bei detached HEAD. */
        fun currentBranch(): String = git("branch", "--show-current").let { if (it.ok) it.stdout.trim() else "" }

        fun isIgnored(relPath: String): Boolean = git("check-ignore", "-q", "--", relPath).exitCode == 0

        fun inIndex(relPath: String): Boolean = git("ls-files", "--", relPath).let { it.ok && it.stdout.isNotBlank() }

        fun inHead(relPath: String): Boolean =
            git("ls-tree", "-r", "--name-only", "HEAD", "--", relPath).let { it.ok && it.stdout.isNotBlank() }

        fun removeFromIndex(relPath: String) {
            required("rm", "-r", "-q", "--cached", "--", relPath)
        }

        fun userName(): String = git("config", "user.name").stdout.trim().ifEmpty { "unknown" }
    }
}
