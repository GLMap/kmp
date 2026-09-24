import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("maven-publish")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}
group="globus"
version="0.1.0-beta.1"
val sdkRoot=System.getenv("GLMAP_SDK_DIR")?.let(::file)
val sdkVersion=sdkRoot?.let {(groovy.json.JsonSlurper().parse(it.resolve("sdk.json")) as Map<*,*>)["version"] as String} ?: "2.2.0"
val sdk=sdkRoot?.resolve("ios") ?: rootProject.file(".local-sdk/ios")
val frameworks=listOf("GLMap","GLMapCore")
kotlin {
    android { namespace="globus.kmp.glmap";compileSdk=37;minSdk=24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    listOf(iosArm64(),iosSimulatorArm64()).forEach { target ->
        fun path(name:String):String {
            val root=sdk.resolve("$name.xcframework")
            val slices=if(target.name=="iosArm64") listOf("ios-arm64") else listOf("ios-arm64-simulator","ios-arm64_x86_64-simulator")
            return (slices.map {root.resolve(it)}.firstOrNull {it.isDirectory} ?: root.resolve(slices.first())).path
        }
        target.compilations.getByName("main").cinterops.create("GLMap") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/GLMap.def"))
            compilerOpts(frameworks.map {"-F${path(it)}"})
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":glmap-core-kmp"))
            api(compose.runtime)
            api(compose.ui)
            implementation(compose.foundation)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        androidMain.dependencies {
            implementation("globus:glmap:$sdkVersion")
            implementation("androidx.activity:activity-compose:1.11.0")
        }
    }
}
publishing {
    repositories {maven {name="verification";url=uri(rootProject.layout.buildDirectory.dir("maven"))}}
    publications.withType<MavenPublication>().configureEach {
        artifactId="glmap-kmp" + if(name=="kotlinMultiplatform") "" else "-${name.lowercase()}"
    }
}
