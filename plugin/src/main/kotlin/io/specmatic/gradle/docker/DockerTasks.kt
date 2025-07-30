package io.specmatic.gradle.docker

import io.specmatic.gradle.SpecmaticGradlePlugin
import io.specmatic.gradle.features.DockerBuildConfig
import io.specmatic.gradle.features.mainJar
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.versioninfo.versionInfo
import io.specmatic.gradle.vuln.createDockerVulnScanTask
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import org.cyclonedx.gradle.CycloneDxTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec

private const val DOCKER_ORG_PRIMARY = "specmatic"
private const val DOCKER_ORG_SECONDARY = "znsio"
private val DOCKER_ORGANIZATIONS = listOf(DOCKER_ORG_PRIMARY, DOCKER_ORG_SECONDARY)

internal fun Project.registerDockerTasks(dockerBuildConfig: DockerBuildConfig) {
    val imageName = dockerImage(dockerBuildConfig)
    val sourceSbomPath =
        project.layout.buildDirectory
            .get()
            .asFile
            .resolve("reports/cyclonedx/bom.json")

    val createDockerfilesTask =
        tasks.register("createDockerFiles") {
            dependsOn(dockerBuildConfig.mainJarTaskName!!)
            group = "docker"
            description = "Creates the Dockerfile and other files needed to build the docker image"
            val targetJarPath = "/usr/local/share/${project.dockerImage(dockerBuildConfig)}/${project.dockerImage(dockerBuildConfig)}.jar"
            val targetSbomPath = "/usr/local/share/${project.dockerImage(dockerBuildConfig)}/sbom.cyclonedx.json"

            createDockerfile(
                dockerBuildConfig = dockerBuildConfig,
                targetJarPath = targetJarPath,
                sourceSbomFile = sourceSbomPath,
                targetSbomPath = targetSbomPath,
            )
            createSpecmaticShellScript(dockerBuildConfig, targetJarPath)
        }

    pluginInfo("Adding docker tasks on $this")

    val dockerTags =
        DOCKER_ORGANIZATIONS.flatMap { org ->
            listOf("$org/$imageName:$version", "$org/$imageName:latest")
        }

    val commonDockerBuildArgs =
        annotationArgs(imageName) +
            dockerTags
                .flatMap { listOf("--tag", it) }
                .toTypedArray() +
            arrayOf("--file", "Dockerfile") +
            arrayOf("--attest", "type=provenance,mode=max") +
            arrayOf("--attest", "type=sbom") +
            dockerBuildConfig.extraDockerArgs

    tasks.register("dockerBuild", Exec::class.java) {
        dependsOn(createDockerfilesTask, dockerBuildConfig.mainJarTaskName!!, project.tasks.withType(CycloneDxTask::class.java))
        group = "docker"
        description = "Builds the docker image"

        commandLine(
            "docker",
            "build",
            *commonDockerBuildArgs,
            ".",
        )

        workingDir =
            project.layout.buildDirectory
                .get()
                .asFile
    }

    createDockerVulnScanTask(dockerTags.first())

    val dockerBuildxPublishInternalTask =
        tasks.register("dockerBuildxPublishInternal", Exec::class.java) {
            dependsOn(createDockerfilesTask, dockerBuildConfig.mainJarTaskName!!)
            group = "docker"
            description = "Publishes the multivariant docker image"

            commandLine(
                "docker",
                "buildx",
                "build",
                *commonDockerBuildArgs,
                "--platform",
                "linux/amd64,linux/arm64",
                "--push",
                ".",
            )

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
            onlyIf { readmeFile.exists() }

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

            repositoryName.set("$DOCKER_ORG_PRIMARY/$imageName")

            readmeContent.set(provider { readmeFile.readText() })
        }

    tasks.register("dockerBuildxPublish") {
        dependsOn(dockerBuildxPublishInternalTask, dockerReadmePublishTask)
        group = "docker"
        description = "Publish image and README to docker hub"
    }
}

fun Task.createSpecmaticShellScript(dockerBuildConfig: DockerBuildConfig, targetJarPath: String) {
    val imageName = project.dockerImage(dockerBuildConfig)
    val specmaticShellScript =
        project.layout.buildDirectory
            .file(imageName)
            .get()
            .asFile

    this.outputs.file(specmaticShellScript)

    doFirst {
        specmaticShellScript.parentFile.mkdirs()
        val templateStream =
            SpecmaticGradlePlugin::class.java.classLoader.getResourceAsStream("specmatic.sh.template")
                ?: throw IllegalStateException("Unable to find specmatic.sh.template in classpath")
        val templateContent = templateStream.bufferedReader().use { it.readText() }

        val shellScriptContent = templateContent.replace("%TARGET_JAR_PATH%", targetJarPath)
        specmaticShellScript.writeText(shellScriptContent)
        specmaticShellScript.setExecutable(true)
    }
}

private fun Task.createDockerfile(
    dockerBuildConfig: DockerBuildConfig,
    targetJarPath: String,
    sourceSbomFile: File,
    targetSbomPath: String,
) {
    val dockerFile =
        project.layout.buildDirectory
            .file("Dockerfile")
            .get()
            .asFile

    this.outputs.file(dockerFile)

    doFirst {
        dockerFile.parentFile.mkdirs()
        val templateStream =
            SpecmaticGradlePlugin::class.java.classLoader.getResourceAsStream("Dockerfile")
                ?: throw IllegalStateException("Unable to find Dockerfile in classpath")
        val templateContent = templateStream.bufferedReader().use { it.readText() }

        val sourceJarPath =
            project
                .mainJar(dockerBuildConfig.mainJarTaskName!!)
                .relativeTo(
                    project.layout.buildDirectory
                        .get()
                        .asFile,
                ).path
                .replace("\\", "/")

        val sourceSbomPath =
            sourceSbomFile
                .relativeTo(
                    project.layout.buildDirectory
                        .get()
                        .asFile,
                ).path
                .replace("\\", "/")

        val dockerFileContent =
            templateContent
                .replace("%TARGET_JAR_PATH%", targetJarPath)
                .replace("%SOURCE_JAR_PATH%", sourceJarPath)
                .replace("%SOURCE_SBOM_PATH%", sourceSbomPath)
                .replace("%TARGET_SBOM_PATH%", targetSbomPath)
                .replace("%IMAGE_NAME%", project.dockerImage(dockerBuildConfig))

        dockerFile.writeText(dockerFileContent)
    }
}

private fun Project.dockerImage(dockerBuildConfig: DockerBuildConfig): String = if (dockerBuildConfig.imageName.isNullOrBlank()) {
    this.name
} else {
    dockerBuildConfig.imageName!!
}

private fun Project.annotationArgs(imageName: String): Array<String> = arrayOf(
    "org.opencontainers.image.created=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(Date())}",
    "org.opencontainers.image.authors=Specmatic Team <info@specmatic.io>",
    "org.opencontainers.image.url=https://hub.docker.com/u/$DOCKER_ORG_PRIMARY/$imageName",
    "org.opencontainers.image.version=$version",
    "org.opencontainers.image.revision=${project.versionInfo().gitCommit}",
    "org.opencontainers.image.vendor=specmatic.io",
).flatMap { listOf("--annotation", it) }.toTypedArray()
