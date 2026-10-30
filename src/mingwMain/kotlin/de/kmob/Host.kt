package de.kmob

import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner
import de.kmob.platform.windows.WindowsPlatform
import de.kmob.platform.windows.WindowsProcessRunner

private val platform: WindowsPlatform by lazy { WindowsPlatform() }

internal actual fun hostPlatform(): Platform = platform

internal actual fun hostProcessRunner(): ProcessRunner = WindowsProcessRunner(platform)

/** Aus `GetCommandLineW`, damit Nicht-ASCII-Argumente unabhängig von der Codepage ankommen (R9.9). */
internal actual fun programArguments(args: Array<String>): List<String> = WindowsPlatform.programArguments()
