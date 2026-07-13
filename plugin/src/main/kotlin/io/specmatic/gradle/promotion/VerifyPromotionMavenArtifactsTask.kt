package io.specmatic.gradle.promotion

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarFile
import org.apache.commons.codec.digest.DigestUtils
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Validates downloaded Maven artifacts")
abstract class VerifyPromotionMavenArtifactsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:Input
    abstract val expectedGitSha: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun verifyArtifacts() {
        val baseDir = inputDirectory.get().asFile
        verifyChecksums(baseDir)
        val pomFiles = baseDir.walkTopDown().filter { it.isFile && it.extension == "pom" }.toList()
        if (pomFiles.isEmpty()) {
            throw GradleException("No POM files found under ${baseDir.absolutePath}")
        }

        pomFiles.forEach { pomFile ->
            val model = pomFile.inputStream().use { MavenXpp3Reader().read(it) }
            val groupId = requireNotNull(model.groupId) { "POM ${pomFile.absolutePath} is missing groupId" }
            requireNotNull(model.version) { "POM ${pomFile.absolutePath} is missing version" }
            val properties = model.properties

            properties.expect("x-specmatic-version", expectedVersion.get(), pomFile)
            properties.expect("x-specmatic-group", groupId, pomFile)
            properties.expect("x-specmatic-git-sha", expectedGitSha.get(), pomFile)
            properties.expect("x-specmatic-git-short-sha", expectedGitSha.get().take(8).trim(), pomFile)
            properties.requirePresent("x-specmatic-artifact-type", pomFile)
            val stampedName =
                properties.getProperty("x-specmatic-name")
                    ?: throw GradleException("POM ${pomFile.absolutePath} is missing x-specmatic-name")

            pomFile.parentFile
                .listFiles()
                .orEmpty()
                .filter { candidate ->
                    candidate.isFile &&
                        candidate.extension == "jar" &&
                        !candidate.name.endsWith("-sources.jar") &&
                        !candidate.name.endsWith("-javadoc.jar")
                }.forEach { jarFile ->
                    verifyJarManifest(jarFile, stampedName, groupId)
                }
        }
    }

    private fun verifyJarManifest(jarFile: File, stampedName: String, groupId: String) {
        val attributes =
            JarFile(jarFile).use { jar ->
                jar.manifest?.mainAttributes ?: throw GradleException("Jar ${jarFile.absolutePath} has no manifest")
            }

        attributes.expect("x-specmatic-version", expectedVersion.get(), jarFile)
        attributes.expect("x-specmatic-group", groupId, jarFile)
        attributes.expect("x-specmatic-name", stampedName, jarFile)
        attributes.expect("x-specmatic-git-sha", expectedGitSha.get(), jarFile)
        attributes.expect("x-specmatic-git-short-sha", expectedGitSha.get().take(8).trim(), jarFile)
    }

    private fun verifyChecksums(baseDir: File) {
        baseDir
            .walkTopDown()
            .filter { it.isFile && !it.isChecksumFile() }
            .forEach { artifactFile ->
                CHECKSUM_ALGORITHMS.forEach { (extension, algorithm) ->
                    verifyChecksumFile(artifactFile, extension, algorithm)
                }
            }
    }

    private fun verifyChecksumFile(artifactFile: File, extension: String, algorithm: String) {
        val checksumFile = File("${artifactFile.absolutePath}.$extension")
        if (!checksumFile.isFile) {
            throw GradleException("Missing checksum file ${checksumFile.absolutePath}")
        }

        val expectedChecksum =
            checksumFile
                .readText()
                .trim()
                .substringBefore(' ')
                .substringBefore('\t')
        val actualChecksum = artifactFile.digest(algorithm)
        logger.lifecycle("Verifying $algorithm checksum of $artifactFile to be $expectedChecksum")
        if (!expectedChecksum.equals(actualChecksum, ignoreCase = true)) {
            throw GradleException(
                "Checksum mismatch for ${artifactFile.absolutePath} using $algorithm: expected $expectedChecksum, got $actualChecksum",
            )
        }
    }
}

private val CHECKSUM_ALGORITHMS =
    listOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha256" to "SHA-256",
        "sha512" to "SHA-512",
    )

private fun File.isChecksumFile(): Boolean =
    name.endsWith(".md5") || name.endsWith(".sha1") || name.endsWith(".sha256") || name.endsWith(".sha512")

internal fun File.digest(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    return DigestUtils(digest).digestAsHex(this)

//    inputStream().use { input ->
//        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
//        while (true) {
//            val read = input.read(buffer)
//            if (read <= 0) {
//                break
//            }
//            digest.update(buffer, 0, read)
//        }
//    }

//    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun Properties.expect(key: String, expected: String, file: File) {
    val actual = getProperty(key)
    if (actual != expected) {
        throw GradleException("File ${file.absolutePath} has $key=$actual, expected $expected")
    }
}

private fun Properties.requirePresent(key: String, file: File) {
    if (getProperty(key).isNullOrBlank()) {
        throw GradleException("POM ${file.absolutePath} is missing $key")
    }
}

private fun Attributes.expect(key: String, expected: String, file: File) {
    val actual = getValue(key)
    if (actual != expected) {
        throw GradleException("File ${file.absolutePath} has $key=$actual, expected $expected")
    }
}
