package io.specmatic.gradle

import com.github.jk1.license.LicenseReportPlugin
import io.mockk.every
import io.mockk.mockk
import io.specmatic.gradle.extensions.SpecmaticGradleExtension
import io.specmatic.gradle.release.SpecmaticReleasePlugin
import io.specmatic.gradle.release.execGit
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.barfuin.gradle.taskinfo.GradleTaskInfoPlugin
import org.gradle.api.Project
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.initialization.resolve.RulesMode
import org.gradle.api.internal.SettingsInternal
import org.gradle.api.internal.project.DefaultProject
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestFramework
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.PluginContainer
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.initialization.SettingsState
import org.gradle.internal.impldep.org.eclipse.jgit.api.Git
import org.gradle.internal.management.DependencyResolutionManagementInternal
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.the
import org.gradle.testfixtures.ProjectBuilder
import org.gradlex.jvm.dependency.conflict.detection.JvmDependencyConflictDetectionPlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

class SpecmaticGradlePluginTest {
    @Nested
    inner class LicenseReporting {
        @Test
        fun `jar, build, assemble tasks will invoke checkLicense task`() {
            val project = createProject("org.example", "1.2.3")

            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            assertThat(project.plugins.hasPlugin(LicenseReportPlugin::class.java)).isTrue()
            assertThat(project.tasks.findByName("prettyPrintLicenseCheckFailures")).isNotNull()
            assertThat(project.tasks.findByName("createAllowedLicensesFile")).isNotNull()

            assertThat(
                project.tasks
                    .named("check")
                    .get()
                    .taskDependencies
                    .getDependencies(null),
            ).contains(project.tasks.named("generateLicenseReport").get())
        }

        @Test
        fun `checkLicense task should invoke createAllowedLicensesFileTask and be finalized by prettyPrintLicenseCheckFailuresTask`() {
            val project = createProject("org.example", "1.2.3")
            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            val checkLicenseTask = project.tasks.named("checkLicense").get()
            val prettyPrintLicenseCheckFailuresTask = project.tasks.named("prettyPrintLicenseCheckFailures")
            val createAllowedLicensesFileTask = project.tasks.named("createAllowedLicensesFile")

            assertThat(checkLicenseTask.taskDependencies.getDependencies(null)).contains(createAllowedLicensesFileTask.get())
            assertThat(checkLicenseTask.finalizedBy.getDependencies(null)).contains(prettyPrintLicenseCheckFailuresTask.get())
        }
    }

    @Nested
    inner class Testing {
        @Test
        fun `configures junit platform extension`() {
            val project = createProject("org.example", "1.2.3")
            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            val testTask = project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).first()
            assertThat(testTask.testFramework).isInstanceOf(JUnitPlatformTestFramework::class.java)
        }

