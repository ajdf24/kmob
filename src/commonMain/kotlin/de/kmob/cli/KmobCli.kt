package de.kmob.cli

import de.kmob.application.FinishMob
import de.kmob.application.HandOverSession
import de.kmob.application.TakeOverSession
import de.kmob.application.port.Console
import de.kmob.application.port.MobCli
import de.kmob.domain.KmobException

/** Befehlsauswahl und Abbildung von Fehlern auf Exit-Codes (design §4.2). */
class KmobCli(
    private val console: Console,
    private val mob: MobCli,
    private val handOver: HandOverSession,
    private val takeOver: TakeOverSession,
    private val finish: FinishMob,
    private val env: (String) -> String?,
    private val version: String,
) {
    fun run(args: List<String>): Int = try {
        val invocation = Args.parse(args, env)
        console.debugEnabled = invocation.debug
        console.debug { "command=${invocation.command} mobArgs=${invocation.mobArgs}" }
        dispatch(invocation)
    } catch (e: KmobException) {
        console.error(e.message ?: "error")
        e.exitCode
    }

    private fun dispatch(invocation: Invocation): Int = when (invocation.command) {
        Command.PASSTHROUGH -> mob.run(invocation.mobArgs)
        Command.NEXT -> handOver.run(invocation.mobArgs, invocation.yes)
        Command.START -> takeOver.run(invocation.mobArgs)
        Command.DONE -> finish.run(invocation.mobArgs)
        Command.VERSION -> version()
        Command.HELP -> help()
    }

    private fun version(): Int {
        console.stdout("kmob $version")
        return mob.run(listOf("version"))
    }

    private fun help(): Int {
        HelpText.lines(version).forEach(console::stdout)
        return mob.run(listOf("help"))
    }
}
