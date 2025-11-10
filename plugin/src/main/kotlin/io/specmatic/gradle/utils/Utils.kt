package io.specmatic.gradle.utils

import io.specmatic.gradle.release.GitOperations
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.gradle.api.Project
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector

fun gitSha(project: Project): String = runCatching {
    GitOperations(project.rootDir, project.properties, project.logger).gitSha()
}.getOrElse { "unknown - no git repo found" }

val okHttp =
    OkHttpClient
        .Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

val okHttpConnector = OkHttpGitHubConnector(okHttp)
