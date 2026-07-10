package io.specmatic.gradle.promotion

import com.vanniktech.maven.publish.portal.SonatypeCentralPortal
import io.specmatic.gradle.plugin.VersionInfo
import io.specmatic.gradle.utils.httpClient
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.Base64
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.apache.maven.artifact.repository.metadata.Metadata
import org.apache.maven.artifact.repository.metadata.Versioning
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.slf4j.LoggerFactory

@DisableCachingByDefault(because = "Promotes downloaded Maven artifacts to remote repositories")
abstract class PromoteMavenArtifactsTask : DefaultTask() {
    @get:Input
    abstract val automaticMavenCentralRelease: Property<Boolean>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:Input
    abstract val maxRetries: Property<Int>

    @get:Nested
    abstract val targets: ListProperty<PromotionMavenTargetInput>

    init {
        automaticMavenCentralRelease.convention(true)
        maxRetries.convention(3)
    }

    @TaskAction
    fun promoteArtifacts() {
        val inputDir = inputDirectory.get().asFile

        targets.get().forEach { target ->
            val repoName = target.repoName.get()
            val kind = PromotionMavenTargetKind.valueOf(target.kind.get())
            val baseUrl = target.url.get()
            val username = target.username.orNull.orEmpty()
            val password = target.password.orNull.orEmpty()
            val artifactPaths = target.artifactPaths.get()
            logger.lifecycle("Promoting ${artifactPaths.size} Maven artifacts to $repoName at $baseUrl")
            when (kind) {
                PromotionMavenTargetKind.REPOSITORY -> {
                    promoteToRepository(inputDir, baseUrl, username, password, repoName, artifactPaths)
                }

                PromotionMavenTargetKind.MAVEN_CENTRAL -> {
                    promoteToMavenCentral(inputDir, baseUrl, username, password, repoName, artifactPaths)
                }
            }
        }
    }

    private fun promoteToRepository(
        inputDir: File,
        baseUrl: String,
        username: String,
        password: String,
        repoName: String,
        artifactPaths: List<String>,
    ) {
        artifactPaths.forEach { relativePath ->
            val sourceFile = inputDir.resolve(relativePath)
            if (!sourceFile.isFile) {
                throw GradleException("Missing staged artifact ${sourceFile.absolutePath} for repository $repoName")
            }

            val baseUri = URI.create(baseUrl)
            if (baseUri.scheme == "file") {
                copyToFileRepository(baseUri, relativePath, sourceFile)
            } else {
                uploadToHttpRepositoryWithRetries(
                    client = httpClient,
                    baseUrl = ensureTrailingSlash(baseUrl),
                    relativePath = relativePath,
                    sourceFile = sourceFile,
                    username = username,
                    password = password,
                    repoName = repoName,
                    maxRetries = maxRetries.get(),
                )
            }
        }

        publishRepositoryMetadata(inputDir, baseUrl, username, password, repoName, artifactPaths)
    }

    private fun promoteToMavenCentral(
        inputDir: File,
        baseUrl: String,
        username: String,
        password: String,
        repoName: String,
        artifactPaths: List<String>,
    ) {
        if (username.isBlank() || password.isBlank()) {
            throw GradleException(
                "Missing credentials for repository $repoName. Expected Gradle properties ${repoName}Username and ${repoName}Password",
            )
        }

        val zipFile = createMavenCentralBundle(inputDir, repoName, artifactPaths)
        val portal =
            SonatypeCentralPortal(
                baseUrl = baseUrl,
                usertoken = Base64.getEncoder().encodeToString("$username:$password".toByteArray()),
                userAgentName = "specmatic-gradle-plugin",
                userAgentVersion = VersionInfo.version,
                okhttpTimeoutSeconds = 30,
                closeTimeoutSeconds = 600,
                pollIntervalMs = 5_000,
                logger = LoggerFactory.getLogger(PromoteMavenArtifactsTask::class.java),
            )
        val publishingType =
            if (automaticMavenCentralRelease.get()) {
                SonatypeCentralPortal.PublishingType.AUTOMATIC
            } else {
                SonatypeCentralPortal.PublishingType.USER_MANAGED
            }

        logger.lifecycle("Uploading Maven Central bundle ${zipFile.absolutePath} to $repoName using $publishingType")
        val deploymentId = portal.upload(zipFile.nameWithoutExtension, publishingType, zipFile)
        logger.lifecycle("Created Maven Central deployment $deploymentId for $repoName")
        portal.validateDeployment(deploymentId, automaticMavenCentralRelease.get())
    }

