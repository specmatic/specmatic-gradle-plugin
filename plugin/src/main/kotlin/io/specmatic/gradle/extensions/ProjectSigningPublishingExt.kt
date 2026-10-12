package io.specmatic.gradle.extensions

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.MavenPublishBasePlugin
import io.specmatic.gradle.jar.massage.mavenPublications
import io.specmatic.gradle.jar.massage.publishing
import io.specmatic.gradle.jar.publishing.isCommercial
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.specmaticExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
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
                val enableAutoPublish = project.properties["disableMavenCentralAutoPublish"] != "true"
                publishToMavenCentral(automaticRelease = enableAutoPublish, validateDeployment = false)
            } else if (publishTarget is MavenInternal) {
                val repo = publishTarget
                project.pluginInfo("Configuring publishing to ${repo.repoName} with url ${repo.url} and type ${repo.type}")
                publishing.repositories.maven {
                    name = repo.repoName
                    url = repo.url
                    if (url.scheme != "file") {
                        credentials(PasswordCredentials::class.java)
                    }
                    isAllowInsecureProtocol = url.scheme == "http"
                }
            } else {
                project.pluginInfo("publishToMavenCentral is not set. Not publishing to Maven Central")
            }
        }

        if (project.isCommercial()) {
            tasks.withType(PublishToMavenRepository::class.java) {
                val repoName = name.substringAfterLast("To").removeSuffix("Repository")
                val mavenRepo = publishTargets.filterIsInstance<MavenInternal>().firstOrNull { it.repoName.equals(repoName, ignoreCase = true) }
                val shouldPublish = mavenRepo?.type == RepoType.PUBLISH_ALL || run {
                    val singleDependency =
                        publication.artifacts
                            .flatMap { it.buildDependencies.getDependencies(null) }
                            .filterIsInstance<Jar>()
                            .first()
                    singleDependency.archiveClassifier.get() in setOf("obfuscated", "all-obfuscated")
                }
                onlyIf("disabling publishing of unobfuscated artifacts to this repository", ShouldPublishArtifact(shouldPublish))
            }
        }
        signAllPublications()
    }
}

private class ShouldPublishArtifact(private val shouldPublish: Boolean) : Spec<Task>, java.io.Serializable {
    override fun isSatisfiedBy(element: Task): Boolean = shouldPublish
}

internal fun Project.setupCommericalJavadocAndSources() {
    val dummyReadme =
        project.layout.buildDirectory
            .file("generated/dummy-readme/README.md")
            .get()
            .asFile

    tasks.register("generateDummyReadme", GenerateDummyReadmeTask::class.java) {
        outputFile.set(dummyReadme)
        content.set(
            """
            This is a DUMMY FILE.

            Specmatic does not open source this library (${project.group}:${project.name}:${project.version}).
            Therefore the source code is not included in this distribution.

            Please contact Specmatic (https://specmatic.io) for more information.
            """.trimIndent(),
        )
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

@CacheableTask
abstract class GenerateDummyReadmeTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val content: Property<String>

    @TaskAction
    fun generate() {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(content.get())
        }
    }
}
