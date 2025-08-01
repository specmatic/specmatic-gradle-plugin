package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider

internal fun Project.createRunObfuscatedFatJarTask(obfuscatedShadowJar: TaskProvider<ShadowJar>, mainClass: String) {
    tasks.register("runObfuscated", JavaExec::class.java) {
        this.group = "application"
        this.dependsOn(obfuscatedShadowJar)
        this.mainClass.set(mainClass)

        this.classpath(
            provider {
                obfuscatedShadowJar
                    .get()
                    .outputs.files.singleFile
            },
        )
    }
}

internal fun Project.createRunFatJarTask(unobfuscatedShadowJar: TaskProvider<ShadowJar>, mainClass: String) {
    tasks.register("runUnObfuscated", JavaExec::class.java) {
        this.group = "application"
        this.dependsOn(unobfuscatedShadowJar)
        this.mainClass.set(mainClass)

        this.classpath(
            provider {
                unobfuscatedShadowJar
                    .get()
                    .outputs.files.singleFile
            },
        )
    }
}
