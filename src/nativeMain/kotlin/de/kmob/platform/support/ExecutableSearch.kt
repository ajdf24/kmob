package de.kmob.platform.support

/**
 * Programmsuche über `PATH` nach den Regeln eines Betriebssystems (design §5.9, R9.2).
 * Reines Kotlin, damit beide Konventionen auf jedem Entwicklungsrechner testbar sind.
 */
class ExecutableSearch private constructor(
    private val listSeparator: Char,
    private val directorySeparators: Set<Char>,
    private val joinSeparator: Char,
    /** Endungen, die an Namen ohne passende Endung angehängt werden; leer = Name unverändert. */
    private val extensions: List<String>,
    private val stripQuotes: Boolean,
    private val hasDrive: (String) -> Boolean,
) {
    /**
     * Name mit Verzeichnisanteil → nur dieser Pfad. Sonst jedes Verzeichnis aus [pathValue] der Reihe nach;
     * das aktuelle Verzeichnis wird nie implizit durchsucht.
     */
    fun find(name: String, pathValue: String?, isExecutable: (String) -> Boolean): String? {
        if (hasDirectory(name)) return candidates(name).firstOrNull(isExecutable)
        return directories(pathValue ?: return null)
            .flatMap { candidates(join(it, name)) }
            .firstOrNull(isExecutable)
    }

    private fun hasDirectory(name: String): Boolean = name.any { it in directorySeparators } || hasDrive(name)

    private fun directories(pathValue: String): List<String> = pathValue.split(listSeparator)
        .map { if (stripQuotes) it.replace("\"", "") else it }
        .filter { it.isNotEmpty() }

    private fun join(directory: String, name: String): String =
        directory.trimEnd(*directorySeparators.toCharArray()) + joinSeparator + name

    private fun candidates(file: String): List<String> =
        if (extensions.isEmpty() || extensions.any { file.endsWith(it, ignoreCase = true) }) listOf(file)
        else extensions.map { file + it }

    companion object {
        /** macOS, Linux: `:`-getrennt, Name unverändert. */
        val POSIX = ExecutableSearch(':', setOf('/'), '/', emptyList(), stripQuotes = false, hasDrive = { false })

        private val WINDOWS_EXTENSIONS = listOf(".com", ".exe")

        /**
         * Windows: `;`-getrennt, Anführungszeichen in Einträgen entfernt, Endungen aus `PATHEXT`,
         * davon nur `.com` und `.exe` (keine `.cmd`/`.bat`, R9.2).
         */
        fun windows(pathExt: String?): ExecutableSearch {
            val fromPathExt = (pathExt ?: "").split(';').map { it.trim().lowercase() }.filter { it in WINDOWS_EXTENSIONS }
            return ExecutableSearch(
                listSeparator = ';',
                directorySeparators = setOf('\\', '/'),
                joinSeparator = '\\',
                extensions = fromPathExt.distinct().ifEmpty { WINDOWS_EXTENSIONS },
                stripQuotes = true,
                hasDrive = { it.length >= 2 && it[1] == ':' && it[0].isLetter() },
            )
        }

        /** Ausführbar unter Windows heißt: Endung `.com` oder `.exe`. */
        fun hasWindowsExecutableExtension(path: String): Boolean =
            WINDOWS_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }
    }
}
