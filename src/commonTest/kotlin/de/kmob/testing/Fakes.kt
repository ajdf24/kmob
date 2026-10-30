package de.kmob.testing

import de.kmob.platform.Captured
import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner
import kotlin.time.Instant

class FakePlatform(
    val envVars: MutableMap<String, String> = mutableMapOf(),
    var interactive: Boolean = false,
    val input: ArrayDeque<String> = ArrayDeque(),
    val executables: MutableSet<String> = mutableSetOf(),
    val pathBinaries: MutableMap<String, String> = mutableMapOf("mob" to "/usr/bin/mob"),
    val realPaths: MutableMap<String, String> = mutableMapOf(),
    var nowValue: Instant = Instant.parse("2026-10-29T13:20:00Z"),
    var home: String = "/home/user",
) : Platform {
    val stdout = mutableListOf<String>()
    val stderr = mutableListOf<String>()

    override fun env(name: String): String? = envVars[name]
    override fun realPath(path: String): String? = realPaths[path] ?: path
    override fun isInteractive(): Boolean = interactive
    override fun readLine(): String? = input.removeFirstOrNull()
    override fun findInPath(binary: String): String? = pathBinaries[binary]
    override fun isExecutable(path: String): Boolean = path in executables || path in pathBinaries.values
    override fun homeDir(): String = home
    override fun now(): Instant = nowValue
    override fun printOut(line: String) { stdout += line }
    override fun printErr(line: String) { stderr += line }

    val errText: String get() = stderr.joinToString("\n")
}

/** Protokolliert Aufrufe; Antworten über [handler] (Standard: Exit 0, leere Ausgabe). */
class FakeRunner(
    var handler: (List<String>) -> Captured = { Captured(0, "", "") },
) : ProcessRunner {
    val calls = mutableListOf<List<String>>()
    val inheritCalls = mutableListOf<List<String>>()

    override fun runInherit(cmd: List<String>): Int {
        calls += cmd; inheritCalls += cmd
        return handler(cmd).exitCode
    }

    override fun runCapture(cmd: List<String>): Captured {
        calls += cmd
        return handler(cmd)
    }
}
