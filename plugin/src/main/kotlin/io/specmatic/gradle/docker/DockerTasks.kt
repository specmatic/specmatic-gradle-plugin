package io.specmatic.gradle.docker

import io.specmatic.gradle.features.DockerBuildConfig
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.tasks.SpecmaticAggregateTask
import io.specmatic.gradle.versioninfo.versionInfo
import io.specmatic.gradle.vuln.createDockerVulnScanTask
import java.text.SimpleDateFormat
import java.util.Date
import org.cyclonedx.gradle.BaseCyclonedxTask
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

internal fun Project.registerDockerTasks(dockerBuildConfig: DockerBuildConfig) {
    val dockerOrgNames = dockerBuildConfig.dockerOrgNames
    if (dockerOrgNames.isEmpty()) {
        throw GradleException(
            "No docker org names specified in the docker build config. Please specify at least one docker org name.",
        )
    }

    val primaryOrg = dockerOrgNames.first()
    val imageName = dockerImage(dockerBuildConfig)

    val sourceSbomPath =
        project.layout.buildDirectory
            .get()
            .asFile
            .resolve("reports/cyclonedx/bom.json")

    val buildDirectory = layout.buildDirectory.get().asFile
    val createDockerfilesTask =
        tasks.register("createDockerFiles", CreateDockerFilesTask::class.java) {
            dependsOn(dockerBuildConfig.mainJarTaskName!!)
            group = "docker"
            description = "Creates the Dockerfile and other files needed to build the docker image"
            val executable = project.dockerImage(dockerBuildConfig)
            this.sourceSbomPath.set(sourceSbomPath.relativeTo(buildDirectory).invariantSeparatorsPath)
            this.targetJarPath.set("/usr/local/share/$executable/$executable.jar")
            this.targetSbomPath.set("/usr/local/share/$executable/sbom.cyclonedx.json")
            this.dockerOrg.set(primaryOrg)
            this.imageName.set(executable)
            this.imageVersion.set(project.version.toString())
            this.gitSha.set(project.versionInfo().gitCommit)
            this.extraApkDependencies.set(dockerBuildConfig.extraApkDependencies)
            this.extraExecutableNames.set(dockerBuildConfig.extraExecutableNames)
            this.extraDockerfileLines.set(dockerBuildConfig.extraDockerfileLines)
            this.dockerfile.set(layout.buildDirectory.file("Dockerfile"))
            this.shellScript.set(layout.buildDirectory.file(executable))
        }
    project.afterEvaluate {
        val jarArchive = tasks.named(dockerBuildConfig.mainJarTaskName!!, ShadowJar::class.java).flatMap { it.archiveFile }
        createDockerfilesTask.configure {
            sourceJar.set(jarArchive)
            sourceJarPath.set(jarArchive.map { it.asFile.relativeTo(buildDirectory).invariantSeparatorsPath })
        }
    }

    pluginInfo("Adding docker tasks on $this")

    val dockerTags =
        dockerOrgNames.flatMap { org ->
            listOf("$org/$imageName:$version", "$org/$imageName:latest")
        }

    val commonDockerBuildArgs =
        dockerTags
            .flatMap { listOf("--tag", it) }
            .toTypedArray() +
            arrayOf("--file", "Dockerfile") +
            dockerBuildConfig.extraDockerArgs

    tasks.register("dockerBuild", Exec::class.java) {
        dependsOn(createDockerfilesTask, dockerBuildConfig.mainJarTaskName!!, project.tasks.withType(BaseCyclonedxTask::class.java))
        group = "docker"
        description = "Builds the docker image"

        commandLine(
            "docker",
            "buildx",
            "build",
            *annotationArgs(primaryOrg, imageName, includeManifestListAnnotations = false),
            *commonDockerBuildArgs,
            "--load",
            ".",
        )
        environment("DOCKER_BUILDKIT", "1")

        workingDir =
            project.layout.buildDirectory
                .get()
                .asFile
    }

    createDockerVulnScanTask(dockerTags.first())

    val dockerBuildxPublishInternalTask =
        tasks.register("dockerBuildxPublishInternal", Exec::class.java) {
            dependsOn(createDockerfilesTask, dockerBuildConfig.mainJarTaskName!!, project.tasks.withType(BaseCyclonedxTask::class.java))
            group = "docker"
            description = "Publishes the multivariant docker image"

            commandLine(
                "docker",
                "buildx",
                "build",
                *annotationArgs(primaryOrg, imageName, includeManifestListAnnotations = true),
                *commonDockerBuildArgs,
                "--platform",
                "linux/amd64,linux/arm64",
                "--push",
                "--attest",
                "type=provenance,mode=max",
                "--attest",
                "type=sbom",
                ".",
            )

            environment("DOCKER_BUILDKIT", "1")

            workingDir =
                project.layout.buildDirectory
                    .get()
                    .asFile
        }

    val dockerReadmePublishTask =
        tasks.register("dockerReadmePublish", UpdateDockerHubOverviewTask::class.java) {
            dependsOn(dockerBuildxPublishInternalTask)
            group = "docker"
            val readmeFile = project.file("readme.docker.md")
            onlyIf("Docker Hub README exists", HasDockerReadme(readmeFile))

            description = "Publishes the README to docker hub"
            dockerHubUsername.set(
                provider {
                    System.getenv("SPECMATIC_DOCKER_HUB_USERNAME")
                        ?: error("SPECMATIC_DOCKER_HUB_USERNAME environment variable is not set")
                },
            )

            dockerHubApiToken.set(
                provider {
                    System.getenv("SPECMATIC_DOCKER_HUB_TOKEN")
                        ?: error("SPECMATIC_DOCKER_HUB_TOKEN environment variable is not set")
                },
            )

            repositoryName.set("$primaryOrg/$imageName")

            readmeContent.set(provider { readmeFile.readText() })
        }

    tasks.register("dockerBuildxPublish", SpecmaticAggregateTask::class.java) {
        dependsOn(dockerBuildxPublishInternalTask, dockerReadmePublishTask)
        group = "docker"
        description = "Publish image and README to docker hub"
    }
}

