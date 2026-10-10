pluginManagement {
    val specmaticGradlePluginVersion = settings.extra["specmaticGradlePluginVersion"] as String
    plugins {
        id("io.specmatic.gradle") version (specmaticGradlePluginVersion)
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal {
            mavenContent {
                snapshotsOnly()
            }

            content {
                includeGroupByRegex("io.specmatic.*")
            }
        }

        val repos =
            mapOf(
                "specmaticReleases" to uri("https://repo.specmatic.io/releases"),
                "specmaticSnapshots" to uri("https://repo.specmatic.io/snapshots"),
                "specmaticPrivate" to uri("https://repo.specmatic.io/private"),
            )

        repos.forEach { (repoName, repoUrl) ->
            maven {
                this.name = repoName
                this.url = repoUrl
                credentials {
                    username = settings.extra.properties["reposilite.user"]?.toString() ?: System.getenv("SPECMATIC_REPOSILITE_USERNAME")
                    password = settings.extra.properties["reposilite.token"]?.toString() ?: System.getenv("SPECMATIC_REPOSILITE_TOKEN")
                }
            }
        }
    }
}

rootProject.name = "specmatic-gradle-plugin"
include("plugin")
