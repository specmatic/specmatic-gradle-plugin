package io.specmatic.gradle.vuln

import io.specmatic.gradle.vuln.scanner.ScanTarget
import io.specmatic.gradle.vuln.scanner.ScanTargetKind
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

    override fun scanTarget(): ScanTarget =
        ScanTarget(ScanTargetKind.ROOTFS, inputDir.get().path)
}

internal fun Project.createJarVulnScanTask() {
    val scanTaskName = "vulnScanJar"
    val scannerType = vulnScannerType()

    val scanTask =
        tasks.register("${scanTaskName}Scan", RootFSVulnScanTask::class.java) {
            dependsOn("assemble")
            dependsOn(project.tasks.withType(Jar::class.java))
            dependsOn(project.tasks.withType(Sign::class.java))
            scannerTool.set(scannerType)
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
