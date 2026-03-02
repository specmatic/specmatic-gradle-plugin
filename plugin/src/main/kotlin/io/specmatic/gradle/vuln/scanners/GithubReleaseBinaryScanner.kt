package io.specmatic.gradle.vuln.scanners

import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.utils.okHttpConnector
import io.specmatic.gradle.vuln.scanner.ScannerContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.days
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.GradleException
import org.kohsuke.github.GHAsset
import org.kohsuke.github.GHRelease
import org.kohsuke.github.GitHubBuilder

private val SEVEN_DAYS_IN_MS = 7.days.inWholeMilliseconds

abstract class GithubReleaseBinaryScanner(
    private val repository: String,
    private val executableName: String,
    private val homeDirName: String,
    private val lockFileName: String,
    private val installDirName: String,
    private val versionFileName: String,
) : VulnerabilityScanner {
    override fun homeDir(): File = SystemUtils.getUserHome().resolve(homeDirName)

    override fun ensureInstalled(context: ScannerContext) {
        context.scannerHomeDir.mkdirs()

        val lockFile = context.scannerHomeDir.resolve(lockFileName)
        withInProcessLock(lockFile) {
            withCrossProcessLock(lockFile) {
                val binary = executableFile(context)
                val lastModified = if (binary.exists()) binary.lastModified() else 0L

                if (System.currentTimeMillis() - lastModified > SEVEN_DAYS_IN_MS) {
                    downloadLatest(context)
                }
            }
        }
    }

    protected fun executablePath(context: ScannerContext): String = executableFile(context).path

    private fun installDir(context: ScannerContext): File = context.scannerHomeDir.resolve(installDirName)

    private fun versionFile(context: ScannerContext): File = context.scannerHomeDir.resolve(versionFileName)

    private fun executableFile(context: ScannerContext): File {
        val extension = if (platform().os == "windows") ".exe" else ""
        return installDir(context).resolve("$executableName$extension")
    }

    private fun downloadLatest(context: ScannerContext) {
        context.project.pluginInfo("Checking if $executableName is up to date")

        try {
            val gitHubBuilder = GitHubBuilder().withConnector(okHttpConnector)
            if (System.getenv("SPECMATIC_GITHUB_USER") != null && System.getenv("SPECMATIC_GITHUB_TOKEN") != null) {
                gitHubBuilder.withPassword(System.getenv("SPECMATIC_GITHUB_USER"), System.getenv("SPECMATIC_GITHUB_TOKEN"))
            }

            val gitHub = gitHubBuilder.build()
            val release = gitHub.getRepository(repository).latestRelease
            val latestVersion = releaseVersion(release)
            val currentVersion = if (versionFile(context).exists()) versionFile(context).readText() else "unknown"

            if (currentVersion == latestVersion) {
                return
            }

            val platform = platform()
            val asset = release.listAssets().find { isCompatibleAsset(it, platform.os, platform.arch) }
                ?: throw RuntimeException("No asset found for $executableName for ${platform.os} ${platform.arch}")
            val archivePath = context.temporaryDir.resolve(asset.name)
            val downloadUrl = asset.browserDownloadUrl

            context.project.pluginInfo(
                "Currently installed $executableName version($currentVersion) is not up-to-date. Downloading version $latestVersion from $downloadUrl to $archivePath",
            )

            downloadArchive(downloadUrl, archivePath)
            context.project.delete(installDir(context))
            installDir(context).mkdirs()

            context.project.copy {
                if (archivePath.extension == "zip") {
                    from(context.project.zipTree(archivePath))
                } else {
                    from(context.project.tarTree(archivePath))
                }
                into(installDir(context))
            }

            versionFile(context).writeText(latestVersion)
        } catch (e: Exception) {
            if (executableFile(context).exists()) {
                context.project.pluginInfo("Unable to check latest $executableName version: ${e.message}. Using existing executable.")
                return
            }
            throw RuntimeException("Failed to check or download $executableName", e)
        }
    }

    private fun downloadArchive(downloadUrl: String, archivePath: File) {
        archivePath.parentFile?.mkdirs()
        if (archivePath.exists()) {
            archivePath.delete()
        }

        val requestBuilder =
            Request
                .Builder()
                .url(downloadUrl)

        System.getenv("SPECMATIC_GITHUB_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?.let { requestBuilder.header("Authorization", "Bearer $it") }

        downloadClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Failed to download $executableName archive. HTTP ${response.code}: ${response.message}")
            }

            val body =
                response.body
                    ?: throw RuntimeException("Failed to download $executableName archive: empty response body")

            body.byteStream().use { input ->
                FileOutputStream(archivePath).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 256)
                }
            }
        }
    }

    protected abstract fun isCompatibleAsset(
        asset: GHAsset,
        os: String,
        arch: String,
    ): Boolean

    protected open fun releaseVersion(release: GHRelease): String = release.name.ifBlank { release.tagName }

    private fun platform(): Platform {
        val os =
            when {
                SystemUtils.IS_OS_WINDOWS -> "windows"
                SystemUtils.IS_OS_MAC -> "darwin"
                SystemUtils.IS_OS_LINUX -> "linux"
                else -> throw GradleException("Unsupported operating system: ${SystemUtils.OS_NAME}")
            }

        val osArch = SystemUtils.OS_ARCH.lowercase()
        val arch =
            when {
                (osArch.contains("x86") || osArch.contains("amd64")) && osArch.contains("64") -> "amd64"
                osArch.contains("aarch") && osArch.contains("64") -> "arm64"
                else -> throw GradleException("Unsupported architecture: $osArch")
            }

        return Platform(os = os, arch = arch)
    }

    private fun withInProcessLock(
        lockFile: File,
        action: () -> Unit,
    ) {
        val lock = inProcessLocks.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }
        lock.lock()
        try {
            action()
        } finally {
            lock.unlock()
        }
    }

    private fun withCrossProcessLock(
        lockFile: File,
        action: () -> Unit,
    ) {
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                action()
            }
        }
    }

    private companion object {
        val inProcessLocks = ConcurrentHashMap<String, ReentrantLock>()
        val downloadClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
    }
}

private data class Platform(
    val os: String,
    val arch: String,
)
