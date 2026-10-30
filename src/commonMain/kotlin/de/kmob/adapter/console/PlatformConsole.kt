package de.kmob.adapter.console

import de.kmob.application.port.Console
import de.kmob.platform.Platform

/** Alle kmob-Meldungen gehen mit Präfix `kmob:` nach stderr, damit stdout von mob unverfälscht bleibt. */
class PlatformConsole(private val platform: Platform, override var debugEnabled: Boolean = false) : Console {
    override fun info(message: String) = platform.printErr("kmob: $message")
    override fun warn(message: String) = platform.printErr("kmob: warning: $message")
    override fun error(message: String) = platform.printErr("kmob: $message")

    override fun debug(message: () -> String) {
        if (debugEnabled) platform.printErr("kmob: debug: ${message()}")
    }

    override fun stdout(line: String) = platform.printOut(line)
    override fun isInteractive(): Boolean = platform.isInteractive()
    override fun readLine(): String? = platform.readLine()
}
