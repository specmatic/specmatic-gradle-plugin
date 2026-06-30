package io.specmatic.gradle.release

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ValidateSnapshotDependenciesFunctionalTest : AbstractFunctionalTest() {
    override fun projectVersion(): String = "2.3.4-SNAPSHOT"

    private fun writeSingleProjectBuild(dependenciesBlock: String) {
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
                $dependenciesBlock
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `succeeds when all dependencies are released versions`() {
        gradleProperties.writeText(
            """
            version=1.0.0
            group=io.specmatic.example
            """.trimIndent(),
        )
        writeSingleProjectBuild("""implementation("io.specmatic.example:released-lib:1.0.0")""")

        val result = runWithSuccess("validateSnapshotDependencies")

        assertThat(result.output).contains("BUILD SUCCESSFUL")
        assertThat(result.output).contains("No SNAPSHOT dependencies found.")
    }

    @Test
    fun `fails when an external snapshot dependency is present`() {
        writeSingleProjectBuild("""implementation("io.specmatic.example:snapshot-lib:1.0.0-SNAPSHOT")""")

        val result = runWithFailure("validateSnapshotDependencies")

        assertThat(result.output).contains("dependencies with SNAPSHOT versions")
        assertThat(result.output).contains("io.specmatic.example:snapshot-lib:1.0.0-SNAPSHOT")
        assertThat(result.output).contains("Please remove them before creating a release")
    }

    @Test
    fun `warns instead of failing when allowSnapshotDependencies is true`() {
        writeSingleProjectBuild("""implementation("io.specmatic.example:snapshot-lib:1.0.0-SNAPSHOT")""")

        val result = runWithSuccess("validateSnapshotDependencies", "-PallowSnapshotDependencies=true")

        assertThat(result.output).contains("BUILD SUCCESSFUL")
        assertThat(result.output).contains("dependencies with SNAPSHOT versions")
        assertThat(result.output).contains("io.specmatic.example:snapshot-lib:1.0.0-SNAPSHOT")
    }

    @Test
    fun `validateSnapshotDependencies passes when the only SNAPSHOT dependencies are internal project dependencies`() {
        settingsFile.writeText(
            """
            rootProject.name = "example-project"
            include("lib-a")
            include("lib-b")
            """.trimIndent(),
        )

        projectDir.resolve("lib-a").mkdirs()
        projectDir.resolve("lib-b").mkdirs()

        buildFile.writeText(
            """
            plugins {
                id("io.specmatic.gradle")
            }

            allprojects {
                group = "io.specmatic.example"
                version = "2.3.4-SNAPSHOT"
            }

            project(":lib-a") {
                apply(plugin = "java")

                repositories {
                    mavenCentral()
                }
            }

            project(":lib-b") {
                apply(plugin = "java")

                repositories {
                    mavenCentral()
                }

                dependencies {
                    // No external SNAPSHOT dependencies - only an internal module dependency.
                    "implementation"(project(":lib-a"))
                }
            }
            """.trimIndent(),
        )

        val result = runWithSuccess("validateSnapshotDependencies")

        assertThat(result.output).contains("BUILD SUCCESSFUL")
        assertThat(result.output).contains("No SNAPSHOT dependencies found.")
        assertThat(result.output).doesNotContain("io.specmatic.example:lib-a:2.3.4-SNAPSHOT")
    }

    @Test
    fun `validateSnapshotDependencies fails on external snapshot dependencies even when internal project dependencies are also SNAPSHOT`() {
        settingsFile.writeText(
            """
            rootProject.name = "example-project"
            include("lib-a")
            include("lib-b")
            """.trimIndent(),
        )

        projectDir.resolve("lib-a").mkdirs()
        projectDir.resolve("lib-b").mkdirs()

        buildFile.writeText(
            """
            plugins {
                id("io.specmatic.gradle")
            }

            allprojects {
                group = "io.specmatic.example"
                version = "2.3.4-SNAPSHOT"
            }

            project(":lib-a") {
                apply(plugin = "java")

                repositories {
                    mavenCentral()
                }
            }

            project(":lib-b") {
                apply(plugin = "java")

                repositories {
                    mavenCentral()
                }

                dependencies {
                    "implementation"(project(":lib-a"))
                    "implementation"("io.specmatic.example:snapshot-lib:1.0.0-SNAPSHOT")
                }
            }
            """.trimIndent(),
        )

        val result = runWithFailure("validateSnapshotDependencies")

        assertThat(result.output).contains("dependencies with SNAPSHOT versions")
        assertThat(result.output).contains("io.specmatic.example:snapshot-lib:1.0.0-SNAPSHOT")
        assertThat(result.output).doesNotContain("io.specmatic.example:lib-a:2.3.4-SNAPSHOT")
    }
}