    private fun createMavenCentralBundle(inputDir: File, repoName: String, artifactPaths: List<String>): File {
        val zipFile = temporaryDir.resolve("$repoName-${System.currentTimeMillis()}.zip")
        zipFile.parentFile.mkdirs()

        ZipOutputStream(zipFile.outputStream()).use { output ->
            artifactPaths.forEach { relativePath ->
                val sourceFile = inputDir.resolve(relativePath)
                if (!sourceFile.isFile) {
                    throw GradleException("Missing staged artifact ${sourceFile.absolutePath} for repository $repoName")
                }

                logger.lifecycle("Adding ${sourceFile.absolutePath} to Maven Central bundle as $relativePath")
                output.putNextEntry(ZipEntry(relativePath))
                sourceFile.inputStream().use { input -> input.copyTo(output) }
                output.closeEntry()
            }
        }

        return zipFile
    }

    private fun copyToFileRepository(baseUri: URI, relativePath: String, sourceFile: File) {
        val targetFile = File(baseUri).resolve(relativePath)
        targetFile.parentFile.mkdirs()
        logger.lifecycle("Copying ${sourceFile.absolutePath} to ${targetFile.absolutePath}")
        sourceFile.copyTo(targetFile, overwrite = true)
    }

    private fun publishRepositoryMetadata(
        inputDir: File,
        baseUrl: String,
        username: String,
        password: String,
        repoName: String,
        artifactPaths: List<String>,
    ) {
        releaseMetadataEntries(artifactPaths).forEach { entry ->
            val metadata = fetchExistingMetadata(baseUrl, username, password, entry.metadataPath)
            val mergedMetadata = mergeReleaseMetadata(metadata, entry)
            val metadataFile = writeMetadataFile(repoName, entry, mergedMetadata)
            val baseUri = URI.create(baseUrl)

            if (baseUri.scheme == "file") {
                copyToFileRepository(baseUri, entry.metadataPath, metadataFile)
                uploadChecksumSidecarsToFileRepository(baseUri, entry.metadataPath, metadataFile)
            } else {
                uploadToHttpRepositoryWithRetries(
                    client = httpClient,
                    baseUrl = ensureTrailingSlash(baseUrl),
                    relativePath = entry.metadataPath,
                    sourceFile = metadataFile,
                    username = username,
                    password = password,
                    repoName = repoName,
                    maxRetries = maxRetries.get(),
                )
                uploadChecksumSidecarsToHttpRepository(baseUrl, username, password, repoName, entry.metadataPath, metadataFile)
            }
        }
    }

    private fun uploadChecksumSidecarsToFileRepository(baseUri: URI, metadataPath: String, metadataFile: File) {
        CHECKSUM_EXTENSIONS.forEach { extension ->
            val checksumFile = writeChecksumFile(metadataFile, extension)
            copyToFileRepository(baseUri, "$metadataPath.$extension", checksumFile)
        }
    }

    private fun uploadChecksumSidecarsToHttpRepository(
        baseUrl: String,
        username: String,
        password: String,
        repoName: String,
        metadataPath: String,
        metadataFile: File,
    ) {
        CHECKSUM_EXTENSIONS.forEach { extension ->
            val checksumFile = writeChecksumFile(metadataFile, extension)
            uploadToHttpRepositoryWithRetries(
                client = httpClient,
                baseUrl = ensureTrailingSlash(baseUrl),
                relativePath = "$metadataPath.$extension",
                sourceFile = checksumFile,
                username = username,
                password = password,
                repoName = repoName,
                maxRetries = maxRetries.get(),
            )
        }
    }

    private fun fetchExistingMetadata(baseUrl: String, username: String, password: String, metadataPath: String): Metadata? {
        val baseUri = URI.create(baseUrl)
        return if (baseUri.scheme == "file") {
            val metadataFile = File(baseUri).resolve(metadataPath)
            if (metadataFile.isFile) {
                metadataFile.reader().use { MetadataXpp3Reader().read(it) }
            } else {
                null
            }
        } else {
            fetchExistingMetadataOverHttp(baseUrl, username, password, metadataPath)
        }
    }

