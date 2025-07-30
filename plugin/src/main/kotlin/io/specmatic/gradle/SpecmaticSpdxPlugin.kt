package io.specmatic.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.spdx.sbom.gradle.SpdxSbomExtension
import org.spdx.sbom.gradle.SpdxSbomPlugin

class SpecmaticSpdxPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply(SpdxSbomPlugin::class.java)

        target.plugins.withType(SpdxSbomPlugin::class.java) {
            target.extensions.getByType(SpdxSbomExtension::class.java).apply {
                targets.apply {
                    create("release") {
                        configurations.set(listOf("runtimeClasspath", "compileClasspath"))
                        document {
                            packageSupplier.set("Person:Goose Loosebazooka")
                        }
                    }
                }
            }
        }
    }
}
