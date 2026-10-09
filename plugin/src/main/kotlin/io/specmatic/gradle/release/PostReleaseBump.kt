package io.specmatic.gradle.release

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class PostReleaseBump : DefaultTask() {
    @get:Internal
    abstract val rootDir: Property<File>

    @get:Input
    abstract val postReleaseVersion: Property<String>

    @TaskAction
    fun postReleaseBump() {
        GitOperations(rootDir.get(), emptyMap(), logger).apply {
            postReleaseBump(postReleaseVersion.get())
        }
    }
}
