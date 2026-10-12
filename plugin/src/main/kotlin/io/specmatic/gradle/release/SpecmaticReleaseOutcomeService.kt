package io.specmatic.gradle.release

import java.util.concurrent.atomic.AtomicBoolean
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.slf4j.LoggerFactory

abstract class SpecmaticReleaseOutcomeService : BuildService<SpecmaticReleaseOutcomeService.Parameters>,
    OperationCompletionListener,
    AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val rootDir: DirectoryProperty
        val originalGitCommit: Property<String>
    }

    private val releaseStarted = AtomicBoolean()
    private val buildFailed = AtomicBoolean()

    override fun onFinish(event: FinishEvent) {
        val taskEvent = event as? TaskFinishEvent ?: return
        if (taskEvent.descriptor.taskPath == ":preReleaseBump") releaseStarted.set(true)
        if (taskEvent.result is TaskFailureResult) buildFailed.set(true)
    }

    override fun close() {
        LoggerFactory.getLogger(javaClass).info(
            "Release outcome state: started=${releaseStarted.get()} failed=${buildFailed.get()}",
        )
        if (!releaseStarted.get() || !buildFailed.get()) return

        val rootDir = parameters.rootDir.get().asFile
        val originalCommit = parameters.originalGitCommit.get()
        LoggerFactory.getLogger(javaClass).warn("Release failed; reverting repository to $originalCommit")
        rootDir.execGit(LoggerFactory.getLogger(javaClass), "reset", "--hard", originalCommit)
    }
}
