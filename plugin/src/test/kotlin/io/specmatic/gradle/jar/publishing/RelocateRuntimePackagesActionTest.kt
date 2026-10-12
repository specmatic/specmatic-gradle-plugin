package io.specmatic.gradle.jar.publishing

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RelocateRuntimePackagesActionTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `collects class packages except configured exclusions`() {
        val jar = temporaryDirectory.resolve("dependencies.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            listOf("com/example/Api.class", "java/lang/Object.class", "META-INF/MANIFEST.MF").forEach {
                output.putNextEntry(JarEntry(it))
                output.closeEntry()
            }
        }

        assertThat(extractPackagesInJars(setOf(jar), listOf("java")))
            .containsExactly("com/example")
    }
}
