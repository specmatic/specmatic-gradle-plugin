package io.specmatic.gradle.promotion

import io.specmatic.gradle.utils.httpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Downloads external Maven artifacts")
abstract class DownloadPromotionMavenArtifactsTask : DefaultTask() {
    @get:Input
    abstract val maxParallelDownloads: Property<Int>

    @get:Input
    abstract val maxRetries: Property<Int>

    @get:Input
    abstract val canonicalRepository: Property<String>

    @get:Input
    abstract val artifactRelativePaths: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        maxParallelDownloads.convention(5)
        maxRetries.convention(3)
    }

    @TaskAction
    fun downloadArtifacts() {
        val baseUri = ensureTrailingSlash(canonicalRepository.get())
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val executor = Executors.newFixedThreadPool(maxParallelDownloads.get())

        try {
            val futures =
                artifactRelativePaths.get().map { relativePath ->
                    executor.submit(
                        Callable {
                            downloadWithRetries(
                                client = httpClient,
                                uri = "$baseUri$relativePath",
                                targetFile = outputDir.resolve(relativePath),
                                maxRetries = maxRetries.get(),
                            )
                        },
                    )
                }

            futures.forEach { future -> future.get(60, TimeUnit.SECONDS) }
        } catch (e: Exception) {
            throw GradleException("Failed to download promotion Maven artifacts", e)
        } finally {
            executor.shutdown()
        }
    }

    private fun ensureTrailingSlash(uri: String): String = if (uri.endsWith("/")) uri else "$uri/"

    private fun downloadWithRetries(client: OkHttpClient, uri: String, targetFile: File, maxRetries: Int) {
        targetFile.parentFile.mkdirs()

        var lastFailure: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                logger.lifecycle("Downloading $uri (attempt ${attempt + 1}/$maxRetries)")
                val request = Request.Builder().url(uri).build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        logger.lifecycle("Download failed for $uri (attempt ${attempt + 1}/$maxRetries)")
                        throw IOException("HTTP ${response.code} for $uri")
                    }

                    val body = response.body ?: throw IOException("Empty response body for $uri")
                    targetFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                return
            } catch (e: Exception) {
                targetFile.delete()
                lastFailure = e
            }
        }

        throw GradleException("Failed to download $uri after $maxRetries attempts", lastFailure)
    }
}
