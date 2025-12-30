package io.specmatic.gradle.jar.publishing

import com.github.jengelman.gradle.plugins.shadow.ShadowBasePlugin
import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin.Companion.SHADOW_RUNTIME_ELEMENTS_CONFIGURATION_NAME
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer
import io.specmatic.gradle.features.ApplicationFeature
import io.specmatic.gradle.jar.massage.jar
import io.specmatic.gradle.jar.massage.shadow
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.projectDependencies
import io.specmatic.gradle.specmaticExtension
import java.io.File
import java.util.jar.JarFile
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

internal const val SHADOW_OBFUSCATED_JAR = "shadowObfuscatedJar"

internal fun Project.createUnobfuscatedShadowJar(
    shadowActions: MutableList<Action<ShadowJar>>,
    shadowPrefix: String,
    isApplication: Boolean,
): TaskProvider<ShadowJar> {
    val jarTask = project.tasks.jar

    val shadowJarTask =
        project.tasks.register("unobfuscatedShadowJar", ShadowJar::class.java) {
            project.pluginInfo("Created task $path")
            group = "build"
            description = "Shadow the original jar"

            dependsOn(jarTask)

            archiveClassifier.set("all-unobfuscated")

            from(project.provider { project.zipTree(jarTask.get().archiveFile) })

            configureCommonShadowConfigs(jarTask, project, shadowPrefix, isApplication)
            applyProjectSpecifiedConfigurations(this, shadowActions)
        }

    project.tasks.named("assemble") { dependsOn(shadowJarTask) }

    return shadowJarTask
}

private fun Project.excludeProjectDependencies(shadowJarTask: TaskProvider<ShadowJar>) {
    project.afterEvaluate {
        val dependentProjectPaths = projectDependencies()

        shadowJarTask.configure {
            dependentProjectPaths.forEach {
                val spec = dependencyFilter.get().project(it.path)
                dependencyFilter.get().exclude(spec)
            }
        }
    }
}

internal fun Project.createObfuscatedShadowJar(
    obfuscateJarTask: TaskProvider<Jar>,
    shadowActions: MutableList<Action<ShadowJar>>,
    shadowPrefix: String,
    isApplication: Boolean,
): TaskProvider<ShadowJar> {
    val jarTask = project.tasks.jar

    val shadowJarTask =
        project.tasks.register(SHADOW_OBFUSCATED_JAR, ShadowJar::class.java) {
            project.pluginInfo("Created task $path")
            group = "build"
            description = "Shadow the obfuscated jar"

            dependsOn(obfuscateJarTask)
            dependsOn(jarTask) // since we use manifest from jarTask, we make this explicit, although obfuscateJarTask depends on jarTask

            archiveClassifier.set("all-obfuscated")

            from(project.provider { project.zipTree(obfuscateJarTask.get().archiveFile) })

            configureCommonShadowConfigs(jarTask, project, shadowPrefix, isApplication)
            applyProjectSpecifiedConfigurations(this, shadowActions)
            this.extensions.extraProperties.set("isObfuscated", true)
        }

    excludeProjectDependencies(shadowJarTask)
    dependOnUpstreamObfuscationTasks(shadowJarTask)

    project.tasks.named("assemble") { dependsOn(shadowJarTask) }

    return shadowJarTask
}

private fun Project.dependOnUpstreamObfuscationTasks(shadowJarTask: TaskProvider<ShadowJar>) {
    afterEvaluate {
        val dependentProjects = project.projectDependencies().map { rootProject.project(it.path) }

        val dependentObfuscationTasks =
            dependentProjects.map { dependentProject ->
                dependentProject.tasks.named(OBFUSCATE_JAR_TASK, Jar::class.java)
            }

        dependentObfuscationTasks.forEach { eachUpstreamObfuscationTask ->
            shadowJarTask.configure {
                dependsOn(eachUpstreamObfuscationTask)
                from(
                    project.provider {
                        project.zipTree(eachUpstreamObfuscationTask.get().archiveFile)
                    },
                )
            }
        }
    }
}

private fun Project.applyProjectSpecifiedConfigurations(shadowJarTask: ShadowJar, shadowActions: MutableList<Action<ShadowJar>>,) {
    shadowActions.forEach {
        project.pluginInfo("Applying custom shadow jar configuration")
        it.execute(shadowJarTask)
    }
}

