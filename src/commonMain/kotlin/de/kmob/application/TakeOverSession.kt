package de.kmob.application

import de.kmob.application.port.ArtifactStore
import de.kmob.application.port.Console
import de.kmob.application.port.GitRepositories
import de.kmob.application.port.MobCli
import de.kmob.domain.handover.HandoverInfo
import de.kmob.domain.session.quotedTitle
import okio.Path

/**
 * `kmob start` (design §6.2, Stand M2): nach erfolgreichem `mob start` zeigen, von wem die Session kommt
 * und wie man sie lädt. Dateien werden nicht verändert (R3.4).
 */
class TakeOverSession(
    private val mob: MobCli,
    private val git: GitRepositories,
    private val artifacts: ArtifactStore,
    private val console: Console,
) {
    fun run(mobArgs: List<String>): Int {
        val exitCode = mob.run(mobArgs)
        if (exitCode != 0) return exitCode // R3.3
        val root = git.root() ?: return exitCode
        val kmobDir = KmobPaths.kmobDir(root)
        val artifactSize = artifacts.artifactSize(kmobDir) ?: return exitCode
        matchingHandoverInfo(kmobDir, artifactSize)?.let { console.info(describe(it)) }
        console.info("load it in Kiro with:  /chat load ${KmobPaths.artifact(kmobDir)}")
        return exitCode
    }

    /** Nur eine Handover-Info, die zum vorliegenden Artefakt gehört (R3.2). */
    private fun matchingHandoverInfo(kmobDir: Path, artifactSize: Long): HandoverInfo? =
        artifacts.readHandoverInfo(kmobDir)?.takeIf { it.artifactBytes == artifactSize }

    private fun describe(info: HandoverInfo): String =
        "Kiro session from ${info.from}, handed over ${info.handedOverAt}:${quotedTitle(info.title)} " +
            "(${info.logEntries} entries, last activity ${info.freshness})"
}
