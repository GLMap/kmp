plugins {
    id("maven-publish")
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
}
group = "software.globus"
version = "0.1.0-beta.1"
val sdkDirectory = System.getenv("GLMAP_SDK_DIR")?.let(::file)
val sdkVersion = sdkDirectory?.let { (groovy.json.JsonSlurper().parse(it.resolve("sdk.json")) as Map<*, *>)["version"] as String } ?: "2.2.0"
// For a published SDK, `scripts/fetch-apple-sdk.py` resolves checked SwiftPM artifacts here.
val sdk = sdkDirectory?.resolve("ios") ?: rootProject.file(".local-sdk/ios")
val frameworks = listOf("GLMap", "GLMapCore", "GLSearch", "GLRoute")
kotlin {
    android {
        namespace = "software.globus.lab.kmp"
        compileSdk = 37
        minSdk = 24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val slice = if (target.name == "iosArm64") "ios-arm64" else "ios-arm64-simulator"
        fun frameworkPath(name: String): String {
            val root = sdk.resolve("$name.xcframework")
            // Released Apple packages may combine arm64/x86_64 in a simulator slice.
            val candidates = if (target.name == "iosArm64") listOf("ios-arm64") else listOf("ios-arm64-simulator", "ios-arm64_x86_64-simulator")
            return candidates.map { root.resolve(it) }.firstOrNull { it.isDirectory }?.path ?: root.resolve(slice).path
        }
        target.binaries.framework { baseName = "GLMapKmp"; isStatic = true }
        target.compilations.getByName("main").cinterops.create("GLMap") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/GLMap.def"))
            compilerOpts(frameworks.map { "-F${frameworkPath(it)}" })
        }
        target.binaries.all {
            linkerOpts(frameworks.map { "-F${frameworkPath(it)}" } + frameworks.flatMap { listOf("-framework", it) })
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            implementation(compose.foundation)
            api(compose.ui)
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        androidMain.dependencies {
            implementation("globus:glmap:$sdkVersion")
            implementation("globus:glsearch:$sdkVersion")
            implementation("globus:glroute:$sdkVersion")
            implementation("androidx.activity:activity-compose:1.11.0")
        }
    }
}

publishing {
    repositories { maven { name = "verification"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
    publications.withType<MavenPublication>().configureEach {
        artifactId = "glmap-kmp" + if (name == "kotlinMultiplatform") "" else "-${name.lowercase()}"
    }
}
