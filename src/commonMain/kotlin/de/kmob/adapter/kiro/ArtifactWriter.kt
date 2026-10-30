package de.kmob.adapter.kiro

import de.kmob.application.KmobPaths
import de.kmob.application.port.WrittenArtifact
import de.kmob.domain.KmobException
import de.kmob.domain.handover.HandoverInfo
import de.kmob.domain.handover.Snapshot
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * Schreibt Artefakt und Handover-Info atomar nach `<root>/.kmob/` (design §5.7, K5-04).
 * Temporäre Dateien liegen im git-dir, nie im Working Tree.
 */
class ArtifactWriter(private val fs: FileSystem) {

    fun write(snapshot: Snapshot, kmobDir: Path, tmpDir: Path, info: (artifactBytes: Long) -> HandoverInfo): WrittenArtifact {
        val tmpArtifact = tmpDir / KmobPaths.ARTIFACT_FILE
        val tmpHandover = tmpDir / KmobPaths.HANDOVER_FILE
        try {
            fs.sink(tmpArtifact).buffer().use { writeExport(snapshot, it) }
            val bytes = fs.metadata(tmpArtifact).size ?: 0L
            fs.write(tmpHandover) { writeUtf8(HandoverInfo.encode(info(bytes))) }
            moveIntoPlace(tmpArtifact, tmpHandover, kmobDir)
            return WrittenArtifact(KmobPaths.artifact(kmobDir), bytes)
        } catch (e: KmobException) {
            throw e
        } catch (e: Exception) {
            // EXDEV-Fallback folgt in M5 (tasks 5.4)
            throw KmobException("could not write $kmobDir: ${e.message}. Use --no-session to hand over without it.")
        } finally {
            fs.delete(tmpArtifact, mustExist = false)
            fs.delete(tmpHandover, mustExist = false)
        }
    }

    /** Format `kiro-session-export-v1` (Spike S1): `{"format":…,"metadata":<roh>,"log_entries":[<zeilen>]}` */
    private fun writeExport(snapshot: Snapshot, out: BufferedSink) {
        out.writeUtf8("{\"format\":\"kiro-session-export-v1\",\"metadata\":")
        out.writeUtf8(fs.read(snapshot.metadata) { readUtf8() })
        out.writeUtf8(",\"log_entries\":[")
        fs.source(snapshot.log).buffer().use { log ->
            var first = true
            while (true) {
                val line = log.readUtf8Line() ?: break
                if (!first) out.writeByte(','.code)
                out.writeUtf8(line)
                first = false
            }
        }
        out.writeUtf8("]}")
    }

    /** Reihenfolge: alte Info weg, dann Artefakt, dann Info – nie alte Info zu neuem Artefakt (R2.12). */
    private fun moveIntoPlace(tmpArtifact: Path, tmpHandover: Path, kmobDir: Path) {
        fs.createDirectories(kmobDir)
        fs.delete(KmobPaths.handoverInfo(kmobDir), mustExist = false)
        fs.atomicMove(tmpArtifact, KmobPaths.artifact(kmobDir))
        fs.atomicMove(tmpHandover, KmobPaths.handoverInfo(kmobDir))
    }
}
