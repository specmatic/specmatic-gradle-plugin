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
                    publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                    publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    
                    kotlinVersion = "1.9.20"
                    withCommercialApplication(rootProject) {
                        mainClass = "io.specmatic.example.Main"
                        dockerBuild()
                    }
                }
                
                tasks.register("runMain", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project/1.2.3/example-project-1.2.3.jar"))
                    mainClass = "io.specmatic.example.Main"
                }

                tasks.register("runMainOriginal", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project-all-debug/1.2.3/example-project-all-debug-1.2.3.jar"))
                    mainClass = "io.specmatic.example.Main"
                }
                """.trimIndent(),
            )

            writeRandomClasses(projectDir, "io.specmatic.example.internal.fluxcapacitor")
            writeMainClass(projectDir, "io.specmatic.example.Main", "io.specmatic.example.internal.fluxcapacitor")
            writeLogbackXml(projectDir)
        }

        @Test
        fun `it should obfuscate`() {
            val result = runWithSuccess("runMain", "runMainOriginal")
            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")
            assertMainJarExecutes(result, "io.specmatic.example.internal.fluxcapacitor")
        }

        @Test
        fun `it publish single fat jar without any dependencies declared in the pom to staging repository`() {
            runWithSuccess("publishAllPublicationsToStagingRepository", "publishToMavenLocal")

            val artifacts =
                arrayOf(
                    "io.specmatic.example:example-project-all-debug:1.2.3",
                    "io.specmatic.example:example-project:1.2.3",
                )

            assertPublished(*artifacts)
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

        @Test
        fun `it should create docker templates`() {
            runWithSuccess("dockerBuild", "createDockerFiles")

            assertThat(projectDir.resolve("build/Dockerfile").exists()).isTrue
            assertThat(projectDir.resolve("build/Dockerfile").readText().lines())
                .contains("ADD reports/cyclonedx/bom.json /usr/local/share/example-project/sbom.cyclonedx.json")
                .contains("ADD libs/example-project-1.2.3-all-obfuscated.jar /usr/local/share/example-project/example-project.jar")
                .contains("ADD example-project /usr/local/bin/example-project")
                .contains("""ENTRYPOINT ["/usr/local/bin/example-project"]""")

            assertThat(projectDir.resolve("build/example-project").exists()).isTrue
            assertThat(projectDir.resolve("build/example-project").readText().lines())
                .contains("""#!/usr/bin/env bash""")
                .contains("""exec java ${'$'}JAVA_OPTS -jar /usr/local/share/example-project/example-project.jar "${'$'}@"""")
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
                    publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                    publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    
                    withCommercialApplication(rootProject) {
                        mainClass = "io.specmatic.example.Main"
                        shadow("example")
                    }
                }
                
                tasks.register("runMain", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project/1.2.3/example-project-1.2.3.jar"))
                    mainClass = "io.specmatic.example.Main"
                }
                

                tasks.register("runMainOriginal", JavaExec::class.java) {
                    dependsOn("publishAllPublicationsToStagingRepository")
                    classpath(rootProject.file("build/mvn-repo/io/specmatic/example/example-project-all-debug/1.2.3/example-project-all-debug-1.2.3.jar"))
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
            )

        val allObfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:example-project:1.2.3",
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

            assertPublished(*allArtifacts)

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
                    publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                    publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    
                    withCommercialLibrary(project(":core")) {
                    }
                    
                    withCommercialApplication(project(":executable")) {
                        mainClass = "io.specmatic.example.executable.Main"
                        dockerBuild {
                            imageName = "specmatic-foo"
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
                        mainClass = "io.specmatic.example.executable.Main"
                    }
                    
                    tasks.register("runMainOriginal", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable-all-debug/1.2.3/executable-all-debug-1.2.3.jar"))
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
            writeLogbackXml(projectDir.resolve("executable"))
        }

        val obfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )

        val unobfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:core-all-debug:1.2.3",
                "io.specmatic.example:executable-all-debug:1.2.3",
            )

        val allArtifacts = obfuscatedArtifacts + unobfuscatedArtifacts

        @Test
        fun `it obfuscates and publishes jars`() {
            val result =
                runWithSuccess(
                    "publishAllPublicationsToStagingRepository",
                    "publishToMavenLocal",
                    "publishAllPublicationsToObfuscatedOnlyRepository",
                    "publishAllPublicationsToAllArtifactsRepository",
                    "runMain",
                    "runMainOriginal",
                )
            assertMainObfuscatedJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")
            assertMainJarExecutes(result, "io.specmatic.example.executable.internal.fluxcapacitor")

            assertPublished(*allArtifacts)

            assertThat(
                projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*obfuscatedArtifacts)

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
            assertThat(getDependencies("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3"))
                .containsExactlyInAnyOrder(
                    "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
                    "org.slf4j:slf4j-api:2.0.17",
                )

            // original jar should be larger than obfuscated jar
            assertThat(getJar("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3").length())
                .isGreaterThan(
                    getJar("io.specmatic.example:core-min:1.2.3").length(),
                )
            assertThat(getJar("io.specmatic.example:core-all-debug:1.2.3").length())
                .isGreaterThan(getJar("io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3").length())
            assertThat(getJar("io.specmatic.example:core-all-debug:1.2.3").length())
                .isGreaterThan(getJar("io.specmatic.example:core:1.2.3").length())

            assertThat(
                listJarContents("io.specmatic.example:executable-all-debug:1.2.3"),
            ).contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                .contains("io/specmatic/example/core/version.properties") // from the core dependency
                .contains("io/specmatic/example/executable/VersionInfo.class")
                .contains("io/specmatic/example/executable/version.properties")
                .contains("kotlin/Metadata.class") // kotlin is also packaged
                .contains("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .contains("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

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
                .contains("""exec java ${'$'}JAVA_OPTS -jar /usr/local/share/specmatic-foo/specmatic-foo.jar "${'$'}@"""")
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
                    publishTo("obfuscatedOnly", file("build/obfuscated-only").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                    publishTo("allArtifacts", file("build/all-artifacts").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    
                    withCommercialLibrary(project(":core")) {
                    }
                    
                    withCommercialApplication(project(":executable")) {
                        mainClass = "io.specmatic.example.executable.Main"
                        shadow("example")
                    }
                }
                
                project(":executable") {
                    dependencies {
                      implementation(project(":core"))
                    }

                    tasks.register("runMain", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable/1.2.3/executable-1.2.3.jar"))
                        mainClass = "io.specmatic.example.executable.Main"
                    }
                    
                    tasks.register("runMainOriginal", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath(rootProject.file("build/mvn-repo/io/specmatic/example/executable-all-debug/1.2.3/executable-all-debug-1.2.3.jar"))
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
            writeLogbackXml(projectDir.resolve("executable"))
        }

        val obfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:core-min:1.2.3",
            )

        val unobfuscatedArtifacts =
            arrayOf(
                "io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:core-all-debug:1.2.3",
                "io.specmatic.example:executable-all-debug:1.2.3",
            )

        val allArtifacts = obfuscatedArtifacts + unobfuscatedArtifacts

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

            assertPublished(*allArtifacts)

            assertThat(
                projectDir.resolve("build/obfuscated-only").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*obfuscatedArtifacts)

            assertThat(
                projectDir.resolve("build/all-artifacts").getPublishedArtifactCoordinates(),
            ).containsExactlyInAnyOrder(*allArtifacts)

            assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()
            assertThat(getDependencies("io.specmatic.example:core:1.2.3")).isEmpty()

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
