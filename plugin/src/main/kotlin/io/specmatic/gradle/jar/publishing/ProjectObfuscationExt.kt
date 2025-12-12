package io.specmatic.gradle.jar.publishing

import io.specmatic.gradle.jar.massage.jar
import io.specmatic.gradle.jar.obfuscate.ProguardTask
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.projectDependencies
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

internal const val OBFUSCATE_JAR_TASK = "obfuscateJar"

internal fun Project.createObfuscatedOriginalJar(proguardExtraArgs: MutableList<String?>): TaskProvider<Jar> {
    val obfuscateJarInternalTask =
        project.tasks.register("obfuscateJarInternal", ProguardTask::class.java) {
            val jarTask = project.tasks.jar.get()
            dependsOn(jarTask)
            inputJar = jarTask.archiveFile.get().asFile
            outputJar = project.file("${project.layout.buildDirectory.get().asFile}/tmp/obfuscated-internal.jar")

            appendProguardArgs(*proguardExtraArgs.filterNotNull().toTypedArray())
        }

    dependOnUpstreamObfuscationTasks(obfuscateJarInternalTask)

    // Jar tasks automatically register as maven publication, so we "copy" the proguard jar into another one
    val obfuscateJarTask =
        project.tasks.register(OBFUSCATE_JAR_TASK, Jar::class.java) {
            project.pluginInfo("Created task $path")

            group = "build"
            description = "Run obfuscation on the output of the `jar` task"

            dependsOn(obfuscateJarInternalTask)
            val obfuscatedTempJar = obfuscateJarInternalTask.get().outputJar!!

            inputs.file(obfuscatedTempJar)

            from(project.zipTree(obfuscatedTempJar))
            archiveClassifier.set("obfuscated")

            this.extensions.extraProperties.set("isObfuscated", true)
        }

    obfuscateJarInternalTask.get().finalizedBy(obfuscateJarTask)
    project.tasks.named("assemble") { dependsOn(obfuscateJarTask) }

    return obfuscateJarTask
}

private fun Project.dependOnUpstreamObfuscationTasks(proguardTask: TaskProvider<ProguardTask>) {
    afterEvaluate {
        val dependentProjects = project.projectDependencies().map { rootProject.project(it.path) }

        val dependentObfuscationTasks =
            dependentProjects.map { dependentProject ->
                dependentProject.tasks.named("obfuscateJarInternal", ProguardTask::class.java)
            }

        dependentObfuscationTasks.forEach { eachUpstreamObfuscationTask ->
            proguardTask.configure {
                dependsOn(eachUpstreamObfuscationTask)
            }
        }
    }
}
