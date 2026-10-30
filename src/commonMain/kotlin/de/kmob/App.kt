package de.kmob

import de.kmob.adapter.console.PlatformConsole
import de.kmob.adapter.git.GitCli
import de.kmob.adapter.kiro.ArtifactFiles
import de.kmob.adapter.kiro.SessionStore
import de.kmob.adapter.mob.MobBinary
import de.kmob.adapter.secrets.BuiltinScanner
import de.kmob.adapter.state.FileLocalState
import de.kmob.application.FinishMob
import de.kmob.application.FirstHandoverNotice
import de.kmob.application.HandOverSession
import de.kmob.application.TakeOverSession
import de.kmob.cli.KmobCli
import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner
import okio.FileSystem
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Verdrahtung (design §3.1): baut Adapter und Use Cases aus den Plattform-Ports.
 * Einzige Stelle, die alle Schichten kennt. Liefert den Exit-Code.
 */
class App(platform: Platform, runner: ProcessRunner, fs: FileSystem) {
    private val cli: KmobCli

    init {
        val console = PlatformConsole(platform)
        val mob = MobBinary(platform, runner)
        val git = GitCli(platform, runner)
        val artifacts = ArtifactFiles(fs)
        val clock = object : Clock {
            override fun now(): Instant = platform.now()
        }
        val handOver = HandOverSession(
            mob = mob,
            git = git,
            sessions = SessionStore(fs, platform, console),
            artifacts = artifacts,
            scanner = BuiltinScanner(fs),
            localStateAt = { stateDir -> FileLocalState(fs, stateDir) },
            notice = FirstHandoverNotice(console),
            console = console,
            clock = clock,
            kmobVersion = BuildInfo.VERSION,
            pathSeparator = platform.pathSeparator,
        )
        cli = KmobCli(
            console = console,
            mob = mob,
            handOver = handOver,
            takeOver = TakeOverSession(mob, git, artifacts, console),
            finish = FinishMob(mob, git, artifacts, console),
            env = platform::env,
            version = BuildInfo.VERSION,
        )
    }

    fun run(args: List<String>): Int = cli.run(args)
}
