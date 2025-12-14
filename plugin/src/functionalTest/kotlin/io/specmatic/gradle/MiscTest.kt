package io.specmatic.gradle

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class MiscTest : AbstractFunctionalTest() {
    @Test
    fun `it should apply kotlin version to all projects, even if the project has no specific configuration applied`() {
        settingsFile.appendText(
            """
            //
            include("core")
            include("executable")
            """.trimIndent(),
        )

        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "1.9.25"
                id("io.specmatic.gradle")
            }
            
            subprojects {
                repositories {
                    mavenCentral()
                }
                
                apply(plugin = "org.jetbrains.kotlin.jvm")
                apply(plugin = "maven-publish")
                
                dependencies {
                    // tiny jar, with no deps
                    implementation("org.slf4j:slf4j-api:2.0.17")
                }
                
                configure<PublishingExtension> {
                    publications {
                        create<MavenPublication>("dummy") {
                            from(project.components.getByName("java"))
                        }
                    }
                }
            }
            
            project(":executable") {
                dependencies {
                  implementation(project(":core"))
                }
            }

            """.trimIndent(),
        )

        writeRandomClasses(
            projectDir.resolve("executable"),
            "io.specmatic.example.executable.internal.fluxcapacitor",
        )
        writeMainClass(
            projectDir.resolve("executable"),
            "io.specmatic.example.executable.Main",
            "io.specmatic.example.executable.internal.fluxcapacitor",
        )
        writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal.chronocore")

        runWithSuccess("publishAllPublicationsToStagingRepository", "publishToMavenLocal")

        assertPublishedWithoutSourcesAndJavadocs(
            "io.specmatic.example:core:1.2.3",
            "io.specmatic.example:executable:1.2.3",
        )

        assertThat(getDependencies("io.specmatic.example:core:1.2.3")).containsExactlyInAnyOrder(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
            "org.slf4j:slf4j-api:2.0.17",
        )
        assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).containsExactlyInAnyOrder(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
            "org.slf4j:slf4j-api:2.0.17",
            "io.specmatic.example:core:1.2.3",
        )
    }
}
