package io.specmatic.gradle.compiler

import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.specmaticExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class ConfigureCompilerOptionsPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.afterEvaluate {
            setupJavaCompilerOpts(target)
            setupKotlinCompilerOpts(target)
        }
    }

    private fun setupKotlinCompilerOpts(target: Project) {
        target.tasks.withType(KotlinCompile::class.java) {
            val specmaticGradleExtension = target.specmaticExtension()
            val jvmVersion = specmaticGradleExtension.jvmVersion
            val kotlinApiVersion = specmaticGradleExtension.kotlinApiVersion
            target.pluginInfo("Configuring Kotlin compiler options on $path. Jvm version: $jvmVersion, Kotlin version: $kotlinApiVersion")

            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(jvmVersion.asInt().toString()))
                apiVersion.set(kotlinApiVersion)
                languageVersion.set(kotlinApiVersion)

                if (kotlinApiVersion <= org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2) {
                    freeCompilerArgs.add("-Xjvm-default=all")
                } else {
                    freeCompilerArgs.add("-Xjvm-default=all-compatibility")
                }
            }
        }
    }

    private fun setupJavaCompilerOpts(target: Project) {
        target.plugins.withType(JavaPlugin::class.java) {
            val specmaticGradleExtension = target.specmaticExtension()
            val jvmVersion = specmaticGradleExtension.jvmVersion

            target.extensions.configure(JavaPluginExtension::class.java) {
                toolchain.languageVersion.set(jvmVersion)
                toolchain.languageVersion.finalizeValue()
            }

            target.tasks.withType(JavaCompile::class.java) {
                target.pluginInfo("Configuring compiler options for ${this.path}")
                options.encoding = "UTF-8"
                options.compilerArgs.add("-Xlint:unchecked") // Warn about unchecked operations.
            }
        }
    }
}
