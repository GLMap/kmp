pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        System.getenv("GLMAP_SDK_DIR")?.let { maven { url = uri(file(it).resolve("maven")) } }
        maven { url = uri("https://maven.globus.software/artifactory/libs") }
    }
}
rootProject.name = "glmap-kmp"
include(":shared", ":example", ":androidApp")
