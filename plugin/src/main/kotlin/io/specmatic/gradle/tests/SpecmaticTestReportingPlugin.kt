package io.specmatic.gradle.tests

import com.adarshr.gradle.testlogger.TestLoggerPlugin
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.specmatic.gradle.extensions.SpecmaticGradleExtension
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

        target.plugins.withType(JavaPlugin::class.java) {
            target.plugins.apply(KoverGradlePlugin::class.java)
            configureJunit(target)

            target.afterEvaluate {
                val detektRequested =
                    rootProject.extensions
                        .getByType(SpecmaticGradleExtension::class.java)
                        .detekt.enabled
                val detektEnabledByProperty = providers.gradleProperty("specmatic.detekt").orNull.toBoolean()
                if (detektRequested && detektEnabledByProperty) {
                    plugins.apply(DetektPlugin::class.java)
                    tasks.withType<Detekt>().configureEach {
                        ignoreFailures = true
                        parallel = true
                    }
                }
            }

            target.plugins.withType(KoverGradlePlugin::class.java) {
                configureKover(target)
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
        val testTasks = eachProject.tasks.withType<Test>()

        eachProject.tasks.withType<KoverReport>().configureEach {
            dependsOn(testTasks)
        }

        eachProject.tasks.named("check") {
            dependsOn(eachProject.tasks.withType<KoverReport>())
        }
    }
}
