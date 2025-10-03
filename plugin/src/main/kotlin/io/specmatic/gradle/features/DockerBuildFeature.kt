package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Project

interface DockerBuildFeature {
    fun dockerBuild(block: DockerBuildConfig.() -> Unit = {})
}

/**
 * Either specify a jar, or a dockerfile must be present in the root project
 */
data class DockerBuildConfig(
    internal var mainJarTaskName: String? = null,
    var imageName: String? = null,
    var extraDockerArgs: MutableList<String> = mutableListOf(),
    var dockerOrgNames: List<String> = mutableListOf("specmatic"),
    var extraApkDependencies: List<String> = listOf(),
)

internal fun Project.mainJar(mainJarTaskName: String) = this.tasks
    .named(mainJarTaskName, ShadowJar::class.java)
    .get()
    .archiveFile
    .get()
    .asFile
