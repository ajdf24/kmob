package de.kmob.cli

import de.kmob.UsageException

enum class Command { NEXT, START, DONE, VERSION, HELP, PASSTHROUGH }

/**
 * Ergebnis der Argumentanalyse.
 * [mobArgs] geht unverändert (bis auf entfernte kmob-Optionen) an mob.
 */
data class Invocation(
    val command: Command,
    val mobArgs: List<String>,
    val debug: Boolean = false,
    val yes: Boolean = false,
)

/**
 * Parser (design §4.1), Stand M2:
 * - Kommando = erstes Argument außer `--debug`; Aliase n/s/d.
 * - Passthrough-Befehle: Argumente identisch, keine KMOB_*-Auswertung (R1.1).
 * - `<cmd> -h/--help` bei erweiterten Befehlen → Passthrough (R1.8).
 * - `--yes` (next, done) wird bis `--` erkannt und entfernt; Werte von mob-Optionen werden übersprungen.
 * Der vollständige Parser (alle Optionen, Widersprüche, Fehler bei falscher Position) folgt in M7.
 */
object Args {
    private const val DEBUG = "--debug"
    private val mobOptionsWithValue = setOf("--message", "-m", "--branch", "-b", "--room")

    fun parse(args: List<String>, env: (String) -> String?): Invocation {
        val cmdIndex = args.indexOfFirst { it != DEBUG }
        if (cmdIndex < 0) return Invocation(Command.HELP, emptyList(), debug = args.isNotEmpty())
        val leadingDebug = cmdIndex > 0
        val token = args[cmdIndex]
        val command = when (token) {
            "next", "n" -> Command.NEXT
            "start", "s" -> Command.START
            "done", "d" -> Command.DONE
            "version", "--version" -> Command.VERSION
            "help", "-h", "--help" -> Command.HELP
            else -> Command.PASSTHROUGH
        }
        if (command == Command.PASSTHROUGH) return Invocation(Command.PASSTHROUGH, args)
        if (command == Command.VERSION || command == Command.HELP) {
            return Invocation(command, emptyList(), debug = leadingDebug)
        }

        val rest = args.subList(cmdIndex + 1, args.size)
        val terminator = rest.indexOf("--").let { if (it < 0) rest.size else it }
        val interpreted = rest.subList(0, terminator)
        if (interpreted.any { it == "-h" || it == "--help" }) return Invocation(Command.PASSTHROUGH, args)

        val supportsYes = command == Command.NEXT || command == Command.DONE
        var yes = false
        val kept = mutableListOf<String>()
        var i = 0
        while (i < interpreted.size) {
            val a = interpreted[i]
            when {
                a in mobOptionsWithValue -> {
                    kept += a
                    if (i + 1 < interpreted.size) kept += interpreted[i + 1]
                    i += 2
                    continue
                }
                a == "--yes" && supportsYes -> yes = true
                else -> kept += a
            }
            i++
        }
        val debug = leadingDebug || DEBUG in kept || envBool(env, "KMOB_DEBUG")
        if (supportsYes && !yes) yes = envBool(env, "KMOB_YES")

        val mobArgs = args.subList(0, cmdIndex + 1) + kept + rest.subList(terminator, rest.size)
        return Invocation(command, mobArgs, debug, yes)
    }

    /** `true`/`false`/`1`/`0` (Groß/klein egal), leer/nicht gesetzt = false, sonst Usage-Fehler. */
    fun envBool(env: (String) -> String?, name: String): Boolean {
        val v = env(name)?.trim()?.lowercase() ?: return false
        return when (v) {
            "", "false", "0" -> false
            "true", "1" -> true
            else -> throw UsageException("$name must be true, false, 1 or 0 (is '$v')")
        }
    }
}
