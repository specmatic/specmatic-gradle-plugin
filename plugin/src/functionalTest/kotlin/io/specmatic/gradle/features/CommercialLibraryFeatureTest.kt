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
                    withCommercialLibrary(rootProject) {
                        shadow("blah")
                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                        
                        if(project.findProperty("publishToMavenCentral") == "true") {
                            publishToMavenCentral()
                        }
                    }
                }
                
                tasks.register("runMain", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project/1.2.3/example-project-1.2.3.jar"))
                    classpath(configurations["runtimeClasspath"])
                    mainClass = "io.specmatic.example.Main"
                }
                
                tasks.register("runMainOriginal", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project-all-debug/1.2.3/example-project-all-debug-1.2.3.jar"))
                    classpath(configurations["runtimeClasspath"])
                    mainClass = "io.specmatic.example.Main"
                }
                
                """.trimIndent(),
            )

            writeRandomClasses(projectDir, "io.specmatic.example.internal.fluxcapacitor")
            writeMainClass(projectDir, "io.specmatic.example.Main", "io.specmatic.example.internal.fluxcapacitor")
            writeLogbackXml(projectDir)
        }

        val allUnobfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project-all-debug:1.2.3",
                "io.specmatic.example:example-project-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
            )

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project:1.2.3",
                "io.specmatic.example:example-project-min:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts + allUnobfuscatedArtifacts

        @Nested
        inner class WithoutMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars without sources and javadoc`() {
                val result =
                    runWithSuccess(
                        "runMain",
                        "runMainOriginal",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")
                assertMainJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")

                assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                allArtifacts.filter { it.contains("min") || it.contains("dont-use-this") }.forEach {
                    assertThat(getDependencies(it)).containsExactlyInAnyOrder(
                        "org.slf4j:slf4j-api:2.0.17",
                        "org.jetbrains.kotlin:kotlin-stdlib:1.9.20",
                    )
                }

                allArtifacts.filter { !it.contains("min") && !it.contains("dont-use-this") }.forEach {
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

        @Nested
        inner class WithMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars with sources and javadoc`() {
                val result =
                    runWithSuccess(
                        "runMain",
                        "runMainOriginal",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                        "-PpublishToMavenCentral=true",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")
                assertMainJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")

                assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)

                allArtifacts.filter { it.contains("min") || it.contains("dont-use-this") }.forEach {
                    assertThat(getDependencies(it)).containsExactlyInAnyOrder(
                        "org.slf4j:slf4j-api:2.0.17",
                        "org.jetbrains.kotlin:kotlin-stdlib:1.9.20",
                    )
                }

                allArtifacts.filter { !it.contains("min") && !it.contains("dont-use-this") }.forEach {
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
                    
                    withCommercialLibrary(rootProject) {
                        shadow("example")

                        publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                        publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    }
                }
                
                tasks.register("runMain", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project/1.2.3/example-project-1.2.3.jar"))
                    classpath(configurations["runtimeClasspath"])
                    mainClass = "io.specmatic.example.Main"
                }
                
                tasks.register("runMainOriginal", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project-all-debug/1.2.3/example-project-all-debug-1.2.3.jar"))
                    classpath(configurations["runtimeClasspath"])
                    mainClass = "io.specmatic.example.Main"
                }
                """.trimIndent(),
            )

            writeRandomClasses(projectDir, "io.specmatic.example.internal.fluxcapacitor")
            writeMainClass(projectDir, "io.specmatic.example.Main", "io.specmatic.example.internal.fluxcapacitor")
            writeLogbackXml(projectDir)
        }

        val allUnobfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project-all-debug:1.2.3",
                "io.specmatic.example:example-project-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
            )

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project:1.2.3",
                "io.specmatic.example:example-project-min:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts + allUnobfuscatedArtifacts

        @Test
        fun `it obfuscates and publishes jars`() {
            val result =
                runWithSuccess(
                    "runMain",
                    "runMainOriginal",
                    "publishAllPublicationsToStagingRepository",
                    "publishToMavenLocal",
                    "publishAllPublicationsToObfuscatedOnlyRepository",
                    "publishAllPublicationsToAllArtifactsRepository",
                )
            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")
            assertMainJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allArtifacts)

            assertPublishedWithoutSourcesAndJavadocs(*arrayOf(*allArtifacts))
            arrayOf(*allArtifacts).filter { it.contains("min") || it.contains("dont-use-this") }.forEach {
                assertThat(getDependencies(it)).containsExactlyInAnyOrder(
                    "org.slf4j:slf4j-api:2.0.17",
                    "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                )
            }

            arrayOf(*allArtifacts).filter { !it.contains("min") && !it.contains("dont-use-this") }.forEach {
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

                    tasks.register("runMain", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable/1.2.3/executable-1.2.3.jar"))
                        classpath(configurations["runtimeClasspath"])
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
            writeLogbackXml(projectDir.resolve("executable"))
            writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal.chronocore")
        }

        val allUnobfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable-min:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:core-all-debug:1.2.3",
                "io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:executable-all-debug:1.2.3",
                "io.specmatic.example:executable-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts + allUnobfuscatedArtifacts

        @Nested
        inner class WithoutMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars`() {
                val result =
                    runWithSuccess(
                        "runMain",
                        "runMainOriginal",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")
                assertMainJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

                assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allUnobfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)
                assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()
                assertThat(getDependencies("io.specmatic.example:executable-all-debug:1.2.3")).isEmpty()

                assertThat(getDependencies("io.specmatic.example:core:1.2.3")).isEmpty()
                assertThat(getDependencies("io.specmatic.example:core-all-debug:1.2.3")).isEmpty()
                assertThat(getDependencies("io.specmatic.example:core-min:1.2.3")).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                    "org.slf4j:slf4j-api:2.0.17",
                )
                assertThat(
                    getDependencies("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3"),
                ).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                    "org.slf4j:slf4j-api:2.0.17",
                )

                // original jar should be larger than obfuscated jar
                assertThat(
                    getJar("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3").length()
                ).isGreaterThan(
                    getJar("io.specmatic.example:core-min:1.2.3").length(),
                )
                assertThat(
                    getJar("io.specmatic.example:core-all-debug:1.2.3").length(),
                ).isGreaterThan(getJar("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3").length())
                assertThat(
                    getJar("io.specmatic.example:core-all-debug:1.2.3").length(),
                ).isGreaterThan(getJar("io.specmatic.example:core:1.2.3").length())

                assertThat(
                    listJarContents("io.specmatic.example:executable-all-debug:1.2.3"),
                ).hasSizeLessThan(110) // vague, but should be less than 100, with kotlin deps, this will be in the hundreds
                    .contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                    .contains("io/specmatic/example/core/version.properties") // from the core dependency
                    .contains("io/specmatic/example/executable/VersionInfo.class")
                    .contains("io/specmatic/example/executable/version.properties")
                    .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
                    .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                    .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                    .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(
                    listJarContents("io.specmatic.example:executable:1.2.3"),
                ).hasSizeLessThan(110) // vague, but should be less than 100, with kotlin deps, this will be in the hundreds
                    .contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                    .contains("io/specmatic/example/core/version.properties") // from the core dependency
                    .contains("io/specmatic/example/executable/VersionInfo.class")
                    .contains("io/specmatic/example/executable/version.properties")
                    .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
                    .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                    .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                    .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isNull()
            }
        }

        @Nested
        inner class WithMavenCentralPublishing {
            @Test
            fun `it obfuscates and publishes jars with sources and javadocs`() {
                val result =
                    runWithSuccess(
                        "runMain",
                        "runMainOriginal",
                        "publishAllPublicationsToStagingRepository",
                        "publishToMavenLocal",
                        "publishAllPublicationsToObfuscatedOnlyRepository",
                        "publishAllPublicationsToAllArtifactsRepository",
                        "-PpublishToMavenCentral=true",
                    )
                assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")
                assertMainJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

                assertPublishedWithSourcesAndJavadocs(*allArtifacts)

                assertThat(
                    projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allUnobfuscatedArtifacts)

                assertThat(
                    projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
                ).containsExactlyInAnyOrder(*allArtifacts)
                assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()
                assertThat(getDependencies("io.specmatic.example:executable-all-debug:1.2.3")).isEmpty()

                assertThat(getDependencies("io.specmatic.example:core:1.2.3")).isEmpty()
                assertThat(getDependencies("io.specmatic.example:core-all-debug:1.2.3")).isEmpty()
                assertThat(getDependencies("io.specmatic.example:core-min:1.2.3")).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                    "org.slf4j:slf4j-api:2.0.17",
                )
                assertThat(
                    getDependencies("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3"),
                ).containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                    "org.slf4j:slf4j-api:2.0.17",
                )

                // original jar should be larger than obfuscated jar
                assertThat(
                    getJar("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3").length()
                ).isGreaterThan(
                    getJar("io.specmatic.example:core-min:1.2.3").length(),
                )
                assertThat(
                    getJar("io.specmatic.example:core-all-debug:1.2.3").length(),
                ).isGreaterThan(getJar("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3").length())
                assertThat(
                    getJar("io.specmatic.example:core-all-debug:1.2.3").length(),
                ).isGreaterThan(getJar("io.specmatic.example:core:1.2.3").length())

                assertThat(
                    listJarContents("io.specmatic.example:executable-all-debug:1.2.3"),
                ).hasSizeLessThan(110) // vague, but should be less than 100, with kotlin deps, this will be in the hundreds
                    .contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                    .contains("io/specmatic/example/core/version.properties") // from the core dependency
                    .contains("io/specmatic/example/executable/VersionInfo.class")
                    .contains("io/specmatic/example/executable/version.properties")
                    .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
                    .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                    .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                    .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(
                    listJarContents("io.specmatic.example:executable:1.2.3"),
                ).hasSizeLessThan(110) // vague, but should be less than 100, with kotlin deps, this will be in the hundreds
                    .contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                    .contains("io/specmatic/example/core/version.properties") // from the core dependency
                    .contains("io/specmatic/example/executable/VersionInfo.class")
                    .contains("io/specmatic/example/executable/version.properties")
                    .doesNotContain("kotlin/Metadata.class") // kotlin is also packaged
                    .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                    .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                    .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

                assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isNull()
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

                    tasks.register("runMain", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable/1.2.3/executable-1.2.3.jar"))
                        classpath(configurations["runtimeClasspath"])
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
            writeLogbackXml(projectDir.resolve("executable"))
            writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal.chronocore")
        }

        val allUnobfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:core-all-debug:1.2.3",
                "io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:executable-all-debug:1.2.3",
                "io.specmatic.example:executable-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
            )

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable-min:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )

        val allArtifacts = allObfuscatedArtifacts + allUnobfuscatedArtifacts

        @Test
        fun `it obfuscates and publishes jars`() {
            val result =
                runWithSuccess(
                    "runMain",
                    "runMainOriginal",
                    "publishAllPublicationsToStagingRepository",
                    "publishToMavenLocal",
                    "publishAllPublicationsToObfuscatedOnlyRepository",
                    "publishAllPublicationsToAllArtifactsRepository",
                )
            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")
            assertMainJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

            assertPublishedWithoutSourcesAndJavadocs(*allArtifacts)
            assertThat(
                projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allObfuscatedArtifacts)

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allArtifacts)

            assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()
            assertThat(getDependencies("io.specmatic.example:core:1.2.3")).isEmpty()

            assertThat(
                listJarContents("io.specmatic.example:executable:1.2.3"),
            ).hasSizeGreaterThan(30) // vague assertion, but if we add dependencies, we should have a large number of files
                .contains("example/io/specmatic/example/core/VersionInfo.class") // from the core dependency
                .contains("example/io/specmatic/example/core/version.properties") // from the core dependency
                .contains("io/specmatic/example/executable/VersionInfo.class")
                .contains("io/specmatic/example/executable/version.properties")
                .doesNotContain("example/kotlin/Metadata.class") // kotlin is not packaged
                .doesNotContain("example/org/jetbrains/annotations/Contract.class") // kotlin is not packaged
                .doesNotContain("example/org/intellij/lang/annotations/Language.class") // kotlin is not packaged
                .doesNotContain("kotlin/Metadata.class") // kotlin is not packaged
                .doesNotContain("org/jetbrains/annotations/Contract.class") // kotlin is not packaged
                .doesNotContain("org/intellij/lang/annotations/Language.class") // kotlin is not packaged
                .contains("example/org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isNull()
        }
    }
}
