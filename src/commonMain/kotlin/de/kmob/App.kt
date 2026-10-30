package de.kmob

import de.kmob.cli.Args
import de.kmob.cli.Command
import de.kmob.cli.Invocation
import de.kmob.mob.Mob
import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner
import okio.FileSystem

/** Verdrahtung und Befehlsauswahl. Liefert den Exit-Code (design §4.2). */
class App(
    private val platform: Platform,
    private val runner: ProcessRunner,
    private val fs: FileSystem,
) {
    private val out = Output(platform)
    private val mob = Mob(platform, runner)

    fun run(args: List<String>): Int = try {
        val inv = Args.parse(args, platform::env)
        out.debugEnabled = inv.debug
        out.debug { "command=${inv.command} mobArgs=${inv.mobArgs}" }
        dispatch(inv)
    } catch (e: KmobException) {
        out.error(e.message ?: "error")
        e.exitCode
    }

    private fun dispatch(inv: Invocation): Int = when (inv.command) {
        Command.PASSTHROUGH, Command.NEXT, Command.START, Command.DONE -> mob.run(inv.mobArgs)
        Command.VERSION -> version()
        Command.HELP -> help()
    }

    private fun version(): Int {
        out.stdout("kmob ${BuildInfo.VERSION}")
        return mob.run(listOf("version"))
    }

    private fun help(): Int {
        HELP_TEXT.lines().forEach(out::stdout)
        return mob.run(listOf("help"))
    }

    companion object {
        val HELP_TEXT = """
            |kmob ${BuildInfo.VERSION} – mob.sh with Kiro session handover
            |
            |kmob extends 'next', 'start' and 'done' and passes every other command unchanged to mob.
            |  kmob next    hand over your Kiro session (.kmob/ in the git root) together with the code
            |  kmob start   after mob start: show how to load the handed-over session in Kiro
            |  kmob done    remove .kmob/ before mob done, so no session files end up on the base branch
            |
            |kmob options (after the command):
            |  --yes        next, done: skip confirmations             (KMOB_YES=true)
            |  --debug      next, start, done: debug output, also passed to mob  (KMOB_DEBUG=true)
            |
            |Environment:
            |  KMOB_MOB_BIN             path to the mob binary (default: mob in PATH)
            |  KMOB_KIRO_SESSIONS_DIR   Kiro session directory (default: ~/.kiro/sessions/cli)
            |
            |mob help follows:
            |""".trimMargin()
    }
}
