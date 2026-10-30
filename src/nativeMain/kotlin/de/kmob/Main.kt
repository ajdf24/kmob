package de.kmob

import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner
import okio.FileSystem
import kotlin.system.exitProcess

/** Einstieg (Plattformschicht): Implementierungen des Betriebssystems an die Verdrahtung geben. */
fun main(args: Array<String>) {
    val app = App(hostPlatform(), hostProcessRunner(), FileSystem.SYSTEM)
    exitProcess(app.run(programArguments(args)))
}

/** Implementierung von [Platform] für das Betriebssystem des Binaries. */
internal expect fun hostPlatform(): Platform

/** Implementierung von [ProcessRunner] für das Betriebssystem des Binaries. */
internal expect fun hostProcessRunner(): ProcessRunner

/** Kommandozeilenargumente ohne Programmnamen, unverändert (R1.1, R9.3). */
internal expect fun programArguments(args: Array<String>): List<String>
