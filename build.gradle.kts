import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "de.kmob"

// BuildInfo.kt mit der Version aus gradle.properties generieren (design §9)
val generatedDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
val generateBuildInfo by tasks.registering {
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
    val nativeTargets: List<KotlinNativeTarget> = listOf(macosArm64(), linuxX64())
    nativeTargets.forEach { target ->
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

