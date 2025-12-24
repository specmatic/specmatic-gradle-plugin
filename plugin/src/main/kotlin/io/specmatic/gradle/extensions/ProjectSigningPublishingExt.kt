package io.specmatic.gradle.extensions

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.MavenPublishBasePlugin
import io.specmatic.gradle.jar.massage.mavenPublications
import io.specmatic.gradle.jar.massage.publishing
import io.specmatic.gradle.jar.publishing.isCommercial
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.specmaticExtension
import org.gradle.api.Project
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

internal fun Project.configureSigning() {
    plugins.apply(SigningPlugin::class.java)

    plugins.withType(SigningPlugin::class.java) {
        extensions.getByType(SigningExtension::class.java).apply {
            val keyId = System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKeyId")
            val key = System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey")
            val password = System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword")
            useInMemoryPgpKeys(keyId, key, password)
        }

        tasks.withType(Sign::class.java) {
            isRequired = System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKeyId") != null
        }
    }
}

internal fun Project.configurePublishing() {
    plugins.apply(MavenPublishBasePlugin::class.java)
    plugins.withType(MavenPublishBasePlugin::class.java) {
        pluginInfo("Configuring maven publishing")
        setupPublishingTargets()
    }
}

private fun Project.setupPublishingTargets() {
    val stagingRepo =
        project.uri(
            project.rootProject.layout.buildDirectory
                .dir("mvn-repo"),
        )

    project.extensions.getByType(MavenPublishBaseExtension::class.java).apply {
        val specmaticExtension = project.rootProject.specmaticExtension()

        val publishTargets =
            specmaticExtension.projectConfigurations[project]?.publishTo.orEmpty() +
                listOf(
                    MavenInternal(
                        "staging",
                        stagingRepo,
                        RepoType.PUBLISH_ALL,
                    ),
                )

        publishTargets.forEach { publishTarget ->
            if (publishTarget is MavenCentral) {
                publishToMavenCentral(project.properties["disableMavenCentralAutoPublish"] == "true")

                if (project.isCommercial()) {
                    setupCommericalJavadocAndSources()
                }
            } else if (publishTarget is MavenInternal) {
                val repo = publishTarget
                project.pluginInfo("Configuring publishing to ${repo.repoName} with url ${repo.url} and type ${repo.type}")
                publishing.repositories.maven {
                    name = repo.repoName
                    url = repo.url
                    if (url.scheme != "file") {
                        credentials(PasswordCredentials::class.java)
                    }
                }
            } else {
                project.pluginInfo("publishToMavenCentral is not set. Not publishing to Maven Central")
            }
        }

        if (project.isCommercial()) {
            tasks.withType(PublishToMavenRepository::class.java) {
                onlyIf("disabling publishing of unobfuscated artifacts to this repository") {
                    val mavenRepo = publishTargets.filterIsInstance<MavenInternal>().firstOrNull { it.repoName == this.repository.name }

                    val singleDependency =
                        publication.artifacts
                            .flatMap { it.buildDependencies.getDependencies(null) }
                            .filterIsInstance<Jar>()
                            .first()

                    val thisPublishTaskIsPublishingObfuscatedDependency =
                        (
                            singleDependency.archiveClassifier.get() == "obfuscated" ||
                                singleDependency.archiveClassifier.get() == "all-obfuscated"
                        )

                    if (mavenRepo is MavenInternal) {
                        mavenRepo.type == RepoType.PUBLISH_ALL ||
                            (mavenRepo.type == RepoType.PUBLISH_OBFUSCATED_ONLY && thisPublishTaskIsPublishingObfuscatedDependency)
                    } else {
                        thisPublishTaskIsPublishingObfuscatedDependency
                    }
                }
            }
        }
        signAllPublications()
    }
}

private fun Project.setupCommericalJavadocAndSources() {
    val dummyReadme =
        project.layout.buildDirectory
            .file("generated/dummy-readme/README.md")
            .get()
            .asFile

    tasks.register("generateDummyReadme") {
        doLast {
            dummyReadme.parentFile.mkdirs()
            dummyReadme.writeText(
                """
                This is a DUMMY FILE.
                
                Specmatic does not open source this library (${project.group}:${project.name}:${project.version}).
                Therefore the source code is not included in this distribution.
                
                Please contact Specmatic (https://specmatic.io) for more information.
                """.trimIndent(),
            )
        }
    }

    val sourcesJarTask =
        project.tasks.register("emptySourcesJar", Jar::class.java) {
            dependsOn("generateDummyReadme")
            archiveClassifier.set("sources")

            from(dummyReadme.parentFile) {
                include(dummyReadme.name)
            }
        }

    project.mavenPublications { artifact(sourcesJarTask) }

    val javadocJarTask =
        project.tasks.register("emptyJavadocJar", Jar::class.java) {
            dependsOn("generateDummyReadme")

            archiveClassifier.set("javadoc")

            from(dummyReadme.parentFile) {
                include(dummyReadme.name)
            }
        }

    project.mavenPublications { artifact(javadocJarTask) }

    project.tasks.withType(Jar::class.java) {
        if (name.contains("sourcesjar", ignoreCase = true) || name.contains("javadocjar")) {
            dependsOn("generateDummyReadme")

            includeEmptyDirs = false

            from(dummyReadme.parentFile) {
                include(dummyReadme.name)
            }

            eachFile {
                if (this.path != dummyReadme.name) {
                    this.exclude()
                }
            }
        }
    }
}
