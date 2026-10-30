import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "de.kmob"

// BuildInfo.kt mit der Version aus gradle.properties generieren (design §9)
val generatedDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val version = project.version.toString()
    val outDir = generatedDir
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("de/kmob/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package de.kmob
            |
            |/** Generiert aus gradle.properties – nicht von Hand ändern. */
            |object BuildInfo {
            |    const val VERSION: String = "$version"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    // Schichten (design §3.1): commonMain = OS-freier Kern, nativeMain = neutrale Plattformhilfen + Einstieg,
    // posixMain = macOS + Linux, mingwMain = Windows (Default-Gruppe "mingw").
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("native") {
                group("posix") {
                    withApple()
                    withLinux()
                }
                group("mingw") {
                    withMingw()
                }
            }
        }
    }

    val posixTargets = listOf(macosArm64(), linuxX64())
    posixTargets.forEach { target ->
        target.compilations.getByName("main").cinterops.create("posix") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/posix.def"))
        }
        // Release-Testbinary für Messungen (PerfTest)
        target.binaries.test(listOf(NativeBuildType.RELEASE))
    }
    val windows = mingwX64()
    windows.compilations.getByName("main").cinterops.create("windows") {
        definitionFile.set(project.file("src/nativeInterop/cinterop/windows.def"))
    }

    (posixTargets + windows).forEach { target ->
        target.binaries.executable {
            baseName = "kmob"
            entryPoint = "de.kmob.main"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.okio)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.okio.fakefilesystem)
            }
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(false)
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}


apply(from = "gradle/architecture.gradle.kts")
