package io.specmatic.gradle.features

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OSSLibraryFeatureTest : AbstractFunctionalTest() {
    @Nested
    inner class RootModuleOnly {
        @BeforeEach
        fun setup() {
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
                    // tiny jar, with no deps
                    implementation("org.slf4j:slf4j-api:2.0.17")
                }
                
                specmatic {
                    kotlinVersion = "1.9.20"
                    withOSSLibrary(rootProject) {
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                }
                """.trimIndent(),
            )

            writeMainClass(projectDir, "io.specmatic.example.Main")
        }

        @Test
        fun `it publish jar with all dependencies declared in the pom to staging repository`() {
            runWithSuccess("publishAllPublicationsToStagingRepository", "publishToMavenLocal")

            assertPublishedWithSourcesAndJavadocs("io.specmatic.example:example-project:1.2.3")
            assertThat(getDependencies("io.specmatic.example:example-project:1.2.3")).containsExactlyInAnyOrder(
                "org.jetbrains.kotlin:kotlin-stdlib:1.9.20",
                "org.slf4j:slf4j-api:2.0.17",
            )

            assertThat(
                listJarContents("io.specmatic.example:example-project:1.2.3"),
            ).contains("io/specmatic/example/VersionInfo.class")
                .contains("io/specmatic/example/version.properties")
                .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
                .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .doesNotContain("org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:example-project:1.2.3")).isNull()
        }

        @Test
        fun `assert publication of obfuscated artifacts`() {
            runWithSuccess("publishAllPublicationsToObfuscatedOnlyRepository", "publishAllPublicationsToAllArtifactsRepository")

            assertThat(
                projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder("io.specmatic.example:example-project:1.2.3")

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder("io.specmatic.example:example-project:1.2.3")
        }
    }

    @Nested
    inner class RootModuleOnlyWithShadowingPrefix {
        @BeforeEach
        fun setup() {
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
                    // tiny jar, with no deps
                    implementation("org.slf4j:slf4j-api:2.0.17")
                }
                
                specmatic {
                    withOSSLibrary(rootProject) {
                        shadow("example")
                    }
                }
                """.trimIndent(),
            )

            writeMainClass(projectDir, "io.specmatic.example.Main")
        }

        @Test
        fun `it fails`() {
            val result = runWithFailure("publishAllPublicationsToStagingRepository")
            assertThat(result.output).contains("Cannot access 'shadow': it is protected in 'OSSLibraryFeature'")

            assertNothingPublished()
        }
    }

    @Nested
    inner class MultiModuleOnly {
        @BeforeEach
        fun setup() {
            settingsFile.appendText(
                """
                //
                include("core")
                include("executable")
                """.trimIndent(),
            )

            projectDir.resolve("core").mkdirs()
            projectDir.resolve("executable").mkdirs()

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
                    
                    apply(plugin = "java")
                    apply(plugin = "org.jetbrains.kotlin.jvm")
                    
                    dependencies {
                        // tiny jar, with no deps
                        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
                        implementation("org.slf4j:slf4j-api:2.0.17")
                    }
                }
                
                specmatic {
                    withOSSLibrary(project(":core")) {
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                    
                    withOSSLibrary(project("executable")) {
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                }
                
                project(":executable") {
                    dependencies {
                      implementation(project(":core"))
                    }
                }
                
                """.trimIndent(),
            )

            writeMainClass(projectDir.resolve("executable"), "io.specmatic.example.executable.Main")
        }

        @Test
        fun `it publish all jars with dependencies`() {
            runWithSuccess("publishAllPublicationsToStagingRepository", "publishToMavenLocal")

            assertPublishedWithSourcesAndJavadocs(
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:core:1.2.3",
            )

            assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).containsExactlyInAnyOrder(
                "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                "org.slf4j:slf4j-api:2.0.17",
                "io.specmatic.example:core:1.2.3",
            )
            assertThat(getDependencies("io.specmatic.example:core:1.2.3")).containsExactlyInAnyOrder(
                "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                "org.slf4j:slf4j-api:2.0.17",
            )

            assertThat(
                listJarContents("io.specmatic.example:executable:1.2.3"),
            ).contains("io/specmatic/example/executable/VersionInfo.class")
                .contains("io/specmatic/example/executable/version.properties")
                .doesNotContain("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                .doesNotContain("io/specmatic/example/core/version.properties") // from the core dependency
                .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
                .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .doesNotContain("org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isNull()
        }

        @Test
        fun `assert publication of obfuscated artifacts`() {
            runWithSuccess("publishAllPublicationsToObfuscatedOnlyRepository", "publishAllPublicationsToAllArtifactsRepository")

            assertThat(
                projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder("io.specmatic.example:executable:1.2.3", "io.specmatic.example:core:1.2.3")

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder("io.specmatic.example:executable:1.2.3", "io.specmatic.example:core:1.2.3")
        }
    }

    @Nested
    inner class MultiModuleOnlyWithShadowingPrefix {
        @BeforeEach
        fun setup() {
            settingsFile.appendText(
                """
                //
                include("core")
                include("executable")
                """.trimIndent(),
            )

            projectDir.resolve("core").mkdirs()
            projectDir.resolve("executable").mkdirs()

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
                    
                    apply(plugin = "java")
                    apply(plugin = "org.jetbrains.kotlin.jvm")
                    
                    dependencies {
                        // tiny jar, with no deps
                        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.25")
                        implementation("org.slf4j:slf4j-api:2.0.17")
                    }
                }
                
                specmatic {
                    withOSSLibrary(project(":core")) {
                        shadow("example")
                    }
                }
                
                project(":executable") {
                    dependencies {
                      implementation(project(":core"))
                    }
                }
                
                """.trimIndent(),
            )

            writeMainClass(projectDir.resolve("executable"), "io.specmatic.example.executable.Main")
        }

        @Test
        fun `it fails`() {
            val result = runWithFailure("publishAllPublicationsToStagingRepository")
            assertThat(result.output).contains("Cannot access 'shadow': it is protected in 'OSSLibraryFeature'")
            assertNothingPublished()
        }
    }
}
