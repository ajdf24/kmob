package de.kmob.cli

import de.kmob.BuildInfo
import de.kmob.Output
import de.kmob.git.Git
import de.kmob.kiro.ARTIFACT_FILE
import de.kmob.kiro.ArtifactWriter
import de.kmob.kiro.HANDOVER_FILE
import de.kmob.kiro.HandoverInfo
import de.kmob.kiro.KMOB_DIR
import de.kmob.kiro.Selection
import de.kmob.kiro.SessionSelector
import de.kmob.kiro.SessionStore
import de.kmob.kiro.SnapshotWriter
import de.kmob.mob.Mob
import de.kmob.platform.Platform
import de.kmob.secrets.BuiltinScanner
import de.kmob.secrets.ScanOutcome
import de.kmob.secrets.SecretScanner
import de.kmob.secrets.SecretsFoundException
import de.kmob.secrets.describeHits
import de.kmob.state.FirstHandoverNotice
import de.kmob.state.LocalState
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** next / start / done (design §6), Stand M2 (MVP). */
class Commands(
    private val platform: Platform,
    private val fs: FileSystem,
    private val git: Git,
    private val mob: Mob,
    private val out: Output,
    private val scanner: SecretScanner = BuiltinScanner(),
) {
    /** WIP-Präfix; wird in M6 aus `mob config` gelesen (tasks 6.1). */
    private val wipPrefix = "mob/"

    // ------------------------------------------------------------------ next (design §6.1)
    fun next(inv: Invocation): Int {
        mob.binary // früh prüfen: fehlt mob, wird nichts geschrieben
        val repo = git.root()?.let(git::repo) ?: return mob.run(inv.mobArgs)
        val branch = repo.currentBranch()
        if (!branch.startsWith(wipPrefix)) {
            out.debug { "branch '$branch' is not a WIP branch – passing through" }
            return mob.run(inv.mobArgs) // R2.15, K5-03
        }
        val tmpDir = repo.gitDir().toPath() / "kmob" / "tmp"
        fs.deleteRecursively(tmpDir)

        val store = SessionStore(fs, platform, out)
        val scan = store.scan()
        val candidates = scan.candidatesFor(repo.root)
        out.debug { "${scan.sessions.size} sessions read, ${scan.skipped} skipped, ${candidates.size} candidates" }
        val chosen = when (val sel = SessionSelector.select(candidates)) {
            is Selection.None -> {
                val skipped = if (scan.skipped > 0) " (${scan.skipped} session files unreadable)" else ""
                out.warn("no Kiro session for ${repo.root} found$skipped – handing over code only.")
                return mob.run(inv.mobArgs)
            }
            is Selection.Chosen -> sel
        }
        out.info(SessionSelector.describe(chosen))

        FirstHandoverNotice(platform, out)
            .ensureAccepted(LocalState(fs, repo.commonDir().toPath()), inv.yes, branch, scanner.name)

        val session = chosen.session
        try {
            val snapshot = SnapshotWriter.write(fs, session.id, store.metadataFile(session.id), store.logFile(session.id), tmpDir)
            val outcome = scanner.scan(fs, snapshot)
            if (outcome is ScanOutcome.Findings) {
                describeHits(outcome.hits).forEach(out::error)
                throw SecretsFoundException(outcome.hits)
            }
            val from = repo.userName()
            val written = ArtifactWriter(fs).write(snapshot, kmobDir(repo.root), tmpDir) { bytes ->
                HandoverInfo(
                    kmob_version = BuildInfo.VERSION,
                    session_id = session.id,
                    title = session.title,
                    freshness = session.freshness.toString(),
                    log_entries = snapshot.entries,
                    artifact_bytes = bytes,
                    from = from,
                    handed_over_at = platform.now().toString(),
                )
            }
            out.debug { "wrote ${written.artifact} (${written.bytes} bytes)" }
        } finally {
            fs.deleteRecursively(tmpDir)
        }
        return mob.run(inv.mobArgs)
    }

    // ------------------------------------------------------------------ start (design §6.2)
    fun start(inv: Invocation): Int {
        val rc = mob.run(inv.mobArgs)
        if (rc != 0) return rc // R3.3
        val root = git.root() ?: return rc
        val dir = kmobDir(root)
        val artifact = dir / ARTIFACT_FILE
        val size = fs.metadataOrNull(artifact)?.size ?: return rc
        val info = HandoverInfo.decodeOrNull(runCatching { fs.read(dir / HANDOVER_FILE) { readUtf8() } }.getOrNull())
            ?.takeIf { it.artifact_bytes == size }
        if (info != null) {
            val title = info.title?.let { " \"${it.lineSequence().first().take(80)}\"" } ?: ""
            out.info(
                "Kiro session from ${info.from}, handed over ${info.handed_over_at}:$title " +
                    "(${info.log_entries} entries, last activity ${info.freshness})"
            )
        }
        out.info("load it in Kiro with:  /chat load $artifact")
        return rc
    }

    // ------------------------------------------------------------------ done (design §6.3, Teilmenge M2)
    fun done(inv: Invocation): Int {
        mob.binary
        val repo = git.root()?.let(git::repo)
        if (repo == null || !repo.currentBranch().startsWith(wipPrefix)) return mob.run(inv.mobArgs)
        val dir = kmobDir(repo.root)
        if (fs.exists(dir)) {
            fs.deleteRecursively(dir) // R4.1; Sicherung + Wiederherstellung folgen in M6
            out.debug { "removed $dir" }
        }
        val rc = mob.run(inv.mobArgs)
        if (rc != 0) return rc
        if (repo.inIndex(KMOB_DIR)) { // R4.4
            repo.removeFromIndex(KMOB_DIR)
            fs.deleteRecursively(dir)
            out.error(".kmob/ was staged in the result – removed it from the index. Check with git status before committing.")
            return 1
        }
        return rc
    }

    private fun kmobDir(root: String): Path = root.toPath() / KMOB_DIR
}
