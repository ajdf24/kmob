package de.kmob.secrets

import de.kmob.kiro.SnapshotWriter
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.getenv
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Messung (tasks 2.5): nur aktiv mit KMOB_PERF_SESSION=<pfad ohne Endung>, z. B.
 * KMOB_PERF_SESSION=~/.kiro/sessions/cli/<id> ./gradlew macosArm64Test --tests '*PerfTest*'
 */
class PerfTest {
    @Test
    fun snapshotAndScanLargeSession() {
        val base = getenv("KMOB_PERF_SESSION")?.toKString() ?: return
        val fs = FileSystem.SYSTEM
        val tmp = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kmob-perf"
        fs.deleteRecursively(tmp)
        val t0 = TimeSource.Monotonic.markNow()
        val snap = SnapshotWriter.write(fs, "perf", "$base.json".toPath(), "$base.jsonl".toPath(), tmp)
        val t1 = t0.elapsedNow()
        val result = BuiltinScanner().scan(fs, snap)
        val t2 = t0.elapsedNow()
        val mb = fs.metadata(snap.log).size!! / 1_000_000
        println("PERF ${mb} MB, ${snap.entries} entries: snapshot ${t1}, scan ${t2 - t1}, result ${result::class.simpleName}")
        fs.deleteRecursively(tmp)
    }
}