        @Test
        fun `should configure test logger plugin`() {
            val project = createProject()
            project.plugins.apply("io.specmatic.gradle")
            assertThat(project.plugins.hasPlugin("com.adarshr.test-logger")).isTrue()
        }
    }

    @Test
    fun `adds SpecmaticGradleExtension`() {
        val project = createProject()
        project.plugins.apply("io.specmatic.gradle")
        assertThat(project.extensions.findByType(SpecmaticGradleExtension::class.java)).isNotNull()
    }

    @Test
    fun `should apply releases plugin`() {
        val project = createProject()
        project.plugins.apply("io.specmatic.gradle")
        assertThat(project.plugins.hasPlugin(SpecmaticReleasePlugin::class.java)).isTrue()
    }

    @Test
    fun `should apply task info plugin`() {
        val project = createProject()
        project.plugins.apply("io.specmatic.gradle")
        assertThat(project.plugins.hasPlugin(GradleTaskInfoPlugin::class.java)).isTrue()
    }

    @Nested
    inner class CompilerOptions {
        @Test
        fun `it should apply jvm options to java plugin if java plugin is applied`() {
            val project = createProject("org.example", "1.2.3")
            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            val javaPluginExtension = project.extensions.getByType(JavaPluginExtension::class.java)
            assertThat(
                javaPluginExtension.toolchain.languageVersion
                    .get()
                    .asInt()
            ).isEqualTo(17)

            val kotlinProjectExtension = project.extensions.findByType(KotlinProjectExtension::class.java)
            assertThat(kotlinProjectExtension).isNull()
        }

        @Test
        fun `it should apply jvm options to kotlin and java if kotlin plugin is applied`() {
            val project = createProject("org.example", "1.2.3")
            project.plugins.apply("kotlin")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            val javaPluginExtension = project.extensions.getByType(JavaPluginExtension::class.java)
            assertThat(
                javaPluginExtension.toolchain.languageVersion
                    .get()
                    .asInt()
            ).isEqualTo(17)
        }
    }

    @Nested
    inner class ReproducibleArtifacts {
        @Test
        fun `should configure reproducible artifacts plugin`() {
            val project = createProject("org.example", "1.2.3")
            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            val archiveTasks = project.tasks.withType(AbstractArchiveTask::class.java)
            assertThat(archiveTasks).isNotEmpty()

            archiveTasks.forEach {
                assertThat(it.isReproducibleFileOrder).isTrue()
                assertThat(it.isPreserveFileTimestamps).isFalse()
            }
        }
    }

    @Nested
    inner class JarStamping {
        @Test
        fun `should stamp jar files with version, group, name and unknown git sha when repo is not initialized`() {
            val project = ProjectBuilder.builder().withName("test-project").build()
            project.projectDir.execGit(LoggerFactory.getLogger("test"), "init", "--initial-branch=main")
            setupSettingsMock(project)
            project.version = "1.2.3"
            project.group = "test-group"

            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            val jarTasks = project.tasks.withType(org.gradle.jvm.tasks.Jar::class.java)
            assertThat(jarTasks).isNotEmpty()

            jarTasks.forEach {
                assertThat(it.manifest.attributes["x-specmatic-version"]).isEqualTo("1.2.3")
                assertThat(it.manifest.attributes["x-specmatic-group"]).isEqualTo("test-group")
                assertThat(it.manifest.attributes["x-specmatic-name"]).isEqualTo("test-project")
                assertThat(it.manifest.attributes["x-specmatic-git-sha"]).isEqualTo("unknown - no git repo found")
            }
        }

        @Test
        fun `should stamp jar files with version, group, name and git sha with repo is initialized`(
            @TempDir tempDir: File
        ) {
            val git = Git.init().setDirectory(tempDir).call()
            git
                .commit()
                .setMessage("Initial commit")
                .setSign(false)
                .call()
            val headCommit =
                git
                    .commit()
                    .setMessage("Second commit")
                    .setSign(false)
                    .call()

            val project =
                ProjectBuilder
                    .builder()
                    .withProjectDir(tempDir)
                    .withName("test-project")
                    .build()
            setupSettingsMock(project)
            project.version = "1.2.3"
            project.group = "test-group"

            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            val jarTasks = project.tasks.withType(org.gradle.jvm.tasks.Jar::class.java)
            assertThat(jarTasks).isNotEmpty()

            jarTasks.forEach {
                assertThat(it.manifest.attributes["x-specmatic-version"]).isEqualTo("1.2.3")
                assertThat(it.manifest.attributes["x-specmatic-group"]).isEqualTo("test-group")
                assertThat(it.manifest.attributes["x-specmatic-name"]).isEqualTo("test-project")
                assertThat(it.manifest.attributes["x-specmatic-git-sha"]).isEqualTo(headCommit.name)
            }
        }
    }

    @Nested
    inner class Sourcesets {
        @Test
        fun `should add generated resources dir to main resources if project is root project, and no subprojects exist`() {
            val project = createProject("org.example", "1.2.3")

            project.plugins.apply("java")
            project.plugins.apply("io.specmatic.gradle")
            project.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            assertGeneratedResourcesSourceExists(project)
        }

        @Test
        fun `should add generated resources dir to main resources on java subprojects`() {
            val rootProject = ProjectBuilder.builder().withName("root").build()
            setupSettingsMock(rootProject)
            rootProject.group = "org.example"
            rootProject.version = "1.2.3"

            val subProjectWithJava =
                ProjectBuilder
                    .builder()
                    .withName("java-subproject")
                    .withParent(rootProject)
                    .build()
            subProjectWithJava.plugins.apply("java")

            val subProjectWithoutJava =
                ProjectBuilder
                    .builder()
                    .withName("non-java-subproject")
                    .withParent(rootProject)
                    .build()

            rootProject.plugins.apply("io.specmatic.gradle")
            rootProject.evaluationDependsOn(":") // force execution of `afterEvaluate` block

            assertGeneratedResourcesSourceExists(subProjectWithJava)
            assertGeneratedResourcesDoesNotExist(subProjectWithoutJava)
            assertGeneratedResourcesDoesNotExist(rootProject)
        }

        private fun assertGeneratedResourcesSourceExists(project: Project) {
            val mainResources = project.the<SourceSetContainer>()["main"].resources
            assertThat(mainResources.srcDirs).contains(project.file("src/main/gen-resources"))

            val mainJava = project.the<SourceSetContainer>()["main"].java
            assertThat(mainJava.srcDirs).contains(project.file("src/main/gen-kt"))
        }

        private fun assertGeneratedResourcesDoesNotExist(project: Project) {
            assertThatCode {
                project.the<SourceSetContainer>()["main"].resources
            }.isInstanceOf(UnknownDomainObjectException::class.java)

            assertThatCode {
                project.the<SourceSetContainer>()["main"].java
            }.isInstanceOf(UnknownDomainObjectException::class.java)
        }
    }

    private fun createProject(group: String? = null, version: String? = null): Project {
        val project = ProjectBuilder.builder().build()
        setupSettingsMock(project)

        if (group != null) {
            project.group = "org.example"
        }
        if (version != null) {
            project.version = "1.2.3"
        }
        return project
    }

    private fun setupSettingsMock(project: Project) {
        val settingsInternal = mockk<SettingsInternal>()
        val settingState = mockk<SettingsState>()
        val dependencyResolutionManagementInternal = mockk<DependencyResolutionManagementInternal>()
        val settingsScript = mockk<ScriptSource>()
        val property = mockk<Property<RulesMode>>()
        val pluginContainer = mockk<PluginContainer>()

        every { property.get() } returns RulesMode.PREFER_PROJECT

        every { dependencyResolutionManagementInternal.rulesMode } returns property
        every { settingsScript.fileName } returns "mock-settings.gradle.kts"

        every { pluginContainer.hasPlugin(JvmDependencyConflictDetectionPlugin::class.java) } returns false
        every { settingsInternal.plugins } returns pluginContainer
        every { settingsInternal.settingsScript } returns settingsScript
        every { settingsInternal.dependencyResolutionManagement } returns dependencyResolutionManagementInternal

        every { settingState.settings } returns settingsInternal
        (project as DefaultProject).gradle.attachSettings(settingState)
    }
}
