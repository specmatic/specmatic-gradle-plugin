package io.specmatic.gradle

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class PriospotMixedProjectFunctionalTest : AbstractFunctionalTest() {
    @Test
    fun `priospot should run on jvm project without configuring kover on non jvm project`() {
        settingsFile.writeText(
            """
            rootProject.name = "mixed-project"
            include("web")
            include("frontend")
            """.trimIndent(),
        )

        projectDir.resolve("web").mkdirs()
        projectDir.resolve("frontend").mkdirs()

        buildFile.writeText(
            """
            plugins {
                id("io.specmatic.gradle")
            }

            project(":web") {
                apply(plugin = "java")

                repositories {
                    mavenCentral()
                }
            }

            project(":frontend") {
                apply(plugin = "base")
            }
            """.trimIndent(),
        )

        val result = runWithSuccess("priospot")

        assertThat(result.output).contains("BUILD SUCCESSFUL")
        assertThat(result.output).contains(":web:priospot")
        assertThat(result.output).doesNotContain(":frontend:koverBinaryReport")
    }
}
