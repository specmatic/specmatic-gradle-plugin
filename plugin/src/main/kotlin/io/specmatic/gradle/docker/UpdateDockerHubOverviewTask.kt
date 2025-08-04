package io.specmatic.gradle.docker

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

@org.gradle.work.DisableCachingByDefault(because = "Makes network calls")
abstract class UpdateDockerHubOverviewTask : DefaultTask() {
    @get:Input
    abstract val dockerHubUsername: Property<String>

    @get:Input
    abstract val dockerHubApiToken: Property<String>

    @get:Input
    abstract val repositoryName: Property<String>

    @get:Input
    abstract val readmeContent: Property<String>

    private fun client(): OkHttpClient = OkHttpClient()

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

        client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Failed to fetch JWT token: ${response.message}")
            }
            val responseBody = response.body?.string() ?: error("Empty response body")
            val jsonNode = mapper().readTree(responseBody)
            return jsonNode["token"]?.asText() ?: error("JWT token not found in response")
        }
    }

    private fun updateDockerHubOverview(jwtToken: String) {
        logger.warn("Updating DockerHub overview for repository: ${repositoryName.get()}")
        val url = "https://hub.docker.com/v2/repositories/${repositoryName.get()}/"

        val payload =
            mapper().writeValueAsString(
                mapOf(
                    "full_description" to readmeContent.get(),
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

        client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(
                    "Failed to update DockerHub overview. code=${response.code} message=${response.message} body=${response.body?.string()}",
                )
            }
        }
    }
}