    private fun fetchExistingMetadataOverHttp(baseUrl: String, username: String, password: String, metadataPath: String): Metadata? {
        val request =
            Request.Builder()
                .url("${ensureTrailingSlash(baseUrl)}$metadataPath")
                .get()
                .header("Authorization", okhttp3.Credentials.basic(username, password))
                .build()

        httpClient.newCall(request).execute().use { response ->
            return when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("Failed to fetch $metadataPath with HTTP ${response.code}")
                else -> {
                    val body = response.body.string()
                    MetadataXpp3Reader().read(StringReader(body))
                }
            }
        }
    }

    private fun mergeReleaseMetadata(existingMetadata: Metadata?, entry: ReleaseMetadataEntry): Metadata {
        val metadata = existingMetadata ?: Metadata()
        metadata.groupId = entry.groupId
        metadata.artifactId = entry.artifactId

        val versioning = metadata.versioning ?: Versioning().also { metadata.versioning = it }
        val versions = linkedSetOf<String>()
        versions.addAll(versioning.versions.orEmpty())
        versions.add(entry.version)

        versioning.versions = versions.toList()
        versioning.latest = entry.version
        versioning.release = entry.version
        versioning.lastUpdated = LAST_UPDATED_FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC))

        return metadata
    }

    private fun writeMetadataFile(repoName: String, entry: ReleaseMetadataEntry, metadata: Metadata): File {
        val metadataFile = temporaryDir.resolve("$repoName-${entry.artifactId}-maven-metadata.xml")
        metadataFile.parentFile.mkdirs()
        metadataFile.writer().use { MetadataXpp3Writer().write(it, metadata) }
        return metadataFile
    }

    private fun writeChecksumFile(sourceFile: File, extension: String): File {
        val checksumFile = File("${sourceFile.absolutePath}.$extension")
        val algorithm = CHECKSUM_ALGORITHMS.getValue(extension)
        checksumFile.writeText(sourceFile.digest(algorithm))
        return checksumFile
    }

    private fun uploadToHttpRepositoryWithRetries(
        client: OkHttpClient,
        baseUrl: String,
        relativePath: String,
        sourceFile: File,
        username: String,
        password: String,
        repoName: String,
        maxRetries: Int,
    ) {
        if (username.isBlank() || password.isBlank()) {
            throw GradleException(
                "Missing credentials for repository $repoName. Expected Gradle properties ${repoName}Username and ${repoName}Password",
            )
        }

        var lastFailure: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                logger.lifecycle("Uploading ${sourceFile.absolutePath} to $repoName as $relativePath (attempt ${attempt + 1}/$maxRetries)")
                uploadToHttpRepository(
                    client = client,
                    baseUrl = baseUrl,
                    relativePath = relativePath,
                    sourceFile = sourceFile,
                    username = username,
                    password = password,
                    repoName = repoName,
                )
                return
            } catch (e: Exception) {
                logger.lifecycle(
                    "Upload failed for ${sourceFile.absolutePath} to $repoName (attempt ${attempt + 1}/$maxRetries): ${e.message}"
                )
                lastFailure = e
            }
        }

        throw GradleException("Failed to upload ${sourceFile.absolutePath} to $repoName after $maxRetries attempts", lastFailure)
    }

    private fun uploadToHttpRepository(
        client: OkHttpClient,
        baseUrl: String,
        relativePath: String,
        sourceFile: File,
        username: String,
        password: String,
        repoName: String,
    ) {
        val request =
            Request
                .Builder()
                .url("$baseUrl$relativePath")
                .put(sourceFile.asRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization", okhttp3.Credentials.basic(username, password))
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Upload failed for ${sourceFile.name} to $repoName with HTTP ${response.code}")
            }
            logger.lifecycle("Uploaded ${sourceFile.absolutePath} to $repoName as $relativePath")
        }
    }

    private fun ensureTrailingSlash(uri: String): String = if (uri.endsWith("/")) uri else "$uri/"
}

private val LAST_UPDATED_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
private val CHECKSUM_ALGORITHMS = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
private val CHECKSUM_EXTENSIONS = CHECKSUM_ALGORITHMS.keys.toList()

private data class ReleaseMetadataEntry(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val metadataPath: String,
)

private fun releaseMetadataEntries(artifactPaths: List<String>): List<ReleaseMetadataEntry> =
    artifactPaths
        .asSequence()
        .filter { it.endsWith(".pom") }
        .mapNotNull { path ->
            val segments = path.split("/")
            if (segments.size < 4) {
                return@mapNotNull null
            }

            val version = segments[segments.lastIndex - 1]
            val artifactId = segments[segments.lastIndex - 2]
            val groupSegments = segments.dropLast(3)
            if (groupSegments.isEmpty()) {
                return@mapNotNull null
            }

            ReleaseMetadataEntry(
                groupId = groupSegments.joinToString("."),
                artifactId = artifactId,
                version = version,
                metadataPath = "${groupSegments.joinToString("/")}/$artifactId/maven-metadata.xml",
            )
        }.distinct()
        .toList()

abstract class PromotionMavenTargetInput {
    @get:Input
    abstract val repoName: Property<String>

    @get:Input
    abstract val kind: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val username: Property<String>

    @get:Input
    abstract val password: Property<String>

    @get:Input
    abstract val artifactPaths: ListProperty<String>
}

enum class PromotionMavenTargetKind {
    REPOSITORY,
    MAVEN_CENTRAL,
}
