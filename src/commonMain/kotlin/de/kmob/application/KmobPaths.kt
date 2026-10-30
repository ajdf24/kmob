package de.kmob.application

import okio.Path
import okio.Path.Companion.toPath

/** Einzige Stelle für Pfade und Dateinamen von kmob (design §3.4). Alle `.kmob`-Pfade hängen am Git-Root. */
object KmobPaths {
    const val KMOB_DIR = ".kmob"
    const val ARTIFACT_FILE = "kiro-session.json"
    const val HANDOVER_FILE = "handover.json"

    /** `<git-root>/.kmob/` */
    fun kmobDir(root: String): Path = root.toPath() / KMOB_DIR

    fun artifact(kmobDir: Path): Path = kmobDir / ARTIFACT_FILE

    fun handoverInfo(kmobDir: Path): Path = kmobDir / HANDOVER_FILE

    /** Temporäre Dateien im git-dir des Worktrees – nie im Working Tree (K5-05). */
    fun tmpDir(gitDir: String): Path = gitDir.toPath() / "kmob" / "tmp"

    /** Merker im git-common-dir: gilt für den Clone mit allen Worktrees (K1-08). */
    fun stateDir(commonDir: String): Path = commonDir.toPath() / "kmob"
}
