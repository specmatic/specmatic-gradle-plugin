package io.specmatic.gradle.exec

import io.specmatic.gradle.license.pluginInfo
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.AbstractExecTask
import org.gradle.api.tasks.JavaExec
import org.gradle.process.BaseExecSpec

class ConfigureExecTaskPlugin : Plugin<Project> {
    private val configuredTasks = mutableSetOf<Task>()

    override fun apply(target: Project) {
        target.tasks.withType(AbstractExecTask::class.java) {
            configureTask(target, this)
        }

        target.tasks.withType(JavaExec::class.java) {
            configureTask(target, this)
        }
    }

    private fun configureTask(target: Project, task: Task) {
        if (task is BaseExecSpec) {
            if (configuredTasks.contains(task)) {
                return
            } else {
                configuredTasks.add(task)
            }

            target.pluginInfo("Configuring exec task ${task.path}")

            task.apply {
                standardOutput = System.out
                errorOutput = System.err
                doFirst {
                    val cliArgs = mutableListOf<String>()
                    if (task is JavaExec) {
                        cliArgs.add("java")
                        if (task.jvmArgs.isNotEmpty()) {
                            cliArgs.addAll(task.jvmArgs)
                        }
                        if (!task.classpath.isEmpty) {
                            cliArgs.add("-cp")
                            cliArgs.add(task.classpath.joinToString(":"))
                        }
                        if (task.mainClass.isPresent) {
                            cliArgs.add(task.mainClass.get())
                        }
                        cliArgs.addAll(task.args)
                    } else {
                        cliArgs.addAll(task.commandLine)
                    }

                    target.pluginInfo($$"[$$workingDir]$ $${shellEscapedArgs(cliArgs)}")
                }
            }
        }
    }
}

fun shellEscapedArgs(args: List<String?>): String {
    val escapedArgs = args.filterNotNull().map { shellEscape(it) }
    return buildString {
        escapedArgs.forEachIndexed { index, arg ->
            if (index > 0) {
                append(" \\\n   ")
            }
            append(arg)
        }
    }
}

const val SAFE_PUNCTUATION: String = "@%-_+:,./"

fun shellEscape(word: String): String {
    val len = word.length
    if (len == 0) {
        // Empty string is a special case: needs to be quoted to ensure that it gets
        // treated as a separate argument.
        return "\"\""
    }
    for (ii in 0 until len) {
        val c = word[ii]
        // We do this positively so as to be sure we don't inadvertently forget
        // any unsafe characters.
        if (!Character.isLetterOrDigit(c) && SAFE_PUNCTUATION.indexOf(c) == -1) {
            // replace() actually means "replace all".
            return "\"" + word.replace("\"", "\"\"").replace("\\", "\\\\") + "\""
        }
    }
    return word
}
