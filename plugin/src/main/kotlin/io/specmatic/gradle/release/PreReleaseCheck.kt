package io.specmatic.gradle.release

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class PreReleaseCheck : DefaultTask() {
    @get:Internal
    abstract val rootDir: Property<File>

    @get:Input
    abstract val projectProperties: MapProperty<String, String>

    @TaskAction
    fun release() {
        GitOperations(rootDir.get(), projectProperties.get(), logger).apply {
            assertMainBranch()
            assertRepoNotDirty()
            assertNoIncomingOrOutgoingChanges()
        }
    }
}
