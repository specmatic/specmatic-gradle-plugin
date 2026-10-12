package io.specmatic.gradle.downstreamprojects

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Updates a checked-out downstream repository")
abstract class BumpDownstreamVersionTask : DefaultTask() {
    @get:Input abstract val propertyKey: Property<String>
    @get:Input @get:Optional abstract val moduleVersion: Property<String>
    @get:Internal abstract val gradlePropertiesFile: RegularFileProperty

    @TaskAction
    fun bump() {
        val version = moduleVersion.orNull ?: return
        val key = propertyKey.get()
        logger.lifecycle("Bumping $key in ${gradlePropertiesFile.get().asFile.parentFile.parentFile.name}")
        val file = gradlePropertiesFile.get().asFile
        val content = replacePropertyValue(file, key, version)
        file.writeText(
            if (content.lines().none { it.startsWith("$key=") }) "$content\n$key=$version\n" else content,
        )
    }
}

@DisableCachingByDefault(because = "Runs a build in a checked-out downstream repository")
abstract class ValidateDownstreamProjectTask : DefaultTask() {
    @get:Internal abstract val downstreamProjectDir: DirectoryProperty
    @get:Input abstract val repositoryName: Property<String>
    @get:Input abstract val propertyKey: Property<String>
    @get:Input @get:Optional abstract val moduleVersion: Property<String>

    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun validate() {
        val projectDir = downstreamProjectDir.get().asFile
        val version = moduleVersion.orNull.orEmpty()
        val (command, display) = downstreamValidationCommand(projectDir, propertyKey.get(), version, System.getProperty("os.name"))
        logger.lifecycle("Validating ${repositoryName.get()} using $display")
        execOperations.exec {
            workingDir(projectDir)
            commandLine(command)
        }
    }
}

@DisableCachingByDefault(because = "Clones or updates a remote repository")
abstract class CloneOrUpdateDownstreamRepositoryTask : DefaultTask() {
    @get:Internal abstract val downstreamProjectDir: DirectoryProperty
    @get:Input abstract val repositoryName: Property<String>
    @get:Input abstract val skipClone: Property<Boolean>

    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun cloneOrUpdate() {
        if (skipClone.get()) return
        val directory = downstreamProjectDir.get().asFile
        if (!directory.exists()) {
            execOperations.exec {
                commandLine("git", "clone", "https://github.com/specmatic/${repositoryName.get()}.git", directory, "--recursive")
            }
        } else {
            execOperations.exec {
                workingDir(directory)
                commandLine("git", "pull", "--ff-only", "--no-rebase")
            }
        }
    }
}

@DisableCachingByDefault(because = "Fetches artifacts from a downstream repository")
abstract class FetchDownstreamArtifactsTask : DefaultTask() {
    @get:Internal abstract val downstreamProjectDir: DirectoryProperty
    @get:Input abstract val dependencyGroup: Property<String>
    @get:Input abstract val artifact: Property<String>
    @get:Input @get:Optional abstract val moduleVersion: Property<String>

    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun fetch() {
        val version = moduleVersion.orNull ?: return
        execOperations.exec {
            workingDir(downstreamProjectDir.get().asFile)
            commandLine("../specmatic-github-workflows/bin/fetch-artifacts", "-a", "${dependencyGroup.get()}:${artifact.get()}:$version", "-r", "lib")
        }
    }
}

internal fun downstreamValidationCommand(
    projectDir: File,
    propertyKey: String,
    moduleVersion: String,
    operatingSystem: String,
): Pair<List<String>, String> {
    val isWindows = operatingSystem.lowercase().contains("windows")
    val gradleBuild = File(projectDir, "build.gradle").exists() || File(projectDir, "build.gradle.kts").exists()
    if (gradleBuild) {
        val wrapper = if (isWindows) "`gradlew.bat`" else "`./gradlew`"
        val command = if (isWindows) listOf("cmd", "/c", "gradlew.bat", "check", "-P$propertyKey=$moduleVersion")
        else listOf("./gradlew", "check", "-P$propertyKey=$moduleVersion")
        return command to "$wrapper check -P$propertyKey=$moduleVersion"
    }

    if (File(projectDir, "pom.xml").exists()) {
        val wrapper = if (isWindows) File(projectDir, "mvnw.bat") else File(projectDir, "mvnw")
        val executable = if (wrapper.exists()) wrapper.name else "mvn"
        val command = when {
            isWindows -> listOf("cmd", "/c", executable, "test", "-D$propertyKey=$moduleVersion")
            wrapper.exists() -> listOf("./$executable", "test", "-D$propertyKey=$moduleVersion")
            else -> listOf("mvn", "test", "-D$propertyKey=$moduleVersion")
        }
        return command to "$executable test -D$propertyKey=$moduleVersion"
    }

    throw GradleException("No build file found in ${projectDir.absolutePath}: expected build.gradle, build.gradle.kts or pom.xml")
}
