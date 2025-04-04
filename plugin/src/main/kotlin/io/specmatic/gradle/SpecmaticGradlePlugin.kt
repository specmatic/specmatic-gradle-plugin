package io.specmatic.gradle

import io.specmatic.gradle.artifacts.EnsureJarsAreStampedPlugin
import io.specmatic.gradle.artifacts.EnsureReproducibleArtifactsPlugin
import io.specmatic.gradle.compiler.ConfigureCompilerOptionsPlugin
import io.specmatic.gradle.exec.ConfigureExecTaskPlugin
import io.specmatic.gradle.extensions.SpecmaticGradleExtension
import io.specmatic.gradle.extensions.baseSetup
import io.specmatic.gradle.jar.massage.applyToRootProjectOrSubprojects
import io.specmatic.gradle.jar.publishing.applyShadowConfigs
import io.specmatic.gradle.license.SpecmaticLicenseReportingPlugin
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.plugin.VersionInfo
import io.specmatic.gradle.sampleprojects.SampleProjectIntegrationPlugin
import io.specmatic.gradle.tests.SpecmaticTestReportingPlugin
import io.specmatic.gradle.versioninfo.VersionInfoPlugin
import io.specmatic.gradle.versioninfo.versionInfo
import io.specmatic.gradle.versions.ForceVersionConstraintsPlugin
import io.specmatic.gradle.vuln.SpecmaticVulnScanPlugin
import org.barfuin.gradle.taskinfo.GradleTaskInfoPlugin
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("unused")
class SpecmaticGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("specmatic", SpecmaticGradleExtension::class.java)

        target.pluginInfo("Specmatic Gradle Plugin ${VersionInfo.describe()}")

        target.applyToRootProjectOrSubprojects {
            applyShadowConfigs()
            afterEvaluate {
                baseSetup()
            }
        }

        target.rootProject.versionInfo()

        target.plugins.apply(SampleProjectIntegrationPlugin::class.java)

        // apply whatever plugins we need to apply
        target.plugins.apply(SpecmaticLicenseReportingPlugin::class.java)
        target.allprojects {
            plugins.apply(SpecmaticTestReportingPlugin::class.java)
        }
        target.plugins.apply(SpecmaticReleasePlugin::class.java)

        target.plugins.apply(GradleTaskInfoPlugin::class.java)

        target.plugins.apply(SpecmaticVulnScanPlugin::class.java)

        target.applyToRootProjectOrSubprojects {
            plugins.apply(VersionInfoPlugin::class.java)
        }

        target.applyToRootProjectOrSubprojects {
            plugins.apply(ConfigureCompilerOptionsPlugin::class.java)
            plugins.apply(EnsureReproducibleArtifactsPlugin::class.java)
            plugins.apply(EnsureJarsAreStampedPlugin::class.java)
            plugins.apply(ConfigureExecTaskPlugin::class.java)
            plugins.apply(ForceVersionConstraintsPlugin::class.java)
        }
    }

}

fun Project.specmaticExtension(): SpecmaticGradleExtension {
    var currentProject: Project? = this
    while (currentProject != null) {
        currentProject.extensions.findByType(SpecmaticGradleExtension::class.java)?.let {
            return it
        }
        currentProject = currentProject.parent
    }
    throw GradleException("SpecmaticGradleExtension not found in project ${this}, or any of its parents")
}