internal fun Project.applyShadowConfigs() {
    pluginInfo("Applying shadow configurations to project $name")
    val shadowConfiguration = configurations.create("shadow")

    val shadowRuntimeElements =
        configurations.create(SHADOW_RUNTIME_ELEMENTS_CONFIGURATION_NAME) {
            extendsFrom(shadowConfiguration)
            isCanBeConsumed = true
            isCanBeResolved = false
        }

    val softwareComponentFactory = (project as ProjectInternal).services.get(SoftwareComponentFactory::class.java)
    val shadowComponent = softwareComponentFactory.adhoc(ShadowBasePlugin.CONFIGURATION_NAME)
    project.components.add(shadowComponent)
    shadowComponent.addVariantsFromConfiguration(shadowRuntimeElements) {
        mapToMavenScope("runtime")
    }

    plugins.withType(JavaPlugin::class.java) {
        configurations.named("compileClasspath") {
            extendsFrom(shadowConfiguration)
        }

        configurations.named("testImplementation") {
            extendsFrom(shadowConfiguration)
        }

        configurations.named("testRuntimeOnly") {
            extendsFrom(shadowConfiguration)
        }

        tasks.jar.configure {
            val excludePackages = mutableListOf<String>()

            if (project.specmaticExtension().projectConfigurations[project] !is ApplicationFeature) {
                excludePackages.addAll(
                    listOf(
                        "java",
                        "javax",
                        "kotlin",
                        "org/jetbrains",
                        "org/intellij/lang/annotations",
                    ),
                )
            }

            from(
                provider {
                    configurations.shadow
                        .get()
                        .files
                        .map { zipTree(it) }
                }
            ) {
                excludePackages.forEach {
                    exclude("$it/**")
                }
            }
        }
    }
}

private fun ShadowJar.maybeRelocateIfConfigured(project: Project, shadowPrefix: String, isApplication: Boolean) {
    val runtimeClasspath = project.configurations.findByName("runtimeClasspath")
    val excludePackages = mutableListOf<String>()

    if (!isApplication) {
        excludePackages.addAll(listOf("java", "javax", "kotlin", "org/jetbrains", "org/intellij/lang/annotations"))
    }

    excludePackages.forEach { exclude("$it/**") }

    if (shadowPrefix.isNotBlank()) {
        // we do this in doFirst so that we can lazily evaluate the runtimeClasspath
        doFirst {
//            val runtimeClasspathFiles = runtimeClasspath?.files.orEmpty()

            val runtimeClasspathFiles =
                runtimeClasspath
                    ?.incoming
                    ?.artifacts
                    ?.filter {
                        !it.variant.owner.displayName
                            .startsWith("ch.qos.logback:")
                    }?.map { it.file }
                    .orEmpty()
                    .toSet()

            val packagesToRelocate = extractPackagesInJars(runtimeClasspathFiles, excludePackages)

            packagesToRelocate.forEach { eachPackage ->
                project.pluginInfo("Relocating package: $eachPackage to $shadowPrefix/$eachPackage")
                relocate(eachPackage, "$shadowPrefix.$eachPackage")
            }
        }
    }
}

private fun extractPackagesInJars(runtimeClasspathFiles: Set<File>, excludePackages: List<String>,): MutableSet<String> {
    val packagesToRelocate = mutableSetOf<String>()
    runtimeClasspathFiles.forEach { eachFile ->
        if (eachFile.name.lowercase().endsWith(".jar")) {
            JarFile(eachFile).use {
                it.entries().asSequence().forEach { entry ->
                    val entryName = entry.name
                    if (shouldRelocatePackage(entryName, excludePackages)) {
                        val packageName = entryName.substring(0, entryName.lastIndexOf('/'))
                        packagesToRelocate.add(packageName)
                    }
                }
            }
        }
    }
    return packagesToRelocate
}

private fun shouldRelocatePackage(entryName: String, excludePackages: List<String>): Boolean = entryName.endsWith(".class") &&
    entryName.contains("/") &&
    excludePackages.none {
        entryName.startsWith(
            "$it/",
        )
    } &&
    !entryName.startsWith("META-INF/")

internal fun ShadowJar.configureCommonShadowConfigs(
    jarTask: TaskProvider<Jar>,
    project: Project,
    shadowPrefix: String,
    isApplication: Boolean,
) {
    val runtimeClasspath = project.configurations.getByName("runtimeClasspath")

    maybeRelocateIfConfigured(project, shadowPrefix, isApplication)

    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    manifest.inheritFrom(jarTask.get().manifest)
    configurations.set(listOf(runtimeClasspath))

    mergeServiceFiles()

    // these files contain hints for spring to find and scan for beans
    append("META-INF/spring.handlers")
    append("META-INF/spring.schemas")
    append("META-INF/spring.tooling")
    append("META-INF/spring/aot.factories")
    append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    append("META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports")
    transform(Log4j2PluginsCacheFileTransformer::class.java) {}

    transform(PropertiesFileTransformer::class.java) {
        paths.set(listOf("META-INF/spring.factories"))
        mergeStrategy.set(PropertiesFileTransformer.MergeStrategy.Append)
    }

    exclude(
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/versions/**/module-info.class",
        "module-info.class",
    )

    dependencies {
        exclude(dependency(project.dependencies.gradleApi()))
    }
}
