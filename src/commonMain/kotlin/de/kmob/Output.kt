package de.kmob

import de.kmob.platform.Platform

/** Fehler, der kmob mit Meldung und Exit-Code beendet (design §4.2, §7). */
open class KmobException(message: String, val exitCode: Int = 1) : RuntimeException(message)

/** Falsche Aufrufparameter → Exit 2. */
class UsageException(message: String) : KmobException("$message. See kmob help.", 2)

/** Alle kmob-Meldungen gehen mit Präfix `kmob:` nach stderr, damit stdout von mob unverfälscht bleibt. */
class Output(private val platform: Platform, var debugEnabled: Boolean = false) {
    fun info(message: String) = platform.printErr("kmob: $message")
    fun warn(message: String) = platform.printErr("kmob: warning: $message")
    fun error(message: String) = platform.printErr("kmob: $message")
    fun debug(message: () -> String) {
        if (debugEnabled) platform.printErr("kmob: debug: ${message()}")
    }
    fun stdout(line: String) = platform.printOut(line)
}
