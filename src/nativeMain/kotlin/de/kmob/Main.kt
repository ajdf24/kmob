package de.kmob

import de.kmob.platform.PosixPlatform
import de.kmob.platform.PosixProcessRunner
import de.kmob.platform.SpawnException
import okio.FileSystem
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val platform = PosixPlatform()
    val code = try {
        App(platform, PosixProcessRunner(), FileSystem.SYSTEM).run(args.toList())
    } catch (e: SpawnException) {
        platform.printErr("kmob: ${e.message}")
        1
    }
    exitProcess(code)
}
