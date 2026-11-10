package io.specmatic.gradle.docker

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.specmatic.gradle.utils.httpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.Task
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@org.gradle.work.DisableCachingByDefault(because = "Makes network calls")
abstract class UpdateDockerHubOverviewTask : DefaultTask() {
    @get:Input
    abstract val dockerHubUsername: Property<String>

    @get:Input
    abstract val dockerHubApiToken: Property<String>

    @get:Input
    abstract val repositoryName: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val readmeFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val readmeContent: Property<String>

    private fun mapper(): ObjectMapper = ObjectMapper().registerKotlinModule()

    init {
        group = "docker"
        description = "Updates the DockerHub overview page"
    }

    @TaskAction
    fun updateOverview() {
        if (repositoryName.get().startsWith("ghcr.io")) {
            logger.warn("Skipping DockerHub overview update for repository: ${repositoryName.get()}")
            return
        }
        val jwtToken = fetchJwtToken(dockerHubUsername.get(), dockerHubApiToken.get())
        updateDockerHubOverview(jwtToken)
    }

    private fun fetchJwtToken(username: String, apiToken: String): String {
        logger.warn("Fetching JWT token for DockerHub user: $username")
        val url = "https://hub.docker.com/v2/users/login/"
        val payload = mapper().writeValueAsString(mapOf("username" to username, "password" to apiToken))
        val requestBody = payload.toRequestBody("application/json".toMediaType())

        val request =
            Request
                .Builder()
                .url(url)
                .post(requestBody)
                .build()

        return withRetries("fetch DockerHub JWT token") {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 429 || response.code >= 500) {
                    throw RetryableHttpException("DockerHub returned HTTP ${response.code}")
                }
                if (!response.isSuccessful) {
                    error("Failed to fetch JWT token: ${response.message}")
                }
                val responseBody = response.body.string().ifBlank { error("Empty response body") }
                val jsonNode = mapper().readTree(responseBody)
                jsonNode["token"]?.asText() ?: error("JWT token not found in response")
            }
        }
    }

    private fun updateDockerHubOverview(jwtToken: String) {
        logger.warn("Updating DockerHub overview for repository: ${repositoryName.get()}")
        val url = "https://hub.docker.com/v2/repositories/${repositoryName.get()}/"

        val payload =
            mapper().writeValueAsString(
                mapOf(
                    "full_description" to
                        (readmeFile.orNull?.asFile?.readText() ?: readmeContent.orNull
                            ?: error("A Docker Hub README file or content must be configured")),
                ),
            )
        val requestBody = payload.toRequestBody("application/json".toMediaType())

        val request =
            Request
                .Builder()
                .url(url)
                .patch(requestBody)
                .addHeader("Authorization", "JWT $jwtToken")
                .build()

        withRetries("update DockerHub overview") {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 429 || response.code >= 500) {
                    throw RetryableHttpException("DockerHub returned HTTP ${response.code}")
                }
                if (!response.isSuccessful) {
                    error("Failed to update DockerHub overview. code=${response.code} message=${response.message} body=${response.body.string()}")
                }
            }
        }
    }

    private fun <T> withRetries(operation: String, block: () -> T): T {
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                lastFailure = e
            }
            if (attempt < 2) {
                val delaySeconds = 5L shl attempt
                logger.warn("DockerHub operation '$operation' failed; retrying in ${delaySeconds}s")
                TimeUnit.SECONDS.sleep(delaySeconds)
            }
        }
        throw GradleException("Failed to $operation after 3 attempts", lastFailure)
    }
}

private class RetryableHttpException(message: String) : IOException(message)

internal class HasDockerReadme(private val readmeFile: java.io.File) : Spec<Task> {
    override fun isSatisfiedBy(element: Task): Boolean = readmeFile.isFile
}
