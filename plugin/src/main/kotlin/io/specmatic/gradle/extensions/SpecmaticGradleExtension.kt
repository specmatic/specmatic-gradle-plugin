package io.specmatic.gradle.extensions

import io.specmatic.gradle.features.BaseDistribution
import io.specmatic.gradle.features.CommercialApplicationAndLibraryFeature
import io.specmatic.gradle.features.CommercialApplicationFeature
import io.specmatic.gradle.features.CommercialLibraryFeature
import io.specmatic.gradle.features.OSSApplicationAndLibraryFeature
import io.specmatic.gradle.features.OSSApplicationFeature
import io.specmatic.gradle.features.OSSLibraryFeature
import java.net.URI
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

interface PublishTarget

class MavenCentral : PublishTarget

data class MavenInternal(val repoName: String, val url: URI, val type: RepoType) : PublishTarget

data class PromotionDockerImage(val sourceImage: String, val targetImage: String)

open class PromotionConfig {
    var canonicalMavenRepository: URI? = null
    val targetMavenRepositories = mutableListOf<PublishTarget>()
    val dockerImagePromotions = mutableListOf<PromotionDockerImage>()

    fun canonicalMavenRepository(url: String) {
        canonicalMavenRepository = URI.create(url)
    }

    fun targetMavenRepository(name: String, url: String, type: RepoType = RepoType.PUBLISH_ALL) {
        targetMavenRepositories.add(MavenInternal(name, URI.create(url), type))
    }

    fun dockerImage(sourceImage: String, targetImage: String) {
        dockerImagePromotions.add(PromotionDockerImage(sourceImage, targetImage))
    }
}

open class SpecmaticGradleExtension {
    var releasePublishTasks = listOf<String>()
    var jvmVersion: JavaLanguageVersion = JavaLanguageVersion.of(17)
        set(value) {
            require(value.asInt() >= 17) { "JVM version must be at least 17" }
            field = value
        }

    var kotlinVersion = "2.3.20"
    var downstreamDependentProjects = listOf<String>()

    var kotlinApiVersion: KotlinVersion = KotlinVersion.KOTLIN_2_3

    internal val licenseData = mutableListOf<ModuleLicenseData>()
    internal val projectConfigurations: MutableMap<Project, BaseDistribution> = mutableMapOf()
    var versionReplacements = mutableMapOf<String, String>()
    val promotion = PromotionConfig()

    fun licenseData(block: ModuleLicenseData.() -> Unit) {
        licenseData.add(ModuleLicenseData().apply(block))
    }

    fun promotion(block: PromotionConfig.() -> Unit) {
        promotion.apply(block)
    }

    fun withOSSLibrary(project: Project, block: OSSLibraryFeature.() -> Unit) {
        val projectConfig = OSSLibraryFeature(project).apply(block)
        projectConfig.applyToProject()
        projectConfigurations[project] = projectConfig
    }

    fun withOSSApplication(project: Project, block: OSSApplicationFeature.() -> Unit) {
        val projectConfig = OSSApplicationFeature(project).apply(block)
        projectConfig.applyToProject()
        projectConfigurations[project] = projectConfig
    }

    fun withOSSApplicationLibrary(project: Project, block: OSSApplicationAndLibraryFeature.() -> Unit) {
        val projectConfig = OSSApplicationAndLibraryFeature(project).apply(block)
        projectConfig.applyToProject()
        projectConfigurations[project] = projectConfig
    }

    fun withCommercialApplication(project: Project, block: CommercialApplicationFeature.() -> Unit) {
        val projectConfig = CommercialApplicationFeature(project).apply(block)
        projectConfig.applyToProject()
        projectConfigurations[project] = projectConfig
    }

    fun withCommercialApplicationLibrary(project: Project, block: CommercialApplicationAndLibraryFeature.() -> Unit) {
        val projectConfig = CommercialApplicationAndLibraryFeature(project).apply(block)
        projectConfig.applyToProject()
        projectConfigurations[project] = projectConfig
    }

    fun withCommercialLibrary(project: Project, block: CommercialLibraryFeature.() -> Unit) {
        val projectConfig = CommercialLibraryFeature(project).apply(block)
        projectConfig.applyToProject()
        projectConfigurations[project] = projectConfig
    }
}

class ModuleLicenseData {
    var name: String = ""
    var version: String = ""
    var projectUrl: String? = null
    var license: String = ""
    var licenseUrl: String? = null
}
