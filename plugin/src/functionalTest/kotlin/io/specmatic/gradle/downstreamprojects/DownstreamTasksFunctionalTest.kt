package io.specmatic.gradle.downstreamprojects

import io.specmatic.gradle.AbstractFunctionalTest
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class DownstreamTasksFunctionalTest : AbstractFunctionalTest() {
    @Test
    fun `downstream lifecycle tasks store and reuse configuration cache`() {
        buildFile.writeText(
            """
            plugins {
                id("io.specmatic.gradle")
            }

            specmatic {
                downstreamDependentProjects = listOf("downstream")
            }
            """.trimIndent(),
        )

        val firstRun = runWithSuccess("bumpVersion-downstream", "-PskipDownstreamClone=true")
        val secondRun = runWithSuccess("bumpVersion-downstream", "-PskipDownstreamClone=true")
        val thirdRun = runWithSuccess("bumpVersion-downstream", "-PskipDownstreamClone=true")

        assertThat(firstRun.output).contains("Configuration cache entry stored")
        assertThat(secondRun.output).contains("Reusing configuration cache")
        assertThat(thirdRun.output).contains("Reusing configuration cache")
    }
}
