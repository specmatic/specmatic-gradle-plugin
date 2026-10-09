package io.specmatic.gradle.release

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class GitPushTask : DefaultTask() {
    @get:Internal
    abstract val rootDir: Property<File>

    @TaskAction
    fun push() {
        GitOperations(rootDir.get(), emptyMap(), logger).push()
    }
}
