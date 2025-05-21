package io.specmatic.gradle.features

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OSSApplicationFeatureTest : AbstractFunctionalTest() {
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
                        withOSSApplication(rootProject) {
                            mainClass = "io.specmatic.example.Main"
                            dockerBuild()
                        }
                    }
                    
                    tasks.register("runMain", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath("build/mvn-repo/io/specmatic/example/example-project/1.2.3/example-project-1.2.3.jar")
                        mainClass = "io.specmatic.example.Main"
                    }
                """.trimIndent()

            )

            writeMainClass(projectDir, "io.specmatic.example.Main")
            writeLogbackXml(projectDir)
        }

        @Test
        fun `it publish single fat jar without any dependencies declared in the pom to staging repository`() {
            val result = runWithSuccess("publishAllPublicationsToStagingRepository", "runMain", "publishToMavenLocal")
            assertMainJarExecutes(result)

            assertPublishedWithJavadocAndSources("io.specmatic.example:example-project:1.2.3")
            assertThat(getDependencies("io.specmatic.example:example-project:1.2.3")).isEmpty()

            assertThat(
                listJarContents("io.specmatic.example:example-project:1.2.3"))
                .contains("io/specmatic/example/VersionInfo.class")
                .contains("io/specmatic/example/version.properties")
                .contains("kotlin/Metadata.class") // kotlin is also packaged
                .contains("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .contains("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:example-project:1.2.3")).isEqualTo(
                "io.specmatic.example.Main"
            )
        }

        @Test
        fun `it should create docker templates`() {
            runWithSuccess("dockerBuild", "createDockerFiles")

            assertThat(projectDir.resolve("build/Dockerfile").exists()).isTrue
            assertThat(projectDir.resolve("build/Dockerfile").readText().lines())
                .contains("ADD libs/example-project-1.2.3-all-unobfuscated.jar /usr/local/share/example-project.jar")
                .contains("ADD example-project /usr/local/bin/example-project")
                .contains("""ENTRYPOINT ["/usr/local/bin/example-project"]""")

            assertThat(projectDir.resolve("build/example-project").exists()).isTrue
            assertThat(projectDir.resolve("build/example-project").readText().lines())
                .contains("""#!/usr/bin/env bash""")
                .contains("""exec java ${'$'}JAVA_OPTS -jar /usr/local/share/example-project.jar "${'$'}@"""")
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
                        withOSSApplication(rootProject) {
                            mainClass = "io.specmatic.example.Main"
                            shadow("example")
                        }
                    }
                    
                    tasks.register("runMain", JavaExec::class.java) {
                        dependsOn("publishAllPublicationsToStagingRepository")
                        classpath("build/mvn-repo/io/specmatic/example/example-project/1.2.3/example-project-1.2.3.jar")
                        mainClass = "io.specmatic.example.Main"
                    }
                """.trimIndent()
            )

            writeMainClass(projectDir, "io.specmatic.example.Main")
            writeLogbackXml(projectDir)
        }

        @Test
        fun `it publish single fat jar without any dependencies declared in the pom to staging repository`() {
            val result = runWithSuccess("publishAllPublicationsToStagingRepository", "runMain", "publishToMavenLocal")
            assertMainJarExecutes(result)

            assertPublishedWithJavadocAndSources("io.specmatic.example:example-project:1.2.3")
            assertThat(getDependencies("io.specmatic.example:example-project:1.2.3")).isEmpty()

            assertThat(
                listJarContents("io.specmatic.example:example-project:1.2.3"))
                .contains("io/specmatic/example/VersionInfo.class")
                .contains("io/specmatic/example/version.properties")
                .contains("example/kotlin/Metadata.class") // kotlin is also packaged
                .contains("example/org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .contains("example/org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .contains("example/org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:example-project:1.2.3")).isEqualTo(
                "io.specmatic.example.Main"
            )
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
                """.trimIndent()
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
                        withOSSLibrary(project(":core")) {
                        }
                        
                        withOSSApplication(project("executable")) {
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
                    }
                    
                """.trimIndent()
            )

            writeMainClass(projectDir.resolve("executable"), "io.specmatic.example.executable.Main")
            writeLogbackXml(projectDir.resolve("executable"))
        }

        @Test
        fun `it publish single fat jar for executable with no deps, and core jar with dependencies`() {
            val result = runWithSuccess("publishAllPublicationsToStagingRepository", "runMain", "publishToMavenLocal")
            assertMainJarExecutes(result)

            assertPublishedWithJavadocAndSources(
                "io.specmatic.example:executable:1.2.3", "io.specmatic.example:core:1.2.3"
            )

            assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()
            assertThat(getDependencies("io.specmatic.example:core:1.2.3")).containsExactlyInAnyOrder(
                "org.jetbrains.kotlin:kotlin-stdlib:1.9.25", "org.slf4j:slf4j-api:2.0.17"
            )

            assertThat(
                listJarContents("io.specmatic.example:executable:1.2.3"))
                .contains("io/specmatic/example/core/VersionInfo.class") // from the core dependency
                .contains("io/specmatic/example/core/version.properties") // from the core dependency
                .contains("io/specmatic/example/executable/VersionInfo.class")
                .contains("io/specmatic/example/executable/version.properties")
                .contains("kotlin/Metadata.class") // kotlin is also packaged
                .contains("org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .contains("org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .contains("org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isEqualTo(
                "io.specmatic.example.executable.Main"
            )
        }

        @Test
        fun `it should create docker templates`() {
            runWithSuccess("dockerBuild", "createDockerFiles")

            assertThat(projectDir.resolve("executable/build/Dockerfile").exists()).isTrue
            assertThat(projectDir.resolve("executable/build/Dockerfile").readText().lines())
                .contains("ADD libs/executable-1.2.3-all-unobfuscated.jar /usr/local/share/specmatic-foo.jar")
                .contains("ADD specmatic-foo /usr/local/bin/specmatic-foo")
                .contains("""ENTRYPOINT ["/usr/local/bin/specmatic-foo"]""")

            assertThat(projectDir.resolve("executable/build/specmatic-foo").exists()).isTrue
            assertThat(projectDir.resolve("executable/build/specmatic-foo").readText().lines())
                .contains("""#!/usr/bin/env bash""")
                .contains("""exec java ${'$'}JAVA_OPTS -jar /usr/local/share/specmatic-foo.jar "${'$'}@"""")
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
            """.trimIndent()
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
                        withOSSLibrary(project(":core")) {
                        }
                        
                        withOSSApplication(project("executable")) {
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
                    }
                    
                """.trimIndent()
            )

            writeMainClass(projectDir.resolve("executable"), "io.specmatic.example.executable.Main")
            writeLogbackXml(projectDir.resolve("executable"))
        }

        @Test
        fun `it publish single fat jar for executable with no deps, and core jar with dependencies`() {
            val result = runWithSuccess("publishAllPublicationsToStagingRepository", "runMain", "publishToMavenLocal")
            assertMainJarExecutes(result)

            assertPublishedWithJavadocAndSources(
                "io.specmatic.example:executable:1.2.3", "io.specmatic.example:core:1.2.3"
            )

            assertThat(getDependencies("io.specmatic.example:executable:1.2.3")).isEmpty()
            assertThat(getDependencies("io.specmatic.example:core:1.2.3")).containsExactlyInAnyOrder(
                "org.jetbrains.kotlin:kotlin-stdlib:1.9.25", "org.slf4j:slf4j-api:2.0.17"
            )

            assertThat(
                listJarContents("io.specmatic.example:executable:1.2.3"))
                .contains("example/io/specmatic/example/core/VersionInfo.class") // from the core dependency
                .contains("example/io/specmatic/example/core/version.properties") // from the core dependency
                .contains("io/specmatic/example/executable/VersionInfo.class")
                .contains("io/specmatic/example/executable/version.properties")
                .contains("example/kotlin/Metadata.class") // kotlin is also packaged
                .contains("example/org/jetbrains/annotations/Contract.class") // kotlin is also packaged
                .contains("example/org/intellij/lang/annotations/Language.class") // kotlin is also packaged
                .contains("example/org/slf4j/Logger.class") // slf4j dependency is also packaged

            assertThat(mainClass("io.specmatic.example:executable:1.2.3")).isEqualTo(
                "io.specmatic.example.executable.Main"
            )
        }
    }
}
