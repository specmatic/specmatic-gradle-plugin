package io.specmatic.gradle.features

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CommercialLibraryFeatureTest : AbstractFunctionalTest() {
    @Nested
    inner class RootModuleOnly {
        @BeforeEach
        fun setup() {
            buildFile.writeText(
                """
                plugins {
                    id("java")
                    kotlin("jvm") version "2.3.10"
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
                    withCommercialLibrary(rootProject) {
                        shadow("blah")
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                        
                        if(project.findProperty("publishToMavenCentral") == "true") {
                            publishToMavenCentral()
                        }
                    }
                }
                
                tasks.register("quickRunObfuscated", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project-min/1.2.3/example-project-min-1.2.3.jar"))
                    classpath(configurations["runtimeClasspath"])
                    mainClass = "io.specmatic.example.Main"
                }
                """.trimIndent(),
            )

            writeRandomClasses(projectDir, "io.specmatic.example.internal.fluxcapacitor")
            writeMainClass(projectDir, "io.specmatic.example.Main", "io.specmatic.example.internal.fluxcapacitor")
            writeLogbackXml(projectDir)
        }

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project-min:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts

        @Nested
        inner class WithoutMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars without sources and javadoc`() {
                val result =
                    runWithSuccess(
                        "quickRunObfuscated",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")

                assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                allArtifacts.filter { it.contains("min") }.forEach {
                    assertThat(getDependencies(it)).containsExactlyInAnyOrder(
                        "org.slf4j:slf4j-api:2.0.17",
                        "org.jetbrains.kotlin:kotlin-stdlib:1.9.20",
                    )
                }

            }
        }

        @Nested
        inner class WithMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars with sources and javadoc`() {
                val result =
                    runWithSuccess(
                        "quickRunObfuscated",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                        "-PpublishToMavenCentral=true",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")

                assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                allArtifacts.filter { it.contains("min") }.forEach {
                    assertThat(getDependencies(it)).containsExactlyInAnyOrder(
                        "org.slf4j:slf4j-api:2.0.17",
                        "org.jetbrains.kotlin:kotlin-stdlib:1.9.20",
                    )
                }

                allArtifacts.filter { !it.contains("min") }.forEach {
                    assertThat(
                        listJarContents(it),
                    ).hasSizeLessThan(100) // vague, but should be less than 100, with kotlin deps, this will be in the hundreds
                        .contains("io/specmatic/example/VersionInfo.class")
                        .contains("io/specmatic/example/version.properties")

                        .doesNotContain("kotlin/Metadata.class") // kotlin is not packaged
                        .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is not packaged
                        .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is not packaged

                        .doesNotContain("blah/kotlin/Metadata.class") // kotlin is not packaged
                        .doesNotContain("blah/org/jetbrains/annotations/Contract.class") // kotlin is not packaged
                        .doesNotContain("blah/org/intellij/lang/annotations/Language.class") // kotlin is not packaged

                        .contains("blah/org/slf4j/Logger.class") // slf4j dependency is also packaged

                    assertThat(mainClass(it)).isNull()
                }
            }
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
                    kotlin("jvm") version "2.3.10"
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
                    
                    withCommercialLibrary(rootProject) {
                        shadow("example")

                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                }
                
                tasks.register("quickRunObfuscated", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project-min/1.2.3/example-project-min-1.2.3.jar"))
                    classpath(configurations["runtimeClasspath"])
                    mainClass = "io.specmatic.example.Main"
                }
                
                """.trimIndent(),
            )

            writeRandomClasses(projectDir, "io.specmatic.example.internal.fluxcapacitor")
            writeMainClass(projectDir, "io.specmatic.example.Main", "io.specmatic.example.internal.fluxcapacitor")
            writeLogbackXml(projectDir)
        }

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project-min:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts

        @Test
        fun `it obfuscates and publishes jars`() {
            val result =
                runWithSuccess(
                    "quickRunObfuscated",
                    "publishAllPublicationsToStagingRepository",
                    "publishToMavenLocal",
                    "publishAllPublicationsToObfuscatedOnlyRepository",
                    "publishAllPublicationsToAllArtifactsRepository",
                )
            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allArtifacts)

            assertPublishedWithoutSourcesAndJavadocs(*arrayOf(*allArtifacts))
            arrayOf(*allArtifacts).filter { it.contains("min") }.forEach {
                assertThat(getDependencies(it)).containsExactlyInAnyOrder(
                    "org.slf4j:slf4j-api:2.0.17",
                    "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
                )
            }

            arrayOf(*allArtifacts).filter { !it.contains("min") }.forEach {
                assertThat(
                    listJarContents(it),
                ).hasSizeLessThan(100) // vague, but should be less than 100, with kotlin deps, this will be in the hundreds
                    .contains("io/specmatic/example/VersionInfo.class")
                    .contains("io/specmatic/example/version.properties")

                    .doesNotContain("kotlin/Metadata.class") // kotlin is not packaged
                    .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is not packaged
                    .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is not packaged

                    .doesNotContain("example/kotlin/Metadata.class") // kotlin is not packaged
                    .doesNotContain("example/org/jetbrains/annotations/Contract.class") // kotlin is not packaged
                    .doesNotContain("example/org/intellij/lang/annotations/Language.class") // kotlin is not packaged

                    .contains("example/org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(mainClass(it)).isNull()
            }
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

            buildFile.writeText(
                """
                plugins {
                    id("java")
                    kotlin("jvm") version "2.3.10"
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
                        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
                        implementation("org.slf4j:slf4j-api:2.0.17")
                    }
                }
                
                specmatic {

                    withCommercialLibrary(project(":core")) {
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                        
                        if(project.findProperty("publishToMavenCentral") == "true") {
                            publishToMavenCentral()
                        }
                    }
                    
                    withCommercialLibrary(project(":executable")) {
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                        
                        if(project.findProperty("publishToMavenCentral") == "true") {
                            publishToMavenCentral()
                        }
                    }
                }
                
                project(":executable") {
                    dependencies {
                      implementation(project(":core"))
                    }
                    
                    tasks.register("quickRunObfuscated", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable-min/1.2.3/executable-min-1.2.3.jar"))
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
            writeLogbackXml(projectDir.resolve("executable"))
            writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal.chronocore")
        }

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable-min:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )


        val allArtifacts = allObfuscatedArtifacts

        @Nested
        inner class WithoutMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars`() {
                val result =
                    runWithSuccess(
                        "quickRunObfuscated",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

                assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                assertThat(getDependencies("io.specmatic.example:core-min:1.2.3")).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
                    "org.slf4j:slf4j-api:2.0.17",
                )
            }
        }

        @Nested
        inner class WithMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars with sources and javadocs`() {
                val result =
                    runWithSuccess(
                        "quickRunObfuscated",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                        "-PpublishToMavenCentral=true",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

                assertPublishedWithSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                assertThat(getDependencies("io.specmatic.example:core-min:1.2.3")).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
                    "org.slf4j:slf4j-api:2.0.17",
                )
            }
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

            buildFile.writeText(
                """
                plugins {
                    id("java")
                    kotlin("jvm") version "2.3.10"
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
                        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
                        implementation("org.slf4j:slf4j-api:2.0.17")
                    }
                }
                
                specmatic {
                    
                    withCommercialLibrary(project(":core")) {
                        shadow("core")

                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                    
                    withCommercialLibrary(project(":executable")) {
                        shadow("example")

                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                }
                
                project(":executable") {
                    dependencies {
                      implementation(project(":core"))
                    }

                    tasks.register("quickRunObfuscated", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable-min/1.2.3/executable-min-1.2.3.jar"))
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
            writeLogbackXml(projectDir.resolve("executable"))
            writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal.chronocore")
        }

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable-min:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts

        @Test
        fun `it obfuscates and publishes jars`() {
            val result =
                runWithSuccess(
                    "quickRunObfuscated",
                    "publishAllPublicationsToStagingRepository",
                    "publishToMavenLocal",
                    "publishAllPublicationsToObfuscatedOnlyRepository",
                    "publishAllPublicationsToAllArtifactsRepository",
                )
            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

            assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)
            assertThat(
                projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allArtifacts)

        }
    }
}
