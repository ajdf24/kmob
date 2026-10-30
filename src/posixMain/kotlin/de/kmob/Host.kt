package de.kmob

import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner
import de.kmob.platform.posix.PosixPlatform
import de.kmob.platform.posix.PosixProcessRunner

internal actual fun hostPlatform(): Platform = PosixPlatform()

internal actual fun hostProcessRunner(): ProcessRunner = PosixProcessRunner()

internal actual fun programArguments(args: Array<String>): List<String> = args.toList()
