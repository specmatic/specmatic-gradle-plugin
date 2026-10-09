package io.specmatic.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault

/** A named lifecycle task whose work is entirely represented by task dependencies. */
@DisableCachingByDefault(because = "Lifecycle aggregation task has no outputs to cache")
abstract class SpecmaticAggregateTask : DefaultTask()
