plugins {
    `maven-publish`
    `java-gradle-plugin`
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "1.3.1"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    val dependenciesWithVulnFixes =
        listOf(
            "org.apache.commons:commons-lang3:3.20.0",
        )

    configurations.all {
        dependenciesWithVulnFixes.forEach {
            this.resolutionStrategy.force(it)
        }
    }

    implementation("com.github.jk1.dependency-license-report:com.github.jk1.dependency-license-report.gradle.plugin:3.0.1")
    implementation("com.adarshr.test-logger:com.adarshr.test-logger.gradle.plugin:4.0.0")
    implementation("org.semver4j:semver4j:6.0.0")
    implementation("org.barfuin.gradle.taskinfo:org.barfuin.gradle.taskinfo.gradle.plugin:2.2.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    implementation("commons-codec:commons-codec:1.20.0")
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.3.0")
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.35.0")
    implementation("org.kohsuke:github-api:1.330")
    implementation("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:3.1.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.0")
    implementation("org.gradlex.jvm-dependency-conflict-resolution:org.gradlex.jvm-dependency-conflict-resolution.gradle.plugin:2.5")
    implementation("org.gradlex.java-ecosystem-capabilities:org.gradlex.java-ecosystem-capabilities.gradle.plugin:1.5.3")
    implementation("io.fuchs.gradle.classpath-collision-detector:io.fuchs.gradle.classpath-collision-detector.gradle.plugin:1.0.0")
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:8.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.zeroturnaround:zt-exec:1.12")

    testImplementation("org.apache.maven:maven-model:3.9.12")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.0.2")
    testImplementation("io.mockk:mockk:1.14.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("specmatic-gradle-plugin") {
            id = "io.specmatic.gradle"
            implementationClass = "io.specmatic.gradle.SpecmaticGradlePlugin"
            displayName = "Specmatic Gradle Plugin"
            description =
                buildString {
                    append("This plugin is used to run Specmatic tests as part of the build process.")
                    append("It ensures some standardization for build processes across specmatic repositories.")
                }
            tags = listOf("specmatic", "internal", "standardization")
        }
    }

    website = "https://specmatic.io"
    vcsUrl = "https://github.com/specmatic/specmatic-gradle-plugin"
}

val functionalTestSourceSet: SourceSet =
    sourceSets.create("functionalTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val functionalTest by tasks.registering(Test::class) {
    description = "Run functional tests"
    group = "verification"

    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.named("check") {
    dependsOn(functionalTest)
}

tasks.withType<Test> {
    useJUnitPlatform()

    val tempDir =
        project.layout.buildDirectory
            .dir("reports/tmpdir/${this.name}")
            .get()
            .asFile
    environment("TMPDIR", tempDir)
    systemProperty("java.io.tmpdir", tempDir)

    doFirst {
        project.delete(tempDir)
        tempDir.mkdirs()
    }
}

val stagingRepo: Directory = layout.buildDirectory.dir("mvn-repo").get()

publishing {
    repositories {
        maven {
            url = stagingRepo.asFile.toURI()
        }
    }
}
