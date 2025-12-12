package io.specmatic.gradle

import java.util.regex.Pattern
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Nested

class SpecmaticGradlePluginPluginFunctionalTest : AbstractFunctionalTest() {
    @Test
    fun `throws error when shadow prefix is not valid package name`() {
        // Set up the test build
        settingsFile.writeText("")
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("io.specmatic.gradle")
            }
            
            specmatic {
                withOSSApplication(rootProject) {
                    shadow("bad-package") 
                }
            }
            """.trimIndent(),
        )

        // Run the build
        val runWithFailure = runWithFailure("tasks")
        assertThat(runWithFailure.output)
            .contains("Invalid Java package name: bad-package")
    }

    @Test
    fun `it should always emit exec task output`() {
        // Set up the test build
        settingsFile.writeText("")
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("io.specmatic.gradle")
            }
            
            tasks.register("myExec", Exec::class.java) {
                commandLine("echo", "hello", "world")
            }
            """.trimIndent(),
        )

        // Run the build
        val result = runWithSuccess("myExec")

        // Verify the result
        assertThat(result.output).contains("hello world")
    }

    @Nested
    inner class VersionPropertiesFile {
        @Test
        fun `it should create version properties file for non-multi-module-project`() {
            // Set up the test build
            settingsFile.writeText("rootProject.name = \"fooBar\"")
            buildFile.writeText(
                """
                plugins {
                    id("java")
                    kotlin("jvm") version "1.9.25"
                    id("io.specmatic.gradle")
                }
                
                repositories {
                    mavenCentral()
                }
                
                dependencies {
                    // workaround for https://github.com/google/osv-scanner/issues/1744
                    implementation("org.junit.jupiter:junit-jupiter-api:5.12.1")
                }
                
                """.trimIndent(),
            )

            // Run the build
            val result = runWithSuccess("assemble")

            // Verify the result
            assertThat(result.task(":createVersionPropertiesFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.task(":createVersionInfoKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            val versionPropertiesFile =
                projectDir.resolve("src/main/gen-resources/io/specmatic/example/version.properties")
            assertThat(versionPropertiesFile).exists()
            assertThat(versionPropertiesFile.readText()).contains("version=1.2.3")

            val versinInfoKotlinFile = projectDir.resolve("src/main/gen-kt/io/specmatic/example/VersionInfo.kt")
            assertThat(versinInfoKotlinFile).exists()
            assertThat(versinInfoKotlinFile.readText()).contains("val version = \"1.2.3\"")
        }

        @Test
        fun `it should create version properties file for multi-module-project`() {
            // Set up the test build
            settingsFile.writeText(
                """
                rootProject.name = "fooBar"
                include("project-a")
                include("project-b")
                """.trimIndent(),
            )

            projectDir.resolve("project-a").mkdirs()
            projectDir.resolve("project-b").mkdirs()

            buildFile.writeText(
                """
                plugins {
                    id("java")
                    id("io.specmatic.gradle")
                }
                            
                project(":project-a") {
                   apply(plugin = "java")
                }
                // nothing applied to project-b
                """.trimIndent(),
            )

            // Run the build
            val result = runWithSuccess("assemble")

            // Verify the result
            assertThat(result.task(":project-a:createVersionPropertiesFile")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.task(":project-a:createVersionInfoKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

            assertThat(projectDir.resolve("src/main/gen-resources/io/specmatic/example/version.properties")).doesNotExist()
            assertThat(projectDir.resolve("src/main/gen-kt/io/specmatic/example/VersionInfo.kt")).doesNotExist()

            assertThat(projectDir.resolve("project-a/src/main/gen-resources/io/specmatic/example/project/a/version.properties")).exists()
            assertThat(projectDir.resolve("project-a/src/main/gen-kt/io/specmatic/example/project/a/VersionInfo.kt")).exists()

            // no generated resources dir itself
            assertThat(projectDir.resolve("project-b/src/main/gen-resources")).doesNotExist()
            assertThat(projectDir.resolve("project-b/src/main/gen-kt")).doesNotExist()
        }
    }

    @Nested
    inner class MavenCentralPublishing {
        @Test
        fun `should not configure maven central publishing if feature is disabled`() {
            // Set up the test build
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id("java")
                    id("io.specmatic.gradle")
                }
                
                specmatic {
                    withOSSApplication(rootProject) {
                        // we asked it to be published, but not specified where
                        publish() {
                            // we don't configure pom
                        }
                    }
                }
                """.trimIndent(),
            )

            // Run the build
            val result = runWithSuccess("tasks", "--all")

            assertThat(result.output).doesNotMatch(
                Pattern.compile(
                    ".*publish.*mavencentral.*",
                    Pattern.CASE_INSENSITIVE,
                ),
            )
        }

        @Test
        fun `should add maven central publishing tasks if feature is enabled`() {
            // Set up the test build
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id("java")
                    id("io.specmatic.gradle")
                }
                
                specmatic {
                    publishToMavenCentral()
                    
                    withOSSApplication(rootProject) {
                        // we asked it to be published, but also specified where
                        publish {}
                    }
                }
                """.trimIndent(),
            )

            // Run the build
            val result = runWithSuccess("tasks", "--all")

            println(result.tasks.joinToString("\n") { it.path })
            assertThat(result.output).matches(
                Pattern.compile(
                    ".*publish\\S*MavenCentral\\S*.*",
                    Pattern.MULTILINE or Pattern.DOTALL or Pattern.CASE_INSENSITIVE,
                ),
            )
        }
    }

    @Nested
    inner class MainClassAttribute {
        @Test
        fun `adds main class to jars if specified in project`() {
            // Set up the test build
            settingsFile.writeText("rootProject.name = \"fooBar\"")
            buildFile.writeText(
                """
                plugins {
                    id("java")
                    id("io.specmatic.gradle")
                }
                
                repositories {
                    mavenCentral()
                }
                
                dependencies {
                    // workaround for https://github.com/google/osv-scanner/issues/1744
                    implementation("org.junit.jupiter:junit-jupiter-api:5.12.1")
                }
                
                specmatic {
                    publishToMavenCentral()
                    
                    withOSSApplication(rootProject) {
                        mainClass = "org.example.Main"
                        
                        // we asked it to be published, but also specified where
                        publish {}
                    }
                }
                
                tasks.register("customJar", Jar::class.java) {
                    archiveBaseName.set("customJar")
                    from("src/main/resources")
                }
                
                """.trimIndent(),
            )

            val result = runWithSuccess("jar", "customJar")
            assertThat(result.task(":jar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            val jarFile = projectDir.resolve("build/libs/fooBar-1.2.3.jar")
            assertThat(jarFile).exists()

            assertThat(mainClass(jarFile)).isEqualTo("org.example.Main")

            val customJar = projectDir.resolve("build/libs/customJar-1.2.3.jar")
            assertThat(customJar).exists()
            assertThat(mainClass(customJar)).isEqualTo("org.example.Main")
        }

        @Test
        fun `should not add main class to jar if not specified in project`() {
            // Set up the test build
            settingsFile.writeText("rootProject.name = \"fooBar\"")
            buildFile.writeText(
                """
                plugins {
                    id("java")
                    id("io.specmatic.gradle")
                }

                repositories {
                    mavenCentral()
                }
                
                dependencies {
                    // workaround for https://github.com/google/osv-scanner/issues/1744
                    implementation("org.junit.jupiter:junit-jupiter-api:5.12.1")
                }
                
                specmatic {
                    publishToMavenCentral()
                    
                    withOSSApplication(rootProject) { }
                }
                
                tasks.register("customJar", Jar::class.java) {
                    archiveBaseName.set("customJar")
                    from("src/main/resources")
                }
                
                """.trimIndent(),
            )

            val result = runWithSuccess("jar", "customJar")
            assertThat(result.task(":jar")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            val jarFile = projectDir.resolve("build/libs/fooBar-1.2.3.jar")
            assertThat(jarFile).exists()

            assertThat(mainClass(jarFile)).isNull()

            val customJar = projectDir.resolve("build/libs/customJar-1.2.3.jar")
            assertThat(customJar).exists()
            assertThat(mainClass(customJar)).isNull()
        }
    }

    @Nested
    inner class JarVulnScan {
        @Test
        fun `should flag vulnerable dependencies in jars`() {
            // Set up the test build
            settingsFile.writeText("rootProject.name = \"fooBar\"")
            buildFile.writeText(
                """
                plugins {
                    id("java")
                    id("io.specmatic.gradle")
                }
                
                repositories {
                    mavenCentral()
                }
                
                dependencies {
                    // add a dependency with some vulnerabilities
                    implementation("com.google.code.gson:gson:2.8.8")
                }
                
                """.trimIndent(),
            )

            val result = runWithFailure("check")
            assertThat(result.output).matches(
                Pattern.compile(
                    ".* com.google.code.gson:gson .* CVE-2022-25647 .* 2.8.8 .*",
                    Pattern.MULTILINE or Pattern.DOTALL or Pattern.CASE_INSENSITIVE,
                ),
            )

            assertThat(result.output).contains("Vulnerabilities with severity [CRITICAL, HIGH] found in the scan.")
        }
    }
}
