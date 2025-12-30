import io.specmatic.gradle.extensions.RepoType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    // version specified in settings.gradle
    id("io.specmatic.gradle")
}

specmatic {
    kotlinVersion = "2.3.0"
    kotlinApiVersion = KotlinVersion.KOTLIN_2_3

    downstreamDependentProjects =
        listOf(
            "specmatic",
            "specmatic-arazzo",
            "specmatic-async",
            "specmatic-google-pubsub",
            "specmatic-gradle-plugin",
            "specmatic-graphql",
            "specmatic-grpc",
            "specmatic-kafka",
            "specmatic-license",
            "specmatic-openapi",
            "specmatic-redis",
        )
    releasePublishTasks =
        listOf(
            "plugin:publishPlugins",
            "plugin:publishToMavenCentral",
            "plugin:publishAllPublicationsToSpecmaticPrivateRepository",
            "plugin:publishAllPublicationsToSpecmaticSnapshotsRepository",
            "plugin:publishAllPublicationsToSpecmaticReleasesRepository",
        )

    withOSSLibrary(project(":plugin")) {
        vulnerableDeps("org.apache.logging.log4j:log4j-core:2.25.3")
        publishTo("specmaticPrivate", "https://repo.specmatic.io/private", RepoType.PUBLISH_ALL)
        publishTo("specmaticSnapshots", "https://repo.specmatic.io/snapshots", RepoType.PUBLISH_OBFUSCATED_ONLY)
        publishTo("specmaticReleases", "https://repo.specmatic.io/releases", RepoType.PUBLISH_OBFUSCATED_ONLY)
        publishToMavenCentral()

        // from com.gradle.publish.PublishPlugin#PUBLISH_TASK_NAME
        publishGradle {
            pom {
                name = "Specmatic Gradle Plugin"
                description = "For internal use by the specmatic team"
                url = "https://specmatic.io"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://opensource.org/license/mit"
                    }
                }
                developers {
                    developer {
                        id = "specmaticBuilders"
                        name = "Specmatic Builders"
                        email = "info@specmatic.io"
                    }
                }
                scm {
                    connection = "https://github.com/specmatic/specmatic-gradle-plugin"
                    url = "https://github.com/specmatic/specmatic-gradle-plugin"
                }
            }
        }

        githubRelease()
    }

    licenseData {
        name = "com.adarshr.test-logger:com.adarshr.test-logger.gradle.plugin"
        version = "4.0.0"
        projectUrl = "https://github.com/radarsh/gradle-test-logger-plugin"
        license = "Apache-2.0"
    }
    licenseData {
        name = "com.adarshr:gradle-test-logger-plugin"
        version = "4.0.0"
        projectUrl = "https://github.com/radarsh/gradle-test-logger-plugin"
        license = "Apache-2.0"
    }
    licenseData {
        name = "com.github.jk1.dependency-license-report:com.github.jk1.dependency-license-report.gradle.plugin"
        version = "2.9"
        projectUrl = "https://github.com/jk1/Gradle-License-Report"
        license = "Apache-2.0"
    }
    licenseData {
        name = "com.github.jk1:gradle-license-report"
        version = "2.9"
        projectUrl = "https://github.com/jk1/Gradle-License-Report"
        license = "Apache-2.0"
    }
    licenseData {
        name = "org.barfuin.gradle.taskinfo:org.barfuin.gradle.taskinfo.gradle.plugin"
        version = "2.2.0"
        projectUrl = "https://gitlab.com/barfuin/gradle-taskinfo"
        license = "Apache-2.0"
    }
    licenseData {
        name = "org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin"
        version = "2.3.0"
        projectUrl = "https://github.com/CycloneDX/cyclonedx-gradle-plugin"
        license = "Apache-2.0"
    }
    licenseData {
        name = "io.fuchs.gradle.classpath-collision-detector:classpath-collision-detector"
        version = "1.0.0"
        projectUrl = "https://github.com/REPLicated/classpath-collision-detector"
        license = "Apache-2.0"
    }
    licenseData {
        name = "io.fuchs.gradle.classpath-collision-detector:io.fuchs.gradle.classpath-collision-detector.gradle.plugin"
        version = "1.0.0"
        projectUrl = "https://github.com/REPLicated/classpath-collision-detector"
        license = "Apache-2.0"
    }
    licenseData {
        name = "org.gradlex.java-ecosystem-capabilities:org.gradlex.java-ecosystem-capabilities.gradle.plugin"
        version = "1.5.3"
        projectUrl = "https://github.com/gradlex-org/java-ecosystem-capabilities"
        license = "Apache-2.0"
    }
    licenseData {
        name =
            "org.gradlex.jvm-dependency-conflict-resolution:org.gradlex.jvm-dependency-conflict-resolution.gradle.plugin"
        version = "2.2"
        projectUrl = "https://github.com/gradlex-org/jvm-dependency-conflict-resolution"
        license = "Apache-2.0"
    }
}
