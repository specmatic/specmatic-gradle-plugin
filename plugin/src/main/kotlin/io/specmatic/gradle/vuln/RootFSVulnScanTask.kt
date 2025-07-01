package io.specmatic.gradle.vuln

import java.io.File
import javax.inject.Inject
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.Sign
import org.gradle.process.ExecOperations

abstract class RootFSVulnScanTask
    @Inject
    constructor(execLauncher: ExecOperations) : AbstractVulnScanTask(execLauncher) {
        @get:InputDirectory
        abstract val inputDir: Property<File>

        override fun getCommandLine(format: String): List<String> =
            listOf(trivyExecutable(), "rootfs", "--format", format, *commonArgs, inputDir.get().path)
    }

internal fun Project.createJarVulnScanTask() {
    val scanTaskName = "vulnScanJar"

    val scanTask =
        tasks.register("${scanTaskName}Scan", RootFSVulnScanTask::class.java) {
            dependsOn("assemble")
            dependsOn(project.tasks.withType(Jar::class.java))
            dependsOn(project.tasks.withType(Sign::class.java))

            trivyHomeDir.set(trivyHomeDir())
            inputDir.set(
                project.layout.buildDirectory
                    .file("libs")
                    .get()
                    .asFile
            )
            reportsDir.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/vulnerabilities/jars")
            )
        }

    project.tasks.named("check") {
        dependsOn(scanTask)
    }
}
