package de.kmob.platform

import de.kmob.domain.KmobException
import kotlin.time.Instant

/*
 * Ports zum Betriebssystem (design §3.2). Der Kern kennt keine Betriebssysteme, nur diese Fähigkeiten;
 * die Implementierungen liegen in der Plattformschicht (posixMain, mingwMain).
 */

/** Ergebnis eines Prozesses mit eingesammelter Ausgabe. */
data class Captured(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Ein Programm ließ sich nicht starten (nicht gefunden, keine Rechte …). */
class ProcessStartException(message: String) : KmobException(message)

/** Startet externe Programme (mob, git, gitleaks, kiro-cli). */
interface ProcessRunner {
    /** Erbt stdin/stdout/stderr. Ctrl+C beendet das Kind, nicht kmob. Liefert den Exit-Code des Kinds. */
    fun runInherit(cmd: List<String>): Int

    /** stdin leer, stdout und stderr werden eingesammelt. */
    fun runCapture(cmd: List<String>): Captured
}

/** Zugriff auf Umgebung, Dateipfade und Terminal. */
interface Platform {
    /** Verzeichnistrenner in Pfaden, die [realPath] liefert (`/` bzw. `\\`). */
    val pathSeparator: Char

    fun env(name: String): String?

    /** Aufgelöster, kanonischer Pfad (Symlinks), null wenn nicht existent. */
    fun realPath(path: String): String?

    /** stdin und stderr sind ein Terminal. */
    fun isInteractive(): Boolean

    /** Eine Zeile von stdin, null bei EOF. */
    fun readLine(): String?

    /** Programm nach den Suchregeln des Betriebssystems finden. */
    fun findInPath(binary: String): String?

    /** Existierende, ausführbare reguläre Datei. */
    fun isExecutable(path: String): Boolean

    fun homeDir(): String

    fun now(): Instant

    fun printOut(line: String)

    fun printErr(line: String)
}
