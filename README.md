# Specmatic gradle convention plugin

> **NOTE:** This plugin contains plugin conventions for building specmatic tools. This is only to be used by the
> specmatic core
> team to build tools under the `io.specmatic` namespace.

The Specmatic Gradle Plugin provides an all-in-one solution for automating obfuscation, creating shadow JARs, and
publishing artifacts to Maven repositories. By configuring the specmatic block, specmatic developers can streamline
their build process without manually handling these steps. Ideal for simplifying deployment pipelines in JVM-based
projects.

## Features

1. Auto signing/publishing of artifacts
    - maven central
    - maven local
    - specmatic private repository (on github)
    - any other supported URLs/repositories
2. License checks
    - Ensure that dependencies have a license that allows commecial use of specmatic software (i.e. no copy left
      licenses) without any incumberance.
    - Generate a license report that can be packaged in the distributable jar. This is legal requirement from licenses
      like Apache, BSD-3-Clause. These licenses have a clause that requires distributions of software to carry a notice,
      or attribution specified in the license.
3. Pretty print test progress - uses https://github.com/radarsh/gradle-test-logger-plugin
4. Publish artifacts and create GitHub releases
5. Print task info and dependencies - uses https://gitlab.com/barfuin/gradle-taskinfo
6. Print vulnerability scan reports - uses [osv-scanner](https://github.com/google/osv-scanner)
7. Creates a `version.properties` and `VersionInfo.kt` file in the `${groupId}:${projectName}` package. This contains
   details like the version number, git sha. That may be useful for `--version` or just dumping the version at startup.
8. Ensure that java and kotlin compilation is forced to a configured and consistent version across projects.
9. Ensure artifacts are reproducible.
10. Pretty print any `exec` or `javaexec` tasks, along with their outputs
11. Auto-upgrade/migrated deprecated dependencies to newer dependencies.
12. Better integration with sample repositories
    - Run a build against sample projects and validate changes.
    - Bummp version of dependency in sample project. Ensure that the appropriate jar is checked into the sample repo.
13. Conflict detection and resolution using a combination of `io.fuchs.gradle.classpath-collision-detector`,
    `org.gradlex.jvm-dependency-conflict-detection`, `org.gradlex.jvm-dependency-conflict-resolution`.
    See https://github.com/REPLicated/classpath-collision-detector
    and https://gradlex.org/jvm-dependency-conflict-resolution/ for more details.

## Requirements for using this plugin

1. The following environment variables containing secrets are needed based on the
   requirements. [This script](https://github.com/specmatic/specmatic-github-workflows/blob/main/bin/upload-secrets) will
   help you upload the relevant secrets by scanning your github workflows.

   | Variable(s)                                                                                                                                 | Purpose                                                                                                        | 
         |---------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
   | **Maven Central**                                                                                                                           |                                                                                                                |
   | `ORG_GRADLE_PROJECT_mavenCentralUsername`                                                                                                   | Username for Maven Central                                                                                     |
   | `ORG_GRADLE_PROJECT_mavenCentralPassword`                                                                                                   | Password for Maven Central                                                                                     |
   | **Signing**                                                                                                                                 |                                                                                                                |
   | `ORG_GRADLE_PROJECT_signingInMemoryKey`                                                                                                     | GPG private key for signing (ascii armoured/base64 encoded, without the leading/trailing -----BEGIN/END lines) |
   | `ORG_GRADLE_PROJECT_signingInMemoryKeyId`                                                                                                   | GPG key ID (last 8 chars of hex hex key without the leading `0x`)                                              |
   | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`                                                                                             | Passphrase for the GPG key                                                                                     |
   | **Specmatic Private Repo**                                                                                                                  |                                                                                                                |
   | `SPECMATIC_REPOSILITE_USERNAME`                                                                                                             | Username for Specmatic private repository                                                                      |
   | `SPECMATIC_REPOSILITE_TOKEN`                                                                                                                | Password for Specmatic private repository                                                                      |
   | `ORG_GRADLE_PROJECT_${REPOSITORY_NAME}Username`                                                                                             | Username for repository with specified name                                                                    |
   | `ORG_GRADLE_PROJECT_${REPOSITORY_NAME}Password`                                                                                             | Password for repository with specified name                                                                    |
   | **Docker Hub**                                                                                                                              |                                                                                                                |
   | No variables are needed, but you are required to perform a docker login yourself. The plugin will simply execute a `docker push` equivalent |                                                                                                                |

## Installation, usage and configuration

1. Edit `build.gradle[.kts]`
   ```kotlin
   // in the root project only
   plugins {
      // version specified in settings.gradle & gradle.properties
       id("io.specmatic.gradle")
   }
   ```

2. Edit `settings.gradle[.kts]`
   ```kotlin
   pluginManagement {
       val specmaticGradlePluginVersion = settings.extra["specmaticGradlePluginVersion"] as String
       plugins {
           id("io.specmatic.gradle") version(specmaticGradlePluginVersion)
       }
       repositories {
           gradlePluginPortal()
           mavenCentral()
           mavenLocal()
    
           val repos = mapOf(
              "specmaticReleases" to uri("https://repo.specmatic.io/releases"),
              "specmaticSnapshots" to uri("https://repo.specmatic.io/snapshots"),
              "specmaticPrivate" to uri("https://repo.specmatic.io/private"),
           )
    
           repos.forEach { (repoName, repoUrl) ->
               maven { 
                   name = repoName
                   url = repoUrl
                   credentials {
                       username =
                           settings.extra.properties["reposilite.user"]?.toString() ?: System.getenv("SPECMATIC_REPOSILITE_USERNAME")
                       password =
                           settings.extra.properties["reposilite.token"]?.toString() ?: System.getenv("SPECMATIC_REPOSILITE_TOKEN")
                   }
               }
           } 
       }
   }
   ```

3. Edit `gradle.properties` and add the plugin version
   ```properties
   specmaticGradlePluginVersion=<PLUGIN_VERSION_HERE>
   ```

4. Add the following to your `build.gradle[.kts]` file
    ```kotlin
    specmatic {
        // Set the JVM version. Currently defaults to 17
        jvmVersion = JavaLanguageVersion.of(17)
        // Set the kotlin version to be used. Currently defaults to 1.9.25
        kotlinVersion = "1.9.25"
        // Set the kotlin compiler version. Currently defaults to 1.9
        kotlinApiVersion = KotlinVersion.KOTLIN_1_9
        // List of sample projects that need validation before release, and bumping post release
        downstreamDependentProjects = listOf("project1", "project2")
   
        // List of publish tasks that need to be run on the release tag
        releasePublishTasks = listOf("publishTasks...")
   
        // replace certain dependencies with other dependencies
        versionReplacements = mapOf(
            "org.example.foo:deprecated" to "org.example.foo:shiny-thing:1.2.3"
        )    
    
        // Publish this to some repositories. Can be invoked multiple times
        publishTo("internalRepo", "https://internal.repo.url/repository/maven-releases/")
        // Publish this to maven central. Only use this on open source code
        publishToMavenCentral()
    
        // Provide license details for any libraries that don't have license information in their POM.
        // if using groovy, you may need to prefix below lines with `it.XXX` instead
        licenseData {
            name = "net.researchgate:gradle-release"
            version = "3.1.0"
            projectUrl = "https://github.com/researchgate/gradle-release"
            license = "MIT"
        }
    
        `with<Commercial|OSS><Library|Application|ApplicationLibrary>`(project(":bar")) {
            // The main class, if publishing an application variant
            mainClass = "io.specmatic.ExampleApp"
    
            // Create a GitHub release. Upload any files generated by specified tasks.
            githubRelease {
                addFile("sourcesJar", "foo-sources-${version}.jar")
            }
    
            // Create a docker build/publish task. Pass any optional args to the docker build task. 
            // The `--build-arg VERSION` is already passed as a default 
            dockerBuild {
                // optional, uses the project name by default
                imageName = "foo"
                // any extra docker build args
                extraDockerArgs = listOf("...")
            }
    
            // Obfuscation is enabled by default, but you may pass additional proguard args https://www.guardsquare.com/manual/configuration/usage
            obfuscate("-some-arg")
            obfuscate("-more-args", "-some-more-args")
    
            // Shadowing is enabled, but you pass any additional shadowing options - https://gradleup.com/shadow/
            shadow(prefix = "specmatic_foo") {
                minimize()
                // other options...
            }
    
            publish {
                // configure the pom and any other publication settings
                pom {
                    name.set("Specmatic License Validation")
                    description.set("Specmatic License parsing and validation library")
                    url.set("https://specmatic.io")
                }
            }
        }
    }
    ```

5. Setup your `.gitignore`
    ```gitignore
    # Add the following to the .gitignore file
    gen-kt/
    gen-resources/
    ```

6. Setup GitHub workflows. Best to copy/paste from existing workflows.

## Handling conflict resolution

To work around the dependency hell problem where multiple dependencies have the same class, but different versions, you
can use the `detectCollisions` task to detect the collisions. This will print a report of all the dependencies that have
collisions, and their versions. Additionally, this plugin wraps the `org.gradlex.jvm-dependency-conflict-resolution`
plugin that addresses conflict resolution for some [popular
dependencies](https://gradlex.org/jvm-dependency-conflict-resolution/#all-capabilities). For other dependencies, you can
use the following snippet in your project:

```kotlin
jvmDependencyConflicts {
    patch {
        // attach capabilities to multiple modules that offer the same capability
        module("org.example:old-name") {
            addCapability("org.example:some-feature")
        }

        module("org.example.somepackage:new-name") {
            addCapability("org.example:some-feature")
        }
    }

    // resolve the conflicts by selecting the highest version of the dependency
    conflictResolution {
        selectHighestVersion("org.example:some-feature")
    }
}
```

## Logging

This plugin ensures that the published application variants use slf4j and logback as the default logging mechanism.
Logback dependencies are automatically added by the plugin. A default `logback.xml` is packaged that turns off all
logging by default. In addition, you should setup your application's `main()` function to call `JULForwarder.forward()`
to setup appropriate forwarding of JUL logging to SLF4J.

```kotlin
import io.specmatic.yourpackage.JULForwarder

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        JULForwarder.forward()
        // your application code here
    }
}
```

You may override the default logback configuration by creating a `logback.xml` file and executing the application via:

```bash
java -Dlogback.configurationFile=logback.xml -jar <jar-file>
```

## Available tasks

Here is a list of available tasks

| Task                                                 | Description                                                                                   |
|------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| **Other checks**                                     |                                                                                               |
| `detectCollisions`                                   | Detects dependency collisions and prints a report.                                            |
| **License Checks**                                   |                                                                                               |
| `checkLicense`                                       | Check if License could be used                                                                |
| `generateLicenseReport`                              | Generates license report for all dependencies of this project and its subprojects.            |
| **Publishing tasks**                                 |                                                                                               |
| `publishAllPublicationsToMavenCentralRepository`     | Publishes all Maven publications produced by this project to the mavenCentral repository.     |
| `publishAllPublicationsToSpecmaticPrivateRepository` | Publishes all Maven publications produced by this project to the specmaticPrivate repository. |
| `publishAllPublicationsToStagingRepository`          | Publishes all Maven publications produced by this project to the staging repository.          |
| `publishToMavenCentral`                              | Publishes to a staging repository on Sonatype OSS.                                            |
| **Release tasks**                                    |                                                                                               |
| `release`                                            | Verify project, release, and update version to next.                                          |
| **Vulnerability tasks**                              |                                                                                               |
| `vulnScanSBOM`                                       | Scan for and print vulnerabilities in just dependency tree.                                   |
| `vulnScanJar`                                        | Scan for and print vulnerabilities by deep scanning inside each generated jar.                |
| `vulnScanDocker`                                     | Scan for and Print vulnerabilities in docker image.                                           |
| **Docker tasks**                                     |                                                                                               |
| `dockerBuild`                                        | Builds the docker image (for local use)                                                       |
| `dockerBuildxPublish`                                | Builds and publishes `linux/amd64,linux/arm64` variants of the docker image                   | 
| **Downstream Project Validation**                    |                                                                                               |
| `validateDownstreamProjects`                         | Validate downstream project(s)                                                                |
| `bumpVersionsInDownstreamProjects`                   | Bump versions in downstream project(s)                                                        | 
| `fetchArtifactsInDownstreamProjects`                 | Fetch artifacts downstream project(s)                                                         | 
| **Internal tasks**                                   |                                                                                               |
| `createGithubRelease`                                | Create a Github release. This is already wired up when publishing a release.                  |
| `cyclonedxBom`                                       | Generates a CycloneDX compliant Software Bill of Materials (SBOM).                            | 
| **Application execution tasks**                      |                                                                                               |
| `runObfuscated`                                      | Runs the obfuscated jar with the main class specified in the specmatic block.                 |
| `runUnobfuscated`                                    | Runs the unobfuscated jar with the main class specified in the specmatic block.               |

## Available distribution flavours and the artifacts they generate

| Generated artifact(s)                                                           | Obfuscated | Fat/Shadowed/Shaded | Has dependencies in POM | Javadoc/Source Jars | Is executable | Purpose                                                                         |
|:--------------------------------------------------------------------------------|:----------:|:-------------------:|:-----------------------:|:-------------------:|:-------------:|---------------------------------------------------------------------------------|
| **OSSLibraryConfig**                                                            |            |                     |                         |                     |               |                                                                                 |
| `${groupId}:${projectId}`                                                       |  &#x274C;  |      &#x274C;       |        &#x2705;         |      &#x2705;       |   &#x274C;    | Publishing a library (specmatic-junit5, for e.g.)                               |
| **OSSApplicationConfig**                                                        |            |                     |                         |                     |               |                                                                                 |
| `${groupId}:${projectId}`                                                       |  &#x274C;  |      &#x2705;       |        &#x274C;         |      &#x2705;       |   &#x2705;    | Publishing an application (specmatic-executable, for e.g.)                      |
| **OSSApplicationLibraryConfig**                                                 |            |                     |                         |                     |               |                                                                                 |
| `${groupId}:${projectId}`                                                       |  &#x274C;  |      &#x274C;       |        &#x2705;         |      &#x2705;       |   &#x2705;    | Use the application code as a library (specmatic-executable, for e.g.)          |
| `${groupId}:${projectId}-all`                                                   |  &#x274C;  |      &#x2705;       |        &#x274C;         |      &#x2705;       |   &#x2705;    | Publishing an application (specmatic-executable-all, for e.g.)                  |
| **CommercialLibraryConfig**                                                     |            |                     |                         |                     |               |                                                                                 |
| `${groupId}:${projectId}`                                                       |  &#x2705;  |      &#x2705;       |        &#x274C;         |      &#x274C;       |   &#x2705;    | Publish a commercial library, for use in other modules (license core, for e.g.) |
| `${groupId}:${projectId}-all-debug`                                             |  &#x274C;  |      &#x2705;       |        &#x274C;         |      &#x274C;       |   &#x2705;    | For local debugging, above jar, but unobfuscated                                |
| `${groupId}:${projectId}-min`                                                   |  &#x2705;  |      &#x274C;       |        &#x2705;         |      &#x274C;       |   &#x274C;    | Obfuscated, but has dependencies in POM, for local debugging                    |         |
| `${groupId}:${projectId}-core-dont-use-this-unless-you-know-what-you-are-doing` |  &#x274C;  |      &#x274C;       |        &#x2705;         |      &#x274C;       |   &#x274C;    | Original jar + original deps in the POM, for local debugging                    |
| **CommercialApplicationConfig**                                                 |  &#x2705;  |      &#x2705;       |                         |                     |               |                                                                                 |
| `${groupId}:${projectId}`                                                       |  &#x2705;  |      &#x2705;       |        &#x274C;         |      &#x274C;       |   &#x2705;    | Publish this for end user consumption                                           |
| `${groupId}:${projectId}-all-debug`                                             |  &#x274C;  |      &#x2705;       |        &#x274C;         |      &#x274C;       |   &#x2705;    | For local debugging                                                             |
| **CommercialApplicationAndLibraryConfig**                                       |  &#x2705;  |      &#x2705;       |                         |                     |               |                                                                                 |
| `${groupId}:${projectId}`                                                       |  &#x2705;  |      &#x274C;       |        &#x2705;         |      &#x274C;       |   &#x274C;    | Publish this for end user consumption as as library                             |
| `${groupId}:${projectId}-all`                                                   |  &#x2705;  |      &#x2705;       |        &#x274C;         |      &#x274C;       |   &#x2705;    | Publish this for end user consumption as an executable                          |
| `${groupId}:${projectId}-all-debug`                                             |  &#x274C;  |      &#x2705;       |        &#x274C;         |      &#x274C;       |   &#x2705;    | For local debugging                                                             |
