package de.kmob.application

import de.kmob.application.port.ArtifactStore
import de.kmob.application.port.Console
import de.kmob.application.port.GitRepositories
import de.kmob.application.port.GitRepository
import de.kmob.application.port.LocalState
import de.kmob.application.port.MobCli
import de.kmob.application.port.SecretScanner
import de.kmob.application.port.SessionRepository
import de.kmob.domain.handover.HandoverInfo
import de.kmob.domain.handover.Snapshot
import de.kmob.domain.mob.isWipBranch
import de.kmob.domain.secrets.ScanOutcome
import de.kmob.domain.secrets.SecretsFoundException
import de.kmob.domain.secrets.describeHits
import de.kmob.domain.session.SessionInfo
import de.kmob.domain.session.SessionSelector
import de.kmob.domain.session.Selection
import okio.Path
import kotlin.time.Clock

/**
 * `kmob next` (design §6.1, Stand M2): Session wählen, bestätigen lassen, kopieren, scannen,
 * nach `.kmob/` schreiben – dann `mob next`. Ohne Repo, WIP-Branch oder Session nur `mob next`.
 */
class HandOverSession(
    private val mob: MobCli,
    private val git: GitRepositories,
    private val sessions: SessionRepository,
    private val artifacts: ArtifactStore,
    private val scanner: SecretScanner,
    private val localStateAt: (stateDir: Path) -> LocalState,
    private val notice: FirstHandoverNotice,
    private val console: Console,
    private val clock: Clock,
    private val kmobVersion: String,
    /** Verzeichnistrenner der Plattform für den Pfadvergleich (R2.4). */
    private val pathSeparator: Char,
) {
    fun run(mobArgs: List<String>, yes: Boolean): Int {
        mob.ensureAvailable() // fehlt mob, wird nichts geschrieben
        val repo = git.root()?.let(git::repo) ?: return mob.run(mobArgs)
        val wipBranch = repo.currentBranch()
        if (!isWipBranch(wipBranch)) {
            console.debug { "branch '$wipBranch' is not a WIP branch – passing through" }
            return mob.run(mobArgs) // R2.15, K5-03
        }
        val tmpDir = KmobPaths.tmpDir(repo.gitDir())
        artifacts.clearTemp(tmpDir)
        val session = chooseSession(repo.root) ?: return mob.run(mobArgs)
        notice.ensureAccepted(localStateAt(KmobPaths.stateDir(repo.commonDir())), yes, wipBranch, scanner.name)
        try {
            writeArtifact(repo, session, tmpDir)
        } finally {
            artifacts.clearTemp(tmpDir)
        }
        return mob.run(mobArgs)
    }

    /** Kandidaten suchen und auswählen (R2.4–R2.7). null = keine Session, nur den Code übergeben (R2.10). */
    private fun chooseSession(root: String): SessionInfo? {
        val scan = sessions.scan()
        val candidates = scan.candidatesFor(root, pathSeparator)
        console.debug { "${scan.sessions.size} sessions read, ${scan.skipped} skipped, ${candidates.size} candidates" }
        return when (val selection = SessionSelector.select(candidates)) {
            is Selection.None -> {
                console.warn(noSessionWarning(root, scan.skipped))
                null
            }
            is Selection.Chosen -> {
                console.info(SessionSelector.describe(selection))
                selection.session
            }
        }
    }

    private fun noSessionWarning(root: String, skipped: Int): String {
        val unreadable = if (skipped > 0) " ($skipped session files unreadable)" else ""
        return "no Kiro session for $root found$unreadable – handing over code only."
    }

    private fun writeArtifact(repo: GitRepository, session: SessionInfo, tmpDir: Path) {
        val snapshot = sessions.snapshot(session.id, tmpDir)
        ensureNoSecrets(snapshot)
        val from = repo.userName()
        val written = artifacts.write(snapshot, KmobPaths.kmobDir(repo.root), tmpDir) { artifactBytes ->
            HandoverInfo(
                kmobVersion = kmobVersion,
                sessionId = session.id,
                title = session.title,
                freshness = session.freshness.toString(),
                logEntries = snapshot.entries,
                artifactBytes = artifactBytes,
                from = from,
                handedOverAt = clock.now().toString(),
            )
        }
        console.debug { "wrote ${written.artifact} (${written.bytes} bytes)" }
    }

    /** Treffer melden (ohne Wert) und abbrechen, `.kmob/` bleibt unverändert (R5.2, R5.4). */
    private fun ensureNoSecrets(snapshot: Snapshot) {
        val outcome = scanner.scan(snapshot)
        if (outcome is ScanOutcome.Findings) {
            describeHits(outcome.hits).forEach(console::error)
            throw SecretsFoundException(outcome.hits)
        }
    }
}
