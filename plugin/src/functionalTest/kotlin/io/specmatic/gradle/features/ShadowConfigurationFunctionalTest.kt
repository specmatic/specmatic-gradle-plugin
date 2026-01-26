package io.specmatic.gradle.features

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ShadowConfigurationFunctionalTest : AbstractFunctionalTest() {
    @BeforeEach
    fun setup() {
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
                id("org.jetbrains.kotlin.jvm") version "1.9.25"
                id("io.specmatic.gradle")
            }
            
            subprojects {
                repositories {
                    mavenCentral()
                }
                
                apply(plugin = "java")
                apply(plugin = "org.jetbrains.kotlin.jvm")
                
                dependencies {
                    // tiny jar, with no deps
                    implementation("org.slf4j:slf4j-api:2.0.17")
                }
            }
            
            specmatic {
                withCommercialLibrary(project(":core")) {
                }
                
                withCommercialLibrary(project(":executable")) {
                    shadow("blah")
                }
            }
            
            project(":executable") {
                dependencies {
                  "shadow"(project(":core"))
                }

                tasks.register("runMain", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable/1.2.3/executable-1.2.3.jar"))
                    mainClass = "io.specmatic.example.executable.Main"
                }
                
                tasks.register("runMainOriginal", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable-all-debug/1.2.3/executable-all-debug-1.2.3.jar"))
                    classpath(configurations["runtimeClasspath"])
                    mainClass = "io.specmatic.example.executable.Main"
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
    }

    @Test
    fun `it should shadow packages`() {
        runWithSuccess("publishAllPublicationsToStagingRepository", "publishToMavenLocal")
        assertPublishedWithoutSourcesAndJavadocs(
            "io.specmatic.example:core-min:1.2.3",
            "io.specmatic.example:executable-min:1.2.3",
        )

        assertThat(
            listJarContents("io.specmatic.example:executable-min:1.2.3"),
        ).contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
            .contains("io/specmatic/example/core/version.properties") // from the core dependency
            .contains("io/specmatic/example/executable/VersionInfo.class") // from the executable dependency
            .contains("io/specmatic/example/executable/version.properties") // from the executable dependency
            .contains("io/specmatic/example/executable/VersionInfo.class")
            .contains("io/specmatic/example/executable/version.properties")
            .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged
            .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
            .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
            .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
            .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
            .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
            .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
    }
}
