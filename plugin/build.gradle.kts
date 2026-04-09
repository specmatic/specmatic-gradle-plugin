plugins {
    `maven-publish`
    `java-gradle-plugin`
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "2.1.1"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    mavenLocal()
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

    implementation("com.github.jk1.dependency-license-report:com.github.jk1.dependency-license-report.gradle.plugin:3.1.2")
    implementation("com.adarshr.test-logger:com.adarshr.test-logger.gradle.plugin:4.0.0")
    implementation("org.semver4j:semver4j:6.0.0")
    implementation("org.barfuin.gradle.taskinfo:org.barfuin.gradle.taskinfo.gradle.plugin:2.2.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    implementation("commons-codec:commons-codec:1.21.0")
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.4.1") {
        exclude(group = "org.codehaus.plexus", module = "plexus-utils")
    }
    implementation("org.codehaus.plexus:plexus-utils:4.0.3")
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.36.0")
    implementation("org.kohsuke:github-api:1.330")
    implementation("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:3.2.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")
    implementation("org.gradlex.jvm-dependency-conflict-resolution:org.gradlex.jvm-dependency-conflict-resolution.gradle.plugin:2.5")
    implementation("org.gradlex.java-ecosystem-capabilities:org.gradlex.java-ecosystem-capabilities.gradle.plugin:1.5.3")
    implementation("io.fuchs.gradle.classpath-collision-detector:io.fuchs.gradle.classpath-collision-detector.gradle.plugin:1.0.0")
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:8.4.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.zeroturnaround:zt-exec:1.12")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.8")
    implementation("io.specmatic.priospot:gradle-plugin:0.99.2")

    testImplementation("org.apache.maven:maven-model:3.9.14")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.0.3")
    testImplementation("io.mockk:mockk:1.14.9")
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

val functionalTestSourceSet =
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

val stagingRepo = layout.buildDirectory.dir("mvn-repo").get()

publishing {
    repositories {
        maven {
            url = stagingRepo.asFile.toURI()
        }
    }
}
