package io.specmatic.gradle

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

class LoggingTest : AbstractFunctionalTest() {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var logAllProjectDir: File

    @BeforeEach
    fun setup() {
        createLogAllLibrary()
        buildFile.writeText(
            """
            import org.gradle.api.Plugin
            import org.gradle.api.Project
            import org.gradle.api.artifacts.ModuleDependency
            import org.gradle.api.artifacts.component.ModuleComponentSelector
            
                plugins {
                    id("java")
                    kotlin("jvm") version "1.9.25"
                    id("io.specmatic.gradle")
                }

                repositories {
                    mavenCentral()
                    maven {
                        url = uri("${logAllProjectDir.resolve("build/mvn-repo").toURI()}")
                        name = "log-all-repo"
                    }
                }
                
                dependencies {
                    // a library that simply logs messages using all logging frameworks
                    implementation("io.specmatic.logger:log-all:3.4.5-SNAPSHOT")
                }
                
                specmatic {
                    withOSSApplicationLibrary(rootProject) {
                        mainClass = "io.specmatic.example.Main"
                    }
                }
                
                tasks.register("runMain", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project-all/1.2.3/example-project-all-1.2.3.jar"))
                    mainClass = "io.specmatic.example.Main"
                }
            """.trimIndent(),
        )

        val fileName = "io.specmatic.example.Main".replace(".", "/") + ".kt"
        val packageName = "io.specmatic.example.Main".substringBeforeLast(".")
        projectDir.resolve("src/main/kotlin/$fileName").also {
            writeMainClass(it, packageName)
        }

        writeLogbackXml(this.projectDir)
    }

    private fun writeMainClass(file: File, packageName: String) {
        file.parentFile.mkdirs()
        file.writeText(
            """
            package $packageName
            
            object Main {
                @JvmStatic
                fun main(args: Array<String>) {
                    // auto generated class
                    io.specmatic.example.JULForwarder.forward()
                    // try all the logging frameworks via a 3rd party dependency
                    io.specmatic.example.LogAll.logAll(VersionInfo.describe())
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `it renders logs from all log frameworks using logback`() {
        val result = runWithSuccess("runMain", "-xvulnScanJar")
        assertThat(getDependencies("io.specmatic.example:example-project:1.2.3")).containsExactlyInAnyOrder(
            "io.specmatic.logger:log-all:3.4.5-SNAPSHOT",
            *loggingDependencies,
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
        )
        assertThat(getDependencies("io.specmatic.example:example-project-all:1.2.3")).isEmpty()

        val logLines = result.output.lines().filter { it.contains("[this will only show via logback]") }
        assertThat(logLines).containsExactlyInAnyOrder(
            "[this will only show via logback] INFO  jul -- jul - Hello, world! Version: v1.2.3",
            "[this will only show via logback] INFO  slf4j -- slf4j - Hello, world! Version: v1.2.3",
            "[this will only show via logback] INFO  commons-logging -- commons-logging - Hello, world! Version: v1.2.3",
            "[this will only show via logback] INFO  log4j -- log4j - Hello, world! Version: v1.2.3",
            "[this will only show via logback] INFO  log4j2 -- log4j2 - Hello, world! Version: v1.2.3",
        )
    }

    // creates and publishes a library that depends on log4j, log4jv2, slf4j, commons-logging and writes out a log
    // statement using each logging framework
    private fun createLogAllLibrary() {
        logAllProjectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                rootProject.name = "log-all"
                """.trimIndent(),
            )

        logAllProjectDir.resolve("build.gradle.kts").writeText(
            $$"""
            import org.gradle.api.Plugin
            import org.gradle.api.Project
            import org.gradle.api.artifacts.ModuleDependency
            import org.gradle.api.artifacts.component.ModuleComponentSelector
            
                plugins {
                    id("java")
                    id("maven-publish")
                    kotlin("jvm") version "1.9.25"
                }
            
                repositories {
                    mavenCentral()
                }
                
                dependencies {
                    implementation("log4j:log4j:1.2.17")
                    implementation("commons-logging:commons-logging:1.3.5")
                    implementation("org.slf4j:slf4j-api:2.0.17")
                    implementation("org.apache.logging.log4j:log4j-api:2.12.4")
                }
                
                group = "io.specmatic.logger"
                version = "3.4.5-SNAPSHOT"
            
                publishing {
                    publications {
                        create<MavenPublication>("mavenJava") {
                            from(components["java"])
                        }
                    }
                    repositories {
                       maven {
                         url = file("${project.buildDir}/mvn-repo").toURI()
                         name = "staging"
                       }
                   }
                }
            """.trimIndent(),
        )

        val fileName = "io.specmatic.example.LogAll".replace(".", "/") + ".kt"
        val packageName = "io.specmatic.example.LogAll".substringBeforeLast(".")
        logAllProjectDir.resolve("src/main/kotlin/$fileName").also {
            writeLogUtilClass(it, packageName)
        }

        GradleRunner
            .create()
            .forwardOutput()
            .withProjectDir(logAllProjectDir)
            .withArguments("publishAllPublicationsToStagingRepository")
            .build()
    }

    private fun writeLogUtilClass(file: File, packageName: String) {
        file.parentFile.mkdirs()
        file.writeText(
            """
            package $packageName
            
            class LogAll {
                companion object {
                    fun logAll(suffix: String) {                                
                        // try all the logging frameworks
                        java.util.logging.Logger.getLogger("jul").info("jul - Hello, world! Version: " + suffix)
                        org.apache.commons.logging.LogFactory.getLog("commons-logging").info("commons-logging - Hello, world! Version: " + suffix)
                        org.apache.log4j.Logger.getLogger("log4j").info("log4j - Hello, world! Version: " + suffix)
                        org.apache.logging.log4j.LogManager.getLogger("log4j2").info("log4j2 - Hello, world! Version: " + suffix)
                        org.slf4j.LoggerFactory.getLogger("slf4j").info("slf4j - Hello, world! Version: " + suffix)
                    }
                }
            }
            """.trimIndent(),
        )
    }
}
