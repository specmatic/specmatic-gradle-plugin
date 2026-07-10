package io.specmatic.gradle.promotion

import io.specmatic.gradle.utils.httpClient
import java.io.File
import java.io.IOException
import java.net.URI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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

@DisableCachingByDefault(because = "Promotes downloaded Maven artifacts to remote repositories")
abstract class PromoteMavenArtifactsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:Input
    abstract val maxRetries: Property<Int>

    @get:Nested
    abstract val targets: ListProperty<PromotionMavenTargetInput>

    init {
        maxRetries.convention(3)
    }

    @TaskAction
    fun promoteArtifacts() {
        val inputDir = inputDirectory.get().asFile

        targets.get().forEach { target ->
            val repoName = target.repoName.get()
            val baseUrl = target.url.get()
            val username = target.username.orNull.orEmpty()
            val password = target.password.orNull.orEmpty()
            val artifactPaths = target.artifactPaths.get()
            logger.lifecycle("Promoting ${artifactPaths.size} Maven artifacts to $repoName at $baseUrl")
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
        }
    }

    private fun copyToFileRepository(baseUri: URI, relativePath: String, sourceFile: File) {
        val targetFile = File(baseUri).resolve(relativePath)
        targetFile.parentFile.mkdirs()
        logger.lifecycle("Copying ${sourceFile.absolutePath} to ${targetFile.absolutePath}")
        sourceFile.copyTo(targetFile, overwrite = true)
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
                logger.lifecycle("Upload failed for ${sourceFile.absolutePath} to $repoName (attempt ${attempt + 1}/$maxRetries): ${e.message}")
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

abstract class PromotionMavenTargetInput {
    @get:Input
    abstract val repoName: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val username: Property<String>

    @get:Input
    abstract val password: Property<String>

    @get:Input
    abstract val artifactPaths: ListProperty<String>
}
