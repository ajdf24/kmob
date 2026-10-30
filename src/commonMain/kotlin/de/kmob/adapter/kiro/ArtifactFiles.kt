package de.kmob.adapter.kiro

import de.kmob.application.KmobPaths
import de.kmob.application.port.ArtifactStore
import de.kmob.application.port.WrittenArtifact
import de.kmob.domain.handover.HandoverInfo
import de.kmob.domain.handover.Snapshot
import okio.FileSystem
import okio.Path

/** `.kmob/` und das Temp-Verzeichnis auf dem Dateisystem. */
class ArtifactFiles(private val fs: FileSystem) : ArtifactStore {
    private val writer = ArtifactWriter(fs)

    override fun clearTemp(tmpDir: Path) = fs.deleteRecursively(tmpDir)

    override fun write(
        snapshot: Snapshot,
        kmobDir: Path,
        tmpDir: Path,
        info: (artifactBytes: Long) -> HandoverInfo,
    ): WrittenArtifact = writer.write(snapshot, kmobDir, tmpDir, info)

    override fun artifactSize(kmobDir: Path): Long? = fs.metadataOrNull(KmobPaths.artifact(kmobDir))?.size

    override fun readHandoverInfo(kmobDir: Path): HandoverInfo? =
        HandoverInfo.decodeOrNull(runCatching { fs.read(KmobPaths.handoverInfo(kmobDir)) { readUtf8() } }.getOrNull())

    override fun removeKmobDir(kmobDir: Path): Boolean {
        if (!fs.exists(kmobDir)) return false
        fs.deleteRecursively(kmobDir)
        return true
    }
}
