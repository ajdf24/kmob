package de.kmob.cli

/** kmob-Abschnitt von `kmob help` (R8.5); danach folgt `mob help`. */
object HelpText {
    fun lines(version: String): List<String> = """
        |kmob $version – mob.sh with Kiro session handover
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
        |""".trimMargin().lines()
}
