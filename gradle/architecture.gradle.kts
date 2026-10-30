// Architekturprüfung (design §3.3, R10.3): Schichtregeln und OS-freier Kern, als Quelltext-Scan über commonMain.
// Eingebunden in build.gradle.kts per apply(from = …); läuft mit ./gradlew check.

data class ArchViolation(val file: String, val line: Int, val rule: String, val detail: String) {
    override fun toString() = "$file:$line: [$rule] $detail"
}

/** Schicht eines Pakets im Kern; null = kein kmob-Paket. */
fun layerOf(pkg: String): String? = when {
    pkg == "de.kmob" -> "root"
    pkg.startsWith("de.kmob.domain") -> "domain"
    pkg.startsWith("de.kmob.application") -> "application"
    pkg.startsWith("de.kmob.adapter") -> "adapter"
    pkg.startsWith("de.kmob.cli") -> "cli"
    pkg.startsWith("de.kmob.platform") -> "platform"
    else -> null
}

/** Erlaubte Abhängigkeiten je Schicht (design §3.1, Tabelle). */
val allowedLayers = mapOf(
    "domain" to setOf("domain"),
    "application" to setOf("application", "domain"),
    "adapter" to setOf("adapter", "application", "domain", "platform"),
    "cli" to setOf("cli", "application", "domain"),
    "platform" to setOf("platform", "domain"),
    "root" to setOf("root", "domain", "application", "adapter", "cli", "platform"),
)

/** Importe, die im ganzen Kern verboten sind: Plattform-APIs und cinterop (R10.2). */
val forbiddenImportPrefixes = listOf("platform.", "kotlinx.cinterop", "de.kmob.cinterop", "de.kmob.platform.posix", "de.kmob.platform.windows", "de.kmob.platform.support")

/** Okio: Domäne, Anwendung, CLI und Ports nur den Werttyp Path; alles andere (FileSystem, Streams) nur in Adaptern und Verdrahtung. */
val okioValueTypes = setOf("okio.Path", "okio.Path.Companion.toPath")
val okioLayers = setOf("adapter", "root")

/** Vollqualifizierte Verwendung ohne Import, z. B. `platform.posix.getenv(…)`. */
val forbiddenQualifiedNames = listOf("platform.posix.", "platform.windows.", "kotlinx.cinterop.", "de.kmob.cinterop.")
val layerQualifiedName = Regex("""\bde\.kmob\.(domain|application|adapter|cli|platform)\.[a-z]""")

/** Kommentar am Zeilenende entfernen, ohne `//` in Strings (z. B. URLs) zu treffen. */
fun codeOf(line: String): String {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return ""
    return line.replace(Regex("""\s//\s.*$"""), "")
}

/** Tokens, die auf Betriebssystem-Logik hindeuten (R10.2). */
val forbiddenTokens = listOf("OsFamily", "osFamily", "\"/dev/null\"", "\"HOME\"", "\"PATH\"", "\"USERPROFILE\"", "'/'", "'\\\\'")

fun checkSources(sourceRoot: File, displayRoot: File): List<ArchViolation> {
    val violations = mutableListOf<ArchViolation>()
    sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
        val name = file.relativeTo(displayRoot).path
        val lines = file.readLines()
        val pkg = lines.firstOrNull { it.startsWith("package ") }?.let(::codeOf)?.removePrefix("package ")?.trim().orEmpty()
        val layer = layerOf(pkg)
        if (layer == null) {
            val packageLine = lines.indexOfFirst { it.startsWith("package ") } + 1
            violations += ArchViolation(name, maxOf(packageLine, 1), "unknown-layer", "package '$pkg' belongs to no layer")
            return@forEach
        }
        lines.forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = codeOf(raw)
            if (raw.startsWith("import ")) {
                val imported = line.removePrefix("import ").substringBefore(" as ").trim()
                forbiddenImportPrefixes.firstOrNull { imported.startsWith(it) }?.let {
                    violations += ArchViolation(name, lineNo, "os-import", "$imported (core must not use OS APIs)")
                }
                if (imported.startsWith("okio.") && imported !in okioValueTypes && layer !in okioLayers) {
                    violations += ArchViolation(name, lineNo, "filesystem", "$imported only allowed in adapter and App")
                }
                if (imported.startsWith("de.kmob.")) {
                    val target = layerOf(imported.substringBeforeLast('.'))
                        ?: layerOf(imported.substringBeforeLast('.').substringBeforeLast('.'))
                    if (target != null && target !in allowedLayers.getValue(layer)) {
                        violations += ArchViolation(name, lineNo, "layer", "$layer must not depend on $target ($imported)")
                    }
                }
            } else if (!raw.startsWith("package ")) {
                forbiddenTokens.firstOrNull { line.contains(it) }?.let {
                    violations += ArchViolation(name, lineNo, "os-token", "$it (OS logic belongs to the platform layer)")
                }
                forbiddenQualifiedNames.firstOrNull { line.contains(it) }?.let {
                    violations += ArchViolation(name, lineNo, "os-import", "$it used fully qualified (core must not use OS APIs)")
                }
                if (Regex("""\bokio\.(FileSystem|fakefilesystem|buffer|Buffer|Source|Sink)""").containsMatchIn(line) && layer !in okioLayers) {
                    violations += ArchViolation(name, lineNo, "filesystem", "okio file system used fully qualified")
                }
                layerQualifiedName.findAll(line).forEach { match ->
                    val target = match.groupValues[1]
                    if (target !in allowedLayers.getValue(layer)) {
                        violations += ArchViolation(name, lineNo, "layer", "$layer must not depend on $target (${match.value}…)")
                    }
                }
            }
        }
    }
    return violations
}

val checkArchitecture = tasks.register("checkArchitecture") {
    group = "verification"
    description = "Prüft Schichtregeln und OS-freien Kern in src/commonMain (design §3.3)."
    val sources = layout.projectDirectory.dir("src/commonMain/kotlin")
    val projectDir = layout.projectDirectory.asFile
    inputs.dir(sources)
    doLast {
        val violations = checkSources(sources.asFile, projectDir)
        if (violations.isNotEmpty()) {
            throw GradleException("Architecture violations:\n" + violations.joinToString("\n"))
        }
        logger.lifecycle("checkArchitecture: no violations")
    }
}

val checkArchitectureSelfTest = tasks.register("checkArchitectureSelfTest") {
    group = "verification"
    description = "Selbsttest: jede mit '// expect: <regel>' markierte Zeile in src/archTest muss gemeldet werden."
    val sources = layout.projectDirectory.dir("src/archTest/kotlin")
    val projectDir = layout.projectDirectory.asFile
    inputs.dir(sources)
    doLast {
        val found = checkSources(sources.asFile, projectDir).map { "${it.file}:${it.line}:${it.rule}" }.toSet()
        val expected = sources.asFile.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                line.substringAfter("// expect: ", "").takeIf { it.isNotEmpty() }
                    ?.let { "${file.relativeTo(projectDir).path}:${index + 1}:${it.trim()}" }
            }
        }.toSet()
        if (expected.isEmpty()) throw GradleException("no '// expect:' markers in src/archTest")
        val missing = expected - found
        val unexpected = found - expected
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw GradleException("checkArchitecture self test failed.\nnot detected:\n" + missing.joinToString("\n") +
                "\nfalse positives:\n" + unexpected.joinToString("\n"))
        }
    }
}

tasks.named("check") {
    dependsOn(checkArchitecture, checkArchitectureSelfTest)
}
