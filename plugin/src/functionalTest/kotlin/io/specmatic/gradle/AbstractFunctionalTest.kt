package io.specmatic.gradle

import io.specmatic.gradle.release.execGit
import org.assertj.core.api.Assertions.assertThat
import org.gradle.internal.impldep.org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.util.jar.JarFile

open class AbstractFunctionalTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var projectDir: File
    protected val buildFile by lazy { projectDir.resolve("build.gradle.kts") }
    protected val settingsFile by lazy { projectDir.resolve("settings.gradle.kts") }
    protected val gradleProperties by lazy { projectDir.resolve("gradle.properties") }
    protected val stagingRepo by lazy { projectDir.resolve("build/mvn-repo") }
    protected val localMavenRepo by lazy { projectDir.resolve("build/m2/repository") }

    protected val loggingDependencies = arrayOf(
        "ch.qos.logback:logback-classic:1.5.18",
        "ch.qos.logback:logback-core:1.5.18",
        "org.apache.logging.log4j:log4j-to-slf4j:2.24.3",
        "org.slf4j:jcl-over-slf4j:2.0.17",
        "org.slf4j:jul-to-slf4j:2.0.17",
        "org.slf4j:log4j-over-slf4j:2.0.17",
    )

    @BeforeEach
    fun baseSetup() {
        projectDir.execGit(LoggerFactory.getLogger("test"), "init")
        projectDir.resolve(".gitignore").writeText(
            """
            build
            .gradle
            """.trimIndent()
        )
        gradleProperties.writeText(
            """
                version=${projectVersion()}
                group=io.specmatic.example
            """.trimIndent()
        )

        settingsFile.writeText(
            """
                rootProject.name = "example-project"
            """.trimIndent()
        )
    }

    fun writeLogbackXml(projectDir: File) {
        projectDir.resolve("src/main/resources/logback-test.xml").also {
            it.parentFile.mkdirs()
            it.writeText(
                """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <!DOCTYPE configuration>
                    <configuration>
                      <import class="ch.qos.logback.classic.encoder.PatternLayoutEncoder"/>
                      <import class="ch.qos.logback.core.ConsoleAppender"/>
                    
                      <appender name="STDOUT" class="ConsoleAppender">
                        <encoder class="PatternLayoutEncoder">
                          <pattern>[this will only show via logback] %-5level %logger{36} -%kvp- %msg%n</pattern>
                        </encoder>
                      </appender>
                    
                      <root level="debug">
                        <appender-ref ref="STDOUT"/>
                      </root>
                    </configuration>                
                """.trimIndent()
            )
        }
    }

    fun writeRandomClasses(projectDir: File, packageName: String) {
        repeat(5) { i ->
            val className = "Class${i + 1}"
            val fileName = "$className.kt"
            val previousClassName = if (i > 0) "Class$i" else null
            projectDir.resolve("src/main/kotlin/${packageName.replace(".", "/")}/$fileName").also {
                it.parentFile.mkdirs()
                it.writeText(
                    """
                        package $packageName
                        
                        object $className {
                            @JvmStatic
                            fun sayHello() {
                                val stackTraceElement = Thread.currentThread().stackTrace[1]
                                println("Hello from " + stackTraceElement.className + "#" + stackTraceElement.methodName)
                                ${previousClassName?.let { "$previousClassName.sayHello()" } ?: ""}
                            }

                        }
                    """.trimIndent())
            }
        }
    }

    fun writeMainClass(projectDir: File, mainClass: String, innerPackage: String? = null) {
        val fileName = mainClass.replace(".", "/") + ".kt"
        val packageName = mainClass.substringBeforeLast(".")
        projectDir.resolve("src/main/kotlin/${fileName}").also {

            val callInnerPackageCode = if (innerPackage.orEmpty().isNotEmpty())
                """
                    println("Calling inner package")
                    $innerPackage.Class5.sayHello()
                """.trimIndent()
            else
                ""
            it.parentFile.mkdirs()
            it.writeText(
                """
                    package $packageName
                    
                    object Main {
                        @JvmStatic
                        fun main(args: Array<String>) {
                            // initialize an slf4j logger
                            // this is to ensure that obfuscation/shadowing etc work fine
                            val logger = org.slf4j.LoggerFactory.getLogger("slf4j")
                            logger.info("Hello, logger! Version: " + VersionInfo.describe())
                            println("Logger class is " + logger.javaClass)
                            
                            // this should only print if the above works
                            println("Hello, world! Version: " + VersionInfo.describe())

                            $callInnerPackageCode
                        }
                    }
                """.trimIndent()
            )
        }
    }

    fun runWithSuccess(vararg gradleRunArgs: String): BuildResult {
        return createRunner(gradleRunArgs).build()
    }

    fun runWithFailure(vararg gradleRunArgs: String): BuildResult {
        return createRunner(gradleRunArgs).buildAndFail()
    }

    private fun createRunner(gradleRunArgs: Array<out String>): GradleRunner =
        GradleRunner.create().forwardOutput().withPluginClasspath().withProjectDir(projectDir)
            .withArguments(
                *gradleRunArgs,
                "-Dmaven.repo.local=${localMavenRepo}",
                "--stacktrace",
//                "-d"
//                "-Dorg.gradle.debug=true"
            )

    fun assertMainJarExecutes(result: BuildResult, innerPackage: String? = null) {
        assertThat(result.output).contains("Hello, world! Version: v${projectVersion()}")
        assertThat(result.output).containsAnyOf(
            "[this will only show via logback] INFO  slf4j -- Hello, logger! Version: v${projectVersion()}",
            "No SLF4J providers were found"
        )

        if (!innerPackage.isNullOrBlank()) {
            val expectedLines = result.output.lines()
                .filter { it.startsWith("Hello from $innerPackage") && it.contains(".internal.") }
            assertThat(expectedLines).containsExactly(
                "Hello from $innerPackage.Class5#sayHello",
                "Hello from $innerPackage.Class4#sayHello",
                "Hello from $innerPackage.Class3#sayHello",
                "Hello from $innerPackage.Class2#sayHello",
                "Hello from $innerPackage.Class1#sayHello",
            )
        }
        assertThat(result.output).contains("BUILD SUCCESSFUL")
    }

    fun assertMainObfuscatedJarExecutes(result: BuildResult, innerPackage: String? = null) {
        assertThat(result.output).contains("Hello, world! Version: v${projectVersion()}")
        assertThat(result.output).containsAnyOf(
            "[this will only show via logback] INFO  slf4j -- Hello, logger! Version: v${projectVersion()}",
            "No SLF4J providers were found"
        )

        if (!innerPackage.isNullOrBlank()) {
            val packageComponents = innerPackage.split(".internal.")
            val expectedLines = result.output.lines()
                .filter { it.startsWith("Hello from ${packageComponents[0]}") && !it.contains(".internal.") }
            assertThat(expectedLines)
                .containsExactlyInAnyOrder(
                    "Hello from ${packageComponents[0]}.a.a.e#a",
                    "Hello from ${packageComponents[0]}.a.a.d#a",
                    "Hello from ${packageComponents[0]}.a.a.c#a",
                    "Hello from ${packageComponents[0]}.a.a.b#a",
                    "Hello from ${packageComponents[0]}.a.a.a#a"
                )

        }
        assertThat(result.output).contains("BUILD SUCCESSFUL")
    }

    open fun projectVersion(): String = "1.2.3"

    private fun openJar(coordinates: String): JarFile = JarFile(getJar(coordinates))

    fun mainClass(coordinates: String): String? =
        openJar(coordinates).use { it.manifest.mainAttributes.getValue("Main-Class") }

    fun mainClass(file: File): String? = JarFile(file).use { it.manifest.mainAttributes.getValue("Main-Class") }

    fun listJarContents(coordinates: String): List<String> {
        val jarFile = openJar(coordinates)
        return jarFile.use { jarFile.entries().toList().map { it.name } }
    }

    fun getJar(coordinates: String): File =
        artifactDir(coordinates).resolve("${coordinates.artifactId()}-${coordinates.version()}.jar")

    fun getDependencies(coordinates: String): Set<String> {
        val publishedPomFiles = stagingRepo.walk().filter { it.extension == "pom" }

        val pomFiles = publishedPomFiles.filter {
            val model = it.inputStream().use { MavenXpp3Reader().read(it) }
            "${model.groupId}:${model.artifactId}:${model.version}" == coordinates
        }

        if (pomFiles.toSet().size != 1) {
            val stagingRepoPublishedPackages = getPublishedArtifactCoordinates(stagingRepo).joinToString(", ")
            val localMavenRepoPublishedPackages = getPublishedArtifactCoordinates(localMavenRepo).joinToString(", ")

            throw AssertionError(
                "Artifact $coordinates not found. Published artifacts in staging repo $stagingRepoPublishedPackages. Published artifacts in local maven repo $localMavenRepoPublishedPackages"
            )
        }

        val pomFile = pomFiles.single()

        val model = pomFile.inputStream().use { MavenXpp3Reader().read(it) }
        return model.dependencies.map {
            if (it.version.isNullOrBlank()) {
                "${it.groupId}:${it.artifactId}"
            } else {
                "${it.groupId}:${it.artifactId}:${it.version}"
            }
        }
            .toSet()
    }

    private fun getPublishedArtifactCoordinates(mavenRepo: File): Set<String> {
        val publishedPomFiles = mavenRepo.walk().filter { it.extension == "pom" }

        val publishedArtifacts = publishedPomFiles.map { pomFile ->
            val model = pomFile.inputStream().use { MavenXpp3Reader().read(it) }
            "${model.groupId}:${model.artifactId}:${model.version}"
        }

        return publishedArtifacts.toSet()
    }

    fun assertPublished(vararg coordinates: String) {
        assertThat(getPublishedArtifactCoordinates(stagingRepo)).containsExactlyInAnyOrder(*coordinates)
        assertThat(getPublishedArtifactCoordinates(localMavenRepo)).containsExactlyInAnyOrder(*coordinates)
        coordinates.forEach {
            val groupId = it.groupId()
            val artifactId = it.artifactId()
            val version = it.version()

            val jarFiles = artifactDir(groupId, artifactId, version).listFiles { file ->
                file.extension == "jar" && !file.name.contains("-sources") && !file.name.contains("-javadoc")
            }
            assertThat(jarFiles).containsExactly(
                artifactDir(groupId, artifactId, version).resolve("${artifactId}-${version}.jar")
            )
            assertThat(artifactDir(groupId, artifactId, version).resolve("${artifactId}-${version}.pom")).exists()
        }
    }

    fun assertPublishedWithJavadocAndSources(vararg coordinates: String) {
        assertThat(getPublishedArtifactCoordinates(stagingRepo)).containsExactlyInAnyOrder(*coordinates)
        assertThat(getPublishedArtifactCoordinates(localMavenRepo)).containsExactlyInAnyOrder(*coordinates)
        coordinates.forEach {
            val groupId = it.groupId()
            val artifactId = it.artifactId()
            val version = it.version()

            val jarFiles = artifactDir(groupId, artifactId, version).listFiles { file -> file.extension == "jar" }

            assertThat(artifactDir(groupId, artifactId, version).resolve("${artifactId}-${version}.pom")).exists()
            assertThat(jarFiles).containsExactlyInAnyOrder(
                artifactDir(groupId, artifactId, version).resolve("${artifactId}-${version}.jar"),
                artifactDir(groupId, artifactId, version).resolve("${artifactId}-${version}-javadoc.jar"),
                artifactDir(groupId, artifactId, version).resolve("${artifactId}-${version}-sources.jar"),
            )
        }
    }

    fun assertNothingPublished() {
        assertThat(getPublishedArtifactCoordinates(stagingRepo)).isEmpty()
        assertThat(getPublishedArtifactCoordinates(localMavenRepo)).isEmpty()
    }

    private fun artifactDir(groupId: String, artifactId: String, version: String): File {
        val groupPath = groupId.replace(".", "/")
        val namePath = artifactId.replace(".", "/")
        return stagingRepo.resolve("$groupPath/$namePath/$version")
    }

    fun artifactDir(coordinates: String): File {
        return artifactDir(coordinates.groupId(), coordinates.artifactId(), coordinates.version())
    }

    private fun String.version() = split(":").get(2)
    private fun String.artifactId() = split(":").get(1)
    private fun String.groupId() = split(":").get(0)
}
