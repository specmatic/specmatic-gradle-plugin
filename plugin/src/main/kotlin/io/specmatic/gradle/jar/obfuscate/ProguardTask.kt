package io.specmatic.gradle.jar.obfuscate

import io.specmatic.gradle.exec.shellEscape
import io.specmatic.gradle.exec.shellEscapedArgs
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.projectDependencies
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import org.gradle.process.internal.ExecException

@CacheableTask
abstract class ProguardTask
    @Inject
    constructor(
        private val execLauncher: ExecOperations,
        javaToolchainService: JavaToolchainService,
        objectFactory: ObjectFactory,
    ) : DefaultTask() {
        @get:Input
        val proguardVersion = "7.7.0"

        private var javaLauncher: Property<JavaLauncher> =
            objectFactory.property(JavaLauncher::class.java).convention(javaToolchainService.launcherFor {})

        @get:Input
        val proguardArgs = mutableListOf<String>()

        @get:InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        var inputJar: File? = null

        @get:OutputFile
        var outputJar: File? = null

        @TaskAction
        fun exec() {
            project.repositories.mavenCentral()

            val proguard =
                (project as ProjectInternal).configurations.resolvableDependencyScopeUnlocked("proguard_$name") {
                    isVisible = true
                    isTransitive = true
//                isCanBeResolved = true
//                isCanBeConsumed = false
//                isCanBeDeclared = true
                    // since proguard is GPL, we avoid compile time dependencies on it
                    defaultDependencies {
                        add(project.dependencies.create("com.guardsquare:proguard-base:$proguardVersion"))
                    }
                }

            val args = mutableListOf<String>()
            args.add("-cp")
            args.add(
                this.project.configurations
                    .named("proguard_${this.name}")
                    .get()
                    .asPath
            )
            args.add("proguard.ProGuard") // main class
            args.addAll(createArgs())
            getArgsFile().writeText(args.joinToString("\n", transform = ::shellEscape))
            val completeCLI =
                listOf(
                    javaLauncher
                        .get()
                        .executablePath.asFile.path,
                    "@${getArgsFile()}"
                )
            val outputFile = project.file("${getProguardOutputDir()}/proguard-task-output-${this.name}.log")
            val outputFileStream = outputFile.outputStream()
            project.pluginInfo("$ ${shellEscapedArgs(completeCLI)}")

            try {
                execLauncher.exec {
                    executable =
                        javaLauncher
                            .get()
                            .executablePath.asFile.path

                    standardOutput = outputFileStream
                    errorOutput = outputFileStream

                    args("@${getArgsFile()}")
                }
            } catch (e: ExecException) {
                project.pluginInfo(e.message!!)
                project.pluginInfo("Check the proguard output in $outputFile")
                throw e
            } finally {
                project.configurations.remove(proguard)
            }
        }

        private fun getArgsFile(): File = getProguardOutputDir().resolve("args.txt")

        private fun getJVMLibraryFiles(): List<File> {
            val dir =
                javaLauncher
                    .get()
                    .metadata.installationPath
                    .dir("jmods")
            return dir.asFile.listFiles { _, name -> name.endsWith(".jmod") }?.toList() ?: emptyList<File>()
        }

        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        fun getRuntimeConfiguration(): Configuration = this.project.configurations.getByName("runtimeClasspath")

        @OutputDirectory
        fun getProguardOutputDir(): File = File("${project.layout.buildDirectory.get().asFile}/proguard-$name")

        private fun createArgs(): MutableList<String> {
            addJVMLibraryJars()
            addLibraryArgs()
            addMappingFiles()

            appendProguardArgs("-injars", inputJar!!.absolutePath)
            appendProguardArgs("-outjars", outputJar!!.absolutePath)

            proguardArgsToSupportIncrementalObfuscationOfDownstreamDependencies()

            appendProguardArgs("-printseeds", "${seedsFile()}")
            appendProguardArgs("-printconfiguration", "${outputConfigFile()}")
            appendProguardArgs("-dump", "${dumpFile()}")
            appendProguardArgs("-printmapping", "${mapFile()}")

            appendProguardArgs("-whyareyoukeeping", "class io.specmatic.** { *; }")
            appendProguardArgs("-keepattributes", "!LocalVariableTable, !LocalVariableTypeTable")

            // Keep all public members in the internal package
            appendProguardArgs("-keep", "class !**.internal.** { public <fields>; public <methods>;}")
            // obfuscate everything in the internal package
            appendProguardArgs("-keep,allowobfuscation", "class io.specmatic.**.internal.** { *; }")
            // Keep kotlin metadata
            appendProguardArgs("-keep", "class kotlin.Metadata")

            // keep enum values and valueOf methods
            appendProguardArgs(
                """
                -keepclassmembers enum * {
                    public static **[] values();
                    public static ** valueOf(java.lang.String);
                }
                """.trimIndent(),
            )

            // spring beans and JPA annotations
            appendProguardArgs(
                """
                -keepclassmembers class * {
                    @jakarta.persistence.* *;
                    @org.springframework.beans.factory.annotation.Autowired *;
                    @org.springframework.context.annotation.Bean *;
                    @org.springframework.stereotype.* *;
                }
                """.trimIndent()
            )

            return proguardArgs
        }

        // see https://www.guardsquare.com/manual/configuration/examples#incremental
        private fun proguardArgsToSupportIncrementalObfuscationOfDownstreamDependencies() {
            appendProguardArgs("-useuniqueclassmembernames")
            appendProguardArgs("-dontoptimize")
            appendProguardArgs("-dontshrink")
        }

        private fun addMappingFiles() {
            val dependentProjects = project.projectDependencies().map { project.rootProject.project(it.path) }

            val dependentObfuscationMappingFiles =
                dependentProjects.map { dependentProject ->
                    dependentProject.tasks
                        .named("obfuscateJarInternal", ProguardTask::class.java)
                        .get()
                        .mapFile()
                }

            dependentObfuscationMappingFiles.forEach {
                appendProguardArgs("-applymapping", it.path)
            }
        }

        internal fun mapFile(): File = getProguardOutputDir().resolve("proguard.mapping.txt")

        private fun dumpFile(): File = getProguardOutputDir().resolve("proguard.dump.txt")

        private fun outputConfigFile(): File = getProguardOutputDir().resolve("proguard.cfg")

        private fun seedsFile(): File = getProguardOutputDir().resolve("seeds.txt")

        private fun addLibraryArgs() {
            val configuration = getRuntimeConfiguration()
            libraryJars(configuration.files.map { it.path })
        }

        private fun addJVMLibraryJars() {
            libraryJars(getJVMLibraryFiles().map { "${it.absolutePath}(!**.jar;!module-info.class)" })
        }

        internal fun appendProguardArgs(vararg toAdd: String?): Boolean = proguardArgs.addAll(toAdd.filterNotNull())

        private fun libraryJars(libJar: Collection<String?>) = libJar.filterNotNull().forEach {
            appendProguardArgs("-libraryjars", it)
        }
    }
