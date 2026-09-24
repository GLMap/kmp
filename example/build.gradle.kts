plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
}
kotlin {
    android { namespace = "software.globus.glmapkmp.example"; compileSdk = 37; minSdk = 24 }
    val sdk = System.getenv("GLMAP_SDK_DIR")?.let { file(it).resolve("ios") } ?: rootProject.file(".local-sdk/ios")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework { baseName = "GLMapKmpDemo"; isStatic = true }
        val candidates = if (target.name == "iosArm64") listOf("ios-arm64") else listOf("ios-arm64-simulator", "ios-arm64_x86_64-simulator")
        val frameworks = listOf("GLMap", "GLMapCore", "GLSearch", "GLRoute")
        target.binaries.all {
            linkerOpts(frameworks.map { name ->
                val root = sdk.resolve("$name.xcframework")
                "-F${candidates.map { root.resolve(it) }.firstOrNull { it.isDirectory } ?: root.resolve(candidates.first())}"
            } + frameworks.flatMap { listOf("-framework", it) })
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material); implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}
