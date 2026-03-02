package io.specmatic.gradle.tests

import com.adarshr.gradle.testlogger.TestLoggerPlugin
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.specmatic.gradle.license.pluginInfo
import kotlinx.kover.gradle.plugin.KoverGradlePlugin
import kotlinx.kover.gradle.plugin.dsl.tasks.KoverReport
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

internal class SpecmaticTestReportingPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginInfo("Apply test logger and setup junit")
        target.plugins.apply(TestLoggerPlugin::class.java)
        target.plugins.apply(DetektPlugin::class.java)
        target.plugins.apply(KoverGradlePlugin::class.java)

        target.plugins.withType(JavaPlugin::class.java) {
            configureJunit(target)

            target.plugins.withType(KoverGradlePlugin::class.java) {
                configureKover(target)
            }
        }

        target.plugins.withType(DetektPlugin::class.java) {
            target.tasks.withType<Detekt> {
                ignoreFailures = true
                parallel = true
            }
        }
    }

    private fun configureJunit(eachProject: Project) {
        eachProject.tasks.withType(Test::class.java) {
            eachProject.pluginInfo("Configuring junitPlatform on ${this.path}")
            useJUnitPlatform()
            defaultCharacterEncoding = "UTF-8"
            filter.isFailOnNoMatchingTests = true
        }
    }

    private fun configureKover(eachProject: Project) {
        eachProject.tasks.withType<KoverReport> {
            dependsOn(project.tasks.withType<Test>())
        }
        eachProject.tasks.withType(Test::class.java) {
            eachProject.pluginInfo("Ensure that ${this.path} is finalized by koverXmlReport")
            finalizedBy(eachProject.tasks.withType<KoverReport>())
        }

    }
}
