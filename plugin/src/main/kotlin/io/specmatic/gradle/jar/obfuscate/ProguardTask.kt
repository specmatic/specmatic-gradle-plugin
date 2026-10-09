package io.specmatic.gradle.jar.obfuscate

import io.specmatic.gradle.exec.shellEscape
import io.specmatic.gradle.exec.shellEscapedArgs
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
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
        val proguardVersion = "7.8.2"

        private var javaLauncher: Property<JavaLauncher> =
            objectFactory.property(JavaLauncher::class.java).convention(javaToolchainService.launcherFor {})

        @get:Input
        val proguardArgs = mutableListOf<String>()

        @get:Classpath
        abstract val proguardClasspath: ConfigurableFileCollection

        @get:Classpath
        abstract val runtimeClasspath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val dependentMappingFiles: ConfigurableFileCollection

        @get:InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        var inputJar: File? = null

        @get:OutputFile
        var outputJar: File? = null

        @get:OutputDirectory
        abstract val proguardOutputDir: DirectoryProperty

        @TaskAction
        fun exec() {
            val args = mutableListOf<String>()
            args.add("-cp")
            args.add(proguardClasspath.asPath)
            args.add("proguard.ProGuard") // main class
            args.addAll(createArgs())
            getArgsFile().writeText(args.joinToString("\n", transform = ::shellEscape))
            val completeCLI =
                listOf(
                    javaLauncher
                        .get()
                        .executablePath.asFile.path,
                    "@${getArgsFile()}",
                )
            val outputFile = outputDirectory().resolve("proguard-task-output-${this.name}.log")
            val outputFileStream = outputFile.outputStream()
            logger.info("$ ${shellEscapedArgs(completeCLI)}")

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
                logger.error(e.message!!)
                logger.error("Check the proguard output in ${outputFile.toURI()}")
                throw e
            } finally {
                outputFileStream.close()
            }
        }

        private fun getArgsFile(): File = outputDirectory().resolve("args.txt")

        private fun getJVMLibraryFiles(): List<File> {
            val dir =
                javaLauncher
                    .get()
                    .metadata.installationPath
                    .dir("jmods")
            return dir.asFile.listFiles { _, name -> name.endsWith(".jmod") }?.toList() ?: emptyList<File>()
        }

        private fun outputDirectory(): File = proguardOutputDir.get().asFile

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
            appendProguardArgs("-keepparameternames")

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
                """.trimIndent(),
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
            val merged = outputDirectory().resolve("dependent-proguard-mappings.txt")
            merged.parentFile.mkdirs()
            merged.bufferedWriter().use { out ->
                dependentMappingFiles.files.forEach { f ->
                    out.append(f.readText())
                }
            }

            appendProguardArgs("-applymapping", merged.absolutePath)
        }

        internal fun mapFile(): File = outputDirectory().resolve("proguard.mapping.txt")

        private fun dumpFile(): File = outputDirectory().resolve("proguard.dump.txt")

        private fun outputConfigFile(): File = outputDirectory().resolve("proguard.cfg")

        private fun seedsFile(): File = outputDirectory().resolve("seeds.txt")

        private fun addLibraryArgs() {
            libraryJars(runtimeClasspath.files.map { it.path })
        }

        private fun addJVMLibraryJars() {
            libraryJars(getJVMLibraryFiles().map { "${it.absolutePath}(!**.jar;!module-info.class)" })
        }

        internal fun appendProguardArgs(vararg toAdd: String?): Boolean = proguardArgs.addAll(toAdd.filterNotNull())

        private fun libraryJars(libJar: Collection<String?>) = libJar.filterNotNull().forEach {
            appendProguardArgs("-libraryjars", it)
        }
    }
