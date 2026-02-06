package io.specmatic.gradle.features

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CommercialApplicationFeatureTest : AbstractFunctionalTest() {
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
                    withCommercialApplication(rootProject) {
                        mainClass = "io.specmatic.example.Main"
                        dockerBuild {
                            imageName = "foo-bar-image"
                            extraExecutableNames = listOf("foo", "bar", "baz")
                        }
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                        
                        if(project.findProperty("publishToMavenCentral") == "true") {
                            publishToMavenCentral()
                        }
                    }
                }
                
                """.trimIndent(),
            )

            writeRandomClasses(projectDir, "io.specmatic.example.internal.fluxcapacitor")
            writeMainClass(projectDir, "io.specmatic.example.Main", "io.specmatic.example.internal.fluxcapacitor")
            writeLogbackXml(projectDir)
        }

        @Nested
        inner class WithMavenCentralPublishing {
            @Test
            fun `it publish single fat jar without any dependencies staging repository along with empty javadoc and sources`() {
                runWithSuccess("publishAllPublicationsToStagingRepository", "publishToMavenLocal", "-PpublishToMavenCentral=true")

                val artifacts =
                    arrayOf(
                        "io.specmatic.example:example-project:1.2.3",
                    )

                assertPublishedWithSourcesAndJavadocs(*artifacts)
                artifacts.forEach { assertThat(getDependencies(it)).isEmpty() }

                artifacts.forEach {
                    assertThat(
                        listJarContents(it),
                    ).contains("io/specmatic/example/VersionInfo.class")
                        .contains("io/specmatic/example/version.properties")
                        .contains("kotlin/Metadata.class") // kotlin is also packaged
                        .contains("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                        .contains("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                        .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                    assertThat(mainClass(it))
                        .isEqualTo("io.specmatic.example.Main")
                }
            }
        }

        @Nested
        inner class WithoutMavenCentralPublishing {
            @Test
            fun `it publish single fat jar without any to staging repository without javadoc and sources`() {
                runWithSuccess("publishAllPublicationsToStagingRepository", "publishToMavenLocal")

                val artifacts =
                    arrayOf(
                        "io.specmatic.example:example-project:1.2.3",
                    )

                assertPublishedWithoutSourcesAndJavadocs(*artifacts)
                artifacts.forEach { assertThat(getDependencies(it)).isEmpty() }

                artifacts.forEach {
                    assertThat(
                        listJarContents(it),
                    ).contains("io/specmatic/example/VersionInfo.class")
                        .contains("io/specmatic/example/version.properties")
                        .contains("kotlin/Metadata.class") // kotlin is also packaged
                        .contains("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                        .contains("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                        .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                    assertThat(mainClass(it))
                        .isEqualTo("io.specmatic.example.Main")
                }
            }
        }

        @Test
        fun `it should obfuscate`() {
            val result = runWithSuccess("runObfuscated")
            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")
        }

        @Test
        fun `it should create docker templates`() {
            runWithSuccess("dockerBuild", "createDockerFiles")

            assertThat(projectDir.resolve("build/Dockerfile").exists()).isTrue
            assertThat(projectDir.resolve("build/Dockerfile").readText().lines())
                .contains("ADD reports/cyclonedx/bom.json /usr/local/share/foo-bar-image/sbom.cyclonedx.json")
                .contains("ADD libs/example-project-1.2.3-all-obfuscated.jar /usr/local/share/foo-bar-image/foo-bar-image.jar")
                .contains("ADD foo-bar-image /usr/local/bin/foo-bar-image")
                .contains(
                    "RUN ln -sf /usr/local/bin/foo-bar-image /usr/local/bin/foo && " +
                            "ln -sf /usr/local/bin/foo-bar-image /usr/local/bin/bar && " +
                            "ln -sf /usr/local/bin/foo-bar-image /usr/local/bin/baz",
                )
                .contains("""ENTRYPOINT ["/usr/local/bin/foo-bar-image"]""")

            assertThat(projectDir.resolve("build/foo-bar-image").exists()).isTrue
            assertThat(projectDir.resolve("build/foo-bar-image").readText().lines())
                .contains("""#!/usr/bin/env bash""")
                .contains($$"""exec java $JAVA_OPTS -jar /usr/local/share/foo-bar-image/foo-bar-image.jar "$@"""")
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
                    withCommercialApplication(rootProject) {
                        mainClass = "io.specmatic.example.Main"
                        shadow("example")

                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                }
                
                """.trimIndent(),
            )

            writeRandomClasses(projectDir, "io.specmatic.example.internal.fluxcapacitor")
            writeMainClass(projectDir, "io.specmatic.example.Main", "io.specmatic.example.internal.fluxcapacitor")
            writeLogbackXml(projectDir)
        }

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts

        @Test
        fun `it obfuscates and publishes jars`() {
            val result =
                runWithSuccess(
                    "runObfuscated",
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

            allArtifacts.forEach { assertThat(getDependencies(it)).isEmpty() }

            allArtifacts.forEach {
                assertThat(
                    listJarContents(it),
                ).contains("io/specmatic/example/VersionInfo.class")
                    .contains("io/specmatic/example/version.properties")
                    .contains("example/kotlin/Metadata.class") // kotlin is also packaged
                    .contains("example/org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                    .contains("example/org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                    .contains("example/org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(mainClass(it))
                    .isEqualTo("io.specmatic.example.Main")
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
                    
                    withCommercialApplication(project(":executable")) {
                        mainClass = "io.specmatic.example.executable.Main"
                        dockerBuild {
                            imageName = "specmatic-foo"
                        }
                        
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
            writeLogbackXml(projectDir.resolve("executable"))
        }

        val obfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )

        val allArtifacts = obfuscatedArtifacts

        @Nested
        inner class WithMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars with javadoc and sources`() {
                val result =
                    runWithSuccess(
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                        "runObfuscated",
                        "-PpublishToMavenCentral=true",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

                assertPublishedWithSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*obfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()

                assertThat(getDependencies("io.specmatic.example:core-min:1.2.3")).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
                    "org.slf4j:slf4j-api:2.0.17",
                )

                assertThat(
                    listJarContents("io.specmatic.example:executable:1.2.3"),
                ).contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                    .contains("io/specmatic/example/core/version.properties") // from the core dependency
                    .contains("io/specmatic/example/executable/VersionInfo.class")
                    .contains("io/specmatic/example/executable/version.properties")
                    .contains("kotlin/Metadata.class") // kotlin is also packaged
                    .contains("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                    .contains("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                    .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isEqualTo("io.specmatic.example.executable.Main")
            }
        }

        @Nested
        inner class WithoutMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars without javadoc and sources`() {
                val result =
                    runWithSuccess(
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                        "runObfuscated",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

                assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*obfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()

                assertThat(getDependencies("io.specmatic.example:core-min:1.2.3")).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:2.3.10",
                    "org.slf4j:slf4j-api:2.0.17",
                )

                assertThat(
                    listJarContents("io.specmatic.example:executable:1.2.3"),
                ).contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                    .contains("io/specmatic/example/core/version.properties") // from the core dependency
                    .contains("io/specmatic/example/executable/VersionInfo.class")
                    .contains("io/specmatic/example/executable/version.properties")
                    .contains("kotlin/Metadata.class") // kotlin is also packaged
                    .contains("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                    .contains("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                    .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isEqualTo("io.specmatic.example.executable.Main")
            }
        }

        @Test
        fun `it should create docker templates`() {
            runWithSuccess("dockerBuild", "createDockerFiles")

            assertThat(projectDir.resolve("executable/build/Dockerfile").exists()).isTrue
            assertThat(projectDir.resolve("executable/build/Dockerfile").readText().lines())
                .contains("ADD reports/cyclonedx/bom.json /usr/local/share/specmatic-foo/sbom.cyclonedx.json")
                .contains("ADD libs/executable-1.2.3-all-obfuscated.jar /usr/local/share/specmatic-foo/specmatic-foo.jar")
                .contains("ADD specmatic-foo /usr/local/bin/specmatic-foo")
                .contains("""ENTRYPOINT ["/usr/local/bin/specmatic-foo"]""")

            assertThat(projectDir.resolve("executable/build/specmatic-foo").exists()).isTrue
            assertThat(projectDir.resolve("executable/build/specmatic-foo").readText().lines())
                .contains("""#!/usr/bin/env bash""")
                .contains($$"""exec java $JAVA_OPTS -jar /usr/local/share/specmatic-foo/specmatic-foo.jar "$@"""")
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
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                    
                    withCommercialApplication(project(":executable")) {
                        mainClass = "io.specmatic.example.executable.Main"
                        shadow("example")
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
            writeLogbackXml(projectDir.resolve("executable"))
        }

        val obfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )

        val allArtifacts = obfuscatedArtifacts

        @Test
        fun `it obfuscates and publishes jars`() {
            val result =
                runWithSuccess(
                    "runObfuscated",
                    "publishAllPublicationsToStagingRepository",
                    "publishToMavenLocal",
                    "publishAllPublicationsToObfuscatedOnlyRepository",
                    "publishAllPublicationsToAllArtifactsRepository",
                )

            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

            assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

            assertThat(
                projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*obfuscatedArtifacts)

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allArtifacts)

            assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()

            assertThat(
                listJarContents("io.specmatic.example:executable:1.2.3"),
            ).contains("example/io/specmatic/example/core/VersionInfo.class") // from the core dependency
                .contains("example/io/specmatic/example/core/version.properties") // from the core dependency
                .contains("io/specmatic/example/executable/VersionInfo.class")
                .contains("io/specmatic/example/executable/version.properties")
                .contains("example/kotlin/Metadata.class") // kotlin is also packaged
                .contains("example/org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .contains("example/org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .contains("example/org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:executable:1.2.3"))
                .isEqualTo("io.specmatic.example.executable.Main")
        }
    }
}
