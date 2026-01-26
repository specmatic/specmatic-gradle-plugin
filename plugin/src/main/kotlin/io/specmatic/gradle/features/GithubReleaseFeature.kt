package io.specmatic.gradle.features

class GithubReleaseConfig {
    internal val files = mutableMapOf<String, String>()

    fun addFile(task: String, filename: String) {
        files[task] = filename
    }
}

interface GithubReleaseFeature {
    fun githubRelease(block: GithubReleaseConfig.() -> Unit = {})
}
