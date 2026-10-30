package de.kmob.cli

import de.kmob.domain.UsageException

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
    private const val YES = "--yes"
    private const val END_OF_OPTIONS = "--"
    private val helpFlags = setOf("-h", "--help")
    private val mobOptionsWithValue = setOf("--message", "-m", "--branch", "-b", "--room")

    fun parse(args: List<String>, env: (String) -> String?): Invocation {
        val commandIndex = args.indexOfFirst { it != DEBUG }
        if (commandIndex < 0) return Invocation(Command.HELP, emptyList(), debug = args.isNotEmpty())
        val leadingDebug = commandIndex > 0
        val command = commandOf(args[commandIndex])
        when (command) {
            Command.PASSTHROUGH -> return Invocation(Command.PASSTHROUGH, args)
            Command.VERSION, Command.HELP -> return Invocation(command, emptyList(), debug = leadingDebug)
            else -> {}
        }

        val rest = args.subList(commandIndex + 1, args.size)
        val endOfOptions = rest.indexOf(END_OF_OPTIONS).let { if (it < 0) rest.size else it }
        val interpreted = rest.subList(0, endOfOptions)
        if (interpreted.any { it in helpFlags }) return Invocation(Command.PASSTHROUGH, args)

        val supportsYes = command == Command.NEXT || command == Command.DONE
        val (kept, yesFlag) = removeKmobOptions(interpreted, supportsYes)
        val debug = leadingDebug || DEBUG in kept || envBool(env, "KMOB_DEBUG")
        val yes = supportsYes && (yesFlag || envBool(env, "KMOB_YES"))

        val mobArgs = args.subList(0, commandIndex + 1) + kept + rest.subList(endOfOptions, rest.size)
        return Invocation(command, mobArgs, debug, yes)
    }

    private fun commandOf(token: String): Command = when (token) {
        "next", "n" -> Command.NEXT
        "start", "s" -> Command.START
        "done", "d" -> Command.DONE
        "version", "--version" -> Command.VERSION
        "help", "-h", "--help" -> Command.HELP
        else -> Command.PASSTHROUGH
    }

    /** Entfernt `--yes` (falls unterstützt); Werte von mob-Optionen werden nie als kmob-Option gelesen (K1-10). */
    private fun removeKmobOptions(args: List<String>, supportsYes: Boolean): Pair<List<String>, Boolean> {
        var yes = false
        val kept = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg in mobOptionsWithValue -> {
                    kept += args.subList(i, minOf(i + 2, args.size))
                    i += 2
                    continue
                }
                arg == YES && supportsYes -> yes = true
                else -> kept += arg
            }
            i++
        }
        return kept to yes
    }

    /** `true`/`false`/`1`/`0` (Groß/klein egal), leer/nicht gesetzt = false, sonst Usage-Fehler. */
    fun envBool(env: (String) -> String?, name: String): Boolean {
        val value = env(name)?.trim()?.lowercase() ?: return false
        return when (value) {
            "", "false", "0" -> false
            "true", "1" -> true
            else -> throw UsageException("$name must be true, false, 1 or 0 (is '$value')")
        }
    }
}
