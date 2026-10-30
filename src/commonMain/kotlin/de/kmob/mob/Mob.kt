package de.kmob.mob

import de.kmob.KmobException
import de.kmob.platform.Platform
import de.kmob.platform.ProcessRunner

/** Aufruf des mob-Binaries (design §5.1). Die Umgebung wird unverändert vererbt (kein MOB_CLI_NAME, R1.6). */
class Mob(private val platform: Platform, private val runner: ProcessRunner) {

    /** Pfad zu mob: `KMOB_MOB_BIN` oder `mob` im PATH (R1.5, R6.3). */
    val binary: String by lazy { resolve() }

    private fun resolve(): String {
        val configured = platform.env("KMOB_MOB_BIN")?.takeIf { it.isNotEmpty() }
        if (configured != null) {
            if (!platform.isExecutable(configured)) {
                throw KmobException("KMOB_MOB_BIN=$configured is not an executable file.")
            }
            return configured
        }
        return platform.findInPath("mob")
            ?: throw KmobException("'mob' not found in PATH. Install from https://mob.sh or set KMOB_MOB_BIN.")
    }

    /** Führt mob mit geerbten Streams aus und liefert dessen Exit-Code. */
    fun run(args: List<String>): Int = runner.runInherit(listOf(binary) + args)
}
