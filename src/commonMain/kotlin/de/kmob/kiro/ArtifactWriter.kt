package de.kmob.kiro

import de.kmob.KmobException
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

const val KMOB_DIR = ".kmob"
const val ARTIFACT_FILE = "kiro-session.json"
const val HANDOVER_FILE = "handover.json"

/**
 * Schreibt Artefakt und Handover-Info atomar nach `<root>/.kmob/` (design §5.7, K5-04).
 * Temporäre Dateien liegen in [tmpDir] (im git-dir, nie im Working Tree).
 */
class ArtifactWriter(private val fs: FileSystem) {

    data class Written(val artifact: Path, val bytes: Long)

    fun write(snapshot: Snapshot, kmobDir: Path, tmpDir: Path, info: (artifactBytes: Long) -> HandoverInfo): Written {
        val tmpArtifact = tmpDir / ARTIFACT_FILE
        val tmpHandover = tmpDir / HANDOVER_FILE
        try {
            // 1. Artefakt streamen: {"format":…,"metadata":<roh>,"log_entries":[<zeilen>]}
            fs.sink(tmpArtifact).buffer().use { out ->
                out.writeUtf8("{\"format\":\"kiro-session-export-v1\",\"metadata\":")
                out.writeUtf8(fs.read(snapshot.metadata) { readUtf8() })
                out.writeUtf8(",\"log_entries\":[")
                fs.source(snapshot.log).buffer().use { src ->
                    var first = true
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        if (!first) out.writeByte(','.code)
                        out.writeUtf8(line)
                        first = false
                    }
                }
                out.writeUtf8("]}")
            }
            val bytes = fs.metadata(tmpArtifact).size ?: 0L
            // 2. Handover-Info
            fs.write(tmpHandover) { writeUtf8(HandoverInfo.encode(info(bytes))) }
            // 3. Commit-Reihenfolge: alte Info weg, dann Artefakt, dann Info
            fs.createDirectories(kmobDir)
            fs.delete(kmobDir / HANDOVER_FILE, mustExist = false)
            fs.atomicMove(tmpArtifact, kmobDir / ARTIFACT_FILE)
            fs.atomicMove(tmpHandover, kmobDir / HANDOVER_FILE)
            return Written(kmobDir / ARTIFACT_FILE, bytes)
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
}
