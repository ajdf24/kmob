package de.kmob.platform

import kotlin.time.Instant

/** Ergebnis eines Prozesses mit eingesammelter Ausgabe. */
data class Captured(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Startet externe Programme (mob, git, gitleaks). design §3, §5.9 */
interface ProcessRunner {
    /** Erbt stdin/stdout/stderr; SIGINT/SIGQUIT im Kind auf Default. Liefert Exit-Code oder 128 + Signal. */
    fun runInherit(cmd: List<String>): Int

    /** stdin = /dev/null, stdout und stderr werden eingesammelt. */
    fun runCapture(cmd: List<String>): Captured
}

/** Zugriff auf Umgebung und Terminal. design §3 */
interface Platform {
    fun env(name: String): String?

    /** Aufgelöster Pfad (Symlinks), null wenn nicht existent. */
    fun realPath(path: String): String?

    /** stdin und stderr sind ein Terminal. */
    fun isInteractive(): Boolean

    /** Eine Zeile von stdin, null bei EOF. */
    fun readLine(): String?

    fun findInPath(binary: String): String?

    /** Existierende, ausführbare reguläre Datei. */
    fun isExecutable(path: String): Boolean

    fun homeDir(): String

    fun now(): Instant

    fun printOut(line: String)

    fun printErr(line: String)
}
