package de.kmob.application.port

import de.kmob.domain.handover.HandoverInfo
import de.kmob.domain.handover.Snapshot
import de.kmob.domain.secrets.ScanOutcome
import de.kmob.domain.session.SessionScan
import okio.Path

/*
 * Ports der Anwendung (design §3.1): Die Use Cases hängen nur von diesen Schnittstellen ab,
 * die Adapter setzen sie mit mob, git, Dateisystem und Konsole um.
 */

/** Das mob-Binary (design §5.1). */
interface MobCli {
    /** Prüft, dass mob gefunden wird; sonst [de.kmob.domain.KmobException]. */
    fun ensureAvailable()

    /** Führt mob mit geerbten Streams aus und liefert dessen Exit-Code. */
    fun run(args: List<String>): Int
}

/** Einstieg in Git (design §5.2). */
interface GitRepositories {
    /** Aufgelöstes Git-Root des aktuellen Verzeichnisses oder null, wenn kein Repo. */
    fun root(): String?

    fun repo(root: String): GitRepository
}

interface GitRepository {
    val root: String

    /** git-dir des aktuellen Worktrees (temporäre Dateien). */
    fun gitDir(): String

    /** Gemeinsames git-dir aller Worktrees eines Clones (Merker, K1-08). */
    fun commonDir(): String

    /** Aktueller Branch, leer bei detached HEAD. */
    fun currentBranch(): String

    fun isIgnored(relPath: String): Boolean
    fun inIndex(relPath: String): Boolean
    fun inHead(relPath: String): Boolean
    fun removeFromIndex(relPath: String)

    /** `user.name`, sonst `unknown`. */
    fun userName(): String
}

/** Lokale Kiro-Sessions (design §5.3, §5.5). */
interface SessionRepository {
    fun scan(): SessionScan

    /** Konsistente Kopie der Session nach [tmpDir]. */
    fun snapshot(sessionId: String, tmpDir: Path): Snapshot
}

/** Secret-Scan über einen Snapshot (design §5.6). */
interface SecretScanner {
    /** z. B. "built-in patterns only" oder "gitleaks 8.30.1" (für den Bestätigungstext). */
    val name: String

    fun scan(snapshot: Snapshot): ScanOutcome
}

/** Das Artefakt-Verzeichnis `.kmob/` und das Temp-Verzeichnis im git-dir (design §5.7, §5.8). */
interface ArtifactStore {
    fun clearTemp(tmpDir: Path)

    /** Schreibt Artefakt und Handover-Info atomar; [info] bekommt die Größe des Artefakts. */
    fun write(snapshot: Snapshot, kmobDir: Path, tmpDir: Path, info: (artifactBytes: Long) -> HandoverInfo): WrittenArtifact

    /** Größe von `.kmob/kiro-session.json`, null wenn nicht vorhanden. */
    fun artifactSize(kmobDir: Path): Long?

    /** Tolerant gelesene Handover-Info, null wenn fehlend oder unlesbar (R3.2). */
    fun readHandoverInfo(kmobDir: Path): HandoverInfo?

    /** Entfernt `.kmob/`; true, wenn es vorhanden war. */
    fun removeKmobDir(kmobDir: Path): Boolean
}

data class WrittenArtifact(val artifact: Path, val bytes: Long)

/** Merker eines Clones unter `<git-common-dir>/kmob/` (design §5.10). */
interface LocalState {
    fun has(name: String): Boolean
    fun set(name: String)

    companion object {
        const val NOTICE_ACCEPTED = "notice-accepted"
    }
}

/** Meldungen (Präfix `kmob:`, stderr) und Rückfragen an die Person am Terminal. */
interface Console {
    var debugEnabled: Boolean

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
    fun debug(message: () -> String)

    /** Ausgabe auf stdout ohne Präfix (version, help). */
    fun stdout(line: String)

    fun isInteractive(): Boolean
    fun readLine(): String?
}
