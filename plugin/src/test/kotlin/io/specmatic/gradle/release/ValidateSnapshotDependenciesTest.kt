package io.specmatic.gradle.release

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.catchThrowable
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class ValidateSnapshotDependenciesTest {
    private fun Project.validateSnapshotDependenciesTask(): ValidateSnapshotDependencies =
        tasks.register("validateSnapshotDependencies", ValidateSnapshotDependencies::class.java).get()

    private fun Project.declareDependency(notation: String, configuration: String = "implementation") {
        if (configurations.findByName(configuration) == null) {
            configurations.create(configuration)
        }
        dependencies.add(configuration, notation)
    }

    private fun Project.declareProjectDependency(dependencyProject: Project, configuration: String = "implementation") {
        if (configurations.findByName(configuration) == null) {
            configurations.create(configuration)
        }
        dependencies.add(configuration, dependencyProject)
    }

    private fun ValidateSnapshotDependencies.assertFailsWithMessage(expectedMessage: String) {
        val thrown = catchThrowable { assertNoSnapshotDependencies() }
        assertThat(thrown).isInstanceOf(GradleException::class.java)
        assertThat(thrown.message).isEqualToNormalizingNewlines(expectedMessage)
    }

    @Test
    fun `succeeds when there are no snapshot dependencies`() {
        val project = ProjectBuilder.builder().build()
        project.declareDependency("io.specmatic.example:lib:1.0.0")
        val task = project.validateSnapshotDependenciesTask()

        assertThatCode { task.assertNoSnapshotDependencies() }.doesNotThrowAnyException()
    }

    @Test
    fun `fails when a configuration has an external snapshot dependency`() {
        val project = ProjectBuilder.builder().build()
        project.declareDependency("io.specmatic.example:lib:1.0.0-SNAPSHOT")
        val task = project.validateSnapshotDependenciesTask()

        task.assertFailsWithMessage(
            """
            The following projects have dependencies with SNAPSHOT versions:

            Project $project uses dependencies with SNAPSHOT versions:
            - io.specmatic.example:lib:1.0.0-SNAPSHOT
            Please remove them before creating a release. Run with `-PallowSnapshotDependencies=true` to disable.
            """.trimIndent(),
        )
    }

    @Test
    fun `fails when a buildscript classpath dependency is a snapshot`() {
        val project = ProjectBuilder.builder().build()
        project.buildscript.dependencies.add("classpath", "io.specmatic.example:some-plugin:1.0.0-SNAPSHOT")
        val task = project.validateSnapshotDependenciesTask()

        task.assertFailsWithMessage(
            """
            The following projects have dependencies with SNAPSHOT versions:

            Project $project uses dependencies with SNAPSHOT versions:
            - io.specmatic.example:some-plugin:1.0.0-SNAPSHOT
            Please remove them before creating a release. Run with `-PallowSnapshotDependencies=true` to disable.
            """.trimIndent(),
        )
    }

    @Test
    fun `warns instead of failing when allowSnapshotDependencies is true`() {
        val project = ProjectBuilder.builder().build()
        project.extensions.extraProperties.set("allowSnapshotDependencies", "true")
        project.declareDependency("io.specmatic.example:lib:1.0.0-SNAPSHOT")
        val task = project.validateSnapshotDependenciesTask()

        assertThatCode { task.assertNoSnapshotDependencies() }.doesNotThrowAnyException()
    }

    @Test
    fun `detects snapshot dependencies declared in a subproject`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        val subProject = ProjectBuilder.builder().withName("child").withParent(rootProject).build()
        subProject.declareDependency("io.specmatic.example:child-lib:1.0.0-SNAPSHOT")
        val task = rootProject.validateSnapshotDependenciesTask()

        task.assertFailsWithMessage(
            """
            The following projects have dependencies with SNAPSHOT versions:

            Project $subProject uses dependencies with SNAPSHOT versions:
            - io.specmatic.example:child-lib:1.0.0-SNAPSHOT
            Please remove them before creating a release. Run with `-PallowSnapshotDependencies=true` to disable.
            """.trimIndent(),
        )
    }

    @Test
    fun `ignores snapshot internal project dependencies`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        val subProject = ProjectBuilder.builder().withName("child").withParent(rootProject).build()
        subProject.version = "1.0.0-SNAPSHOT"
        rootProject.declareProjectDependency(subProject)
        val task = rootProject.validateSnapshotDependenciesTask()

        assertThatCode { task.assertNoSnapshotDependencies() }.doesNotThrowAnyException()
    }

    @Test
    fun `ignores snapshot project dependencies but still fails on external snapshot dependencies`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        val subProject = ProjectBuilder.builder().withName("child").withParent(rootProject).build()
        subProject.version = "1.0.0-SNAPSHOT"
        rootProject.declareProjectDependency(subProject)
        rootProject.declareDependency("io.specmatic.example:external-lib:2.0.0-SNAPSHOT")
        val task = rootProject.validateSnapshotDependenciesTask()

        task.assertFailsWithMessage(
            """
            The following projects have dependencies with SNAPSHOT versions:

            Project $rootProject uses dependencies with SNAPSHOT versions:
            - io.specmatic.example:external-lib:2.0.0-SNAPSHOT
            Please remove them before creating a release. Run with `-PallowSnapshotDependencies=true` to disable.
            """.trimIndent(),
        )
    }

    @Test
    fun `groups snapshot dependencies by project in the failure message`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        rootProject.declareDependency("io.specmatic.example:root-lib:1.0.0-SNAPSHOT")
        val subProject = ProjectBuilder.builder().withName("child").withParent(rootProject).build()
        subProject.declareDependency("io.specmatic.example:child-lib:2.0.0-SNAPSHOT")
        val task = rootProject.validateSnapshotDependenciesTask()

        task.assertFailsWithMessage(
            """
            The following projects have dependencies with SNAPSHOT versions:

            Project $rootProject uses dependencies with SNAPSHOT versions:
            - io.specmatic.example:root-lib:1.0.0-SNAPSHOT

            Project $subProject uses dependencies with SNAPSHOT versions:
            - io.specmatic.example:child-lib:2.0.0-SNAPSHOT
            Please remove them before creating a release. Run with `-PallowSnapshotDependencies=true` to disable.
            """.trimIndent(),
        )
    }
}
