package io.specmatic.gradle.utils

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.gradle.api.Project
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector

fun gitSha(project: Project): String = runCatching {
    val gitOutput = project.providers.exec {
        commandLine("git", "rev-parse", "HEAD")
        workingDir(project.rootDir)
        isIgnoreExitValue = true
    }
    if (gitOutput.result.get().exitValue != 0) {
        "unknown - no git repo found"
    } else {
        gitOutput.standardOutput.asText.get().trim().ifBlank { "unknown - no git repo found" }
    }
}.getOrElse { "unknown - no git repo found" }

val okHttp =
    OkHttpClient
        .Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

val okHttpConnector = OkHttpGitHubConnector(okHttp)
