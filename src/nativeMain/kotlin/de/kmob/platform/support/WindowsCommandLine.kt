package de.kmob.platform.support

/**
 * Kommandozeile für `CreateProcessW` nach den Regeln der Microsoft-C-Runtime (design §5.9, R9.3).
 * Reines Kotlin, damit das Quoting auf jedem Rechner testbar ist.
 */
object WindowsCommandLine {

    fun quote(args: List<String>): String = args.joinToString(" ", transform = ::quoteArgument)

    /** Unverändert, wenn nichts zu schützen ist; sonst in `"` mit verdoppelten Backslashes vor `"` und am Ende. */
    fun quoteArgument(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '\n' || it == '\u000B' || it == '"' }) return arg
        val out = StringBuilder("\"")
        var backslashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> backslashes++
                '"' -> {
                    out.append("\\".repeat(2 * backslashes + 1)).append('"')
                    backslashes = 0
                }
                else -> {
                    out.append("\\".repeat(backslashes)).append(c)
                    backslashes = 0
                }
            }
        }
        out.append("\\".repeat(2 * backslashes)).append('"')
        return out.toString()
    }

    /**
     * Referenz-Zerlegung wie `CommandLineToArgvW` bzw. die C-Runtime ab VS 2008 (für Tests und Roundtrips).
     * Das erste Element wird wie ein normales Argument behandelt.
     */
    fun parse(commandLine: String): List<String> {
        val args = mutableListOf<String>()
        var i = 0
        val n = commandLine.length
        while (true) {
            while (i < n && (commandLine[i] == ' ' || commandLine[i] == '\t')) i++
            if (i >= n) break
            val current = StringBuilder()
            var inQuotes = false
            while (i < n) {
                val c = commandLine[i]
                if (!inQuotes && (c == ' ' || c == '\t')) break
                if (c == '\\') {
                    var backslashes = 0
                    while (i < n && commandLine[i] == '\\') { backslashes++; i++ }
                    if (i < n && commandLine[i] == '"') {
                        current.append("\\".repeat(backslashes / 2))
                        if (backslashes % 2 == 1) { current.append('"'); i++ }
                    } else {
                        current.append("\\".repeat(backslashes))
                    }
                    continue
                }
                if (c == '"') {
                    if (inQuotes && i + 1 < n && commandLine[i + 1] == '"') {
                        current.append('"') // "" innerhalb von Anführungszeichen = ein "
                        i += 2
                        continue
                    }
                    inQuotes = !inQuotes
                    i++
                    continue
                }
                current.append(c)
                i++
            }
            args += current.toString()
        }
        return args
    }
}
