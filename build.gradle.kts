plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.1.0" apply false
    id("com.android.application") version "9.1.0" apply false
}

// Remote publishing is opt-in. Ordinary builds and `publish` without this
// property continue to use only the local verification repository.
val glmapPublishUrl = providers.gradleProperty("glmapPublishUrl")
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            glmapPublishUrl.orNull?.let { publishUrl ->
                require(publishUrl.startsWith("https://")) {
                    "glmapPublishUrl must use HTTPS"
                }
                repositories.maven {
                    name = "globus"
                    url = uri(publishUrl)
                    // Read lazily from globusUsername / globusPassword Gradle
                    // properties (or ORG_GRADLE_PROJECT_* environment variables).
                    credentials(org.gradle.api.credentials.PasswordCredentials::class)
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set("GLMap Kotlin Multiplatform bindings for Android and iOS")
                    url.set("https://github.com/GLMap/kmp")
                    scm {
                        url.set("https://github.com/GLMap/kmp")
                        connection.set("scm:git:https://github.com/GLMap/kmp.git")
                    }
                }
            }
        }
    }
}
