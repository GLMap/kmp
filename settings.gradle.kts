pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        System.getenv("GLMAP_SDK_DIR")?.let { maven { url = uri(file(it).resolve("maven")) } }
        maven { url = uri("https://maven.globus.software/artifactory/libs") }
    }
}
rootProject.name = "glmap-kmp"
include(":glmap-core-kmp", ":glmap-kmp", ":glsearch-kmp", ":glroute-kmp")
include(":example:shared", ":example:androidApp")
project(":glmap-core-kmp").projectDir=file("glmap-core")
project(":glmap-kmp").projectDir=file("glmap")
project(":glsearch-kmp").projectDir=file("glsearch")
project(":glroute-kmp").projectDir=file("glroute")

include(":headless-probe", ":headless-app")