@CacheableTask
abstract class CreateDockerFilesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceJar: RegularFileProperty

    @get:Input abstract val sourceJarPath: Property<String>
    @get:Input abstract val sourceSbomPath: Property<String>
    @get:Input abstract val targetJarPath: Property<String>
    @get:Input abstract val targetSbomPath: Property<String>
    @get:Input abstract val dockerOrg: Property<String>
    @get:Input abstract val imageName: Property<String>
    @get:Input abstract val imageVersion: Property<String>
    @get:Input abstract val gitSha: Property<String>
    @get:Input abstract val extraApkDependencies: ListProperty<String>
    @get:Input abstract val extraExecutableNames: ListProperty<String>
    @get:Input abstract val extraDockerfileLines: ListProperty<String>
    @get:OutputFile abstract val dockerfile: RegularFileProperty
    @get:OutputFile abstract val shellScript: RegularFileProperty

    @TaskAction
    fun generate() {
        val executable = imageName.get()
        val aliases = extraExecutableNames.get().joinToString(" && ") { alias ->
            "ln -sf /usr/local/bin/$executable /usr/local/bin/$alias"
        }
        val template = resourceText("Dockerfile")
        val dockerfileContents = template
            .replace("%TARGET_JAR_PATH%", targetJarPath.get())
            .replace("%SOURCE_JAR_PATH%", sourceJarPath.get())
            .replace("%SOURCE_SBOM_PATH%", sourceSbomPath.get())
            .replace("%TARGET_SBOM_PATH%", targetSbomPath.get())
            .replace("%EXECUTABLE_NAME%", executable)
            .replace("%GIT_SHA%", gitSha.get())
            .replace("%IMAGE_VERSION%", imageVersion.get())
            .replace("%IMAGE_URL%", "https://hub.docker.com/u/${dockerOrg.get()}/$executable")
            .replace("%EXTRA_APK_DEPS%", extraApkDependencies.get().joinToString(" "))
            .replace("%EXTRA_COMMANDS%", if (aliases.isEmpty()) "" else "RUN $aliases")
            .replace("%EXTRA_DOCKERFILE_LINES%", extraDockerfileLines.get().joinToString("\n"))

        dockerfile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(dockerfileContents)
        }
        shellScript.get().asFile.apply {
            parentFile.mkdirs()
            writeText(resourceText("specmatic.sh.template").replace("%TARGET_JAR_PATH%", targetJarPath.get()))
            setExecutable(true)
        }
    }

    private fun resourceText(name: String): String = javaClass.classLoader.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
        ?: throw IllegalStateException("Unable to find $name in classpath")
}

private fun Project.dockerImage(dockerBuildConfig: DockerBuildConfig): String = if (dockerBuildConfig.imageName.isNullOrBlank()) {
    this.name
} else {
    dockerBuildConfig.imageName!!
}

private fun Project.annotationArgs(primaryOrg: String, imageName: String, includeManifestListAnnotations: Boolean,): Array<String> {
    val keys =
        listOf(
            "org.opencontainers.image.created" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(Date()),
            "org.opencontainers.image.authors" to "Specmatic Team <info@specmatic.io>",
            "org.opencontainers.image.url" to "https://hub.docker.com/u/$primaryOrg/$imageName",
            "org.opencontainers.image.version" to this.project.version.toString(),
            "org.opencontainers.image.revision" to this.project.versionInfo().gitCommit,
            "org.opencontainers.image.vendor" to "specmatic.io",
        )

    val prefixes =
        if (includeManifestListAnnotations) {
            listOf("", "index:", "manifest-descriptor:")
        } else {
            listOf("")
        }

    return prefixes
        .flatMap { prefix ->
            keys.map { (k, v) -> "--annotation" to "$prefix$k=$v" }
        }.flatMap { listOf(it.first, it.second) }
        .toTypedArray()
}
