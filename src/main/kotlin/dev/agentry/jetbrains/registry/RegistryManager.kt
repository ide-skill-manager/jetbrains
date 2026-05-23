package dev.agentry.jetbrains.registry

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.util.AgentryPaths
import dev.agentry.jetbrains.util.InputValidation
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Clones / pulls registry git repositories and indexes the skill manifests they contain.
 *
 * Registered as an application-level service so test code can replace it via
 * `ServiceContainerUtil.replaceService` and concurrent callers share the same cache state.
 */
@Service(Service.Level.APP)
class RegistryManager {

    private val log = logger<RegistryManager>()
    private val parser = ManifestParser()

    /** Fetch all enabled sources. Network IO; call from a background task. */
    fun fetchAll(
        sources: List<RegistrySource>,
        progress: ProgressIndicator? = null
    ): Map<RegistrySource, List<SkillManifest>> {
        val enabled = sources.filter { it.enabled }
        return enabled.mapIndexed { idx, source ->
            progress?.text = "Fetching registry: ${source.displayName}"
            progress?.fraction = idx.toDouble() / enabled.size.coerceAtLeast(1)
            source to fetchSource(source, progress)
        }.toMap()
    }

    /** Fetch a single source. Returns empty list on validation or git failure. */
    fun fetchSource(source: RegistrySource, progress: ProgressIndicator? = null): List<SkillManifest> {
        if (!InputValidation.isValidRegistryUrl(source.url)) {
            log.warn("Rejecting registry with invalid URL: '${source.url}'")
            return emptyList()
        }
        if (!InputValidation.isValidGitRef(source.ref) && source.ref != "HEAD") {
            log.warn("Rejecting registry '${source.url}' with invalid ref: '${source.ref}'")
            return emptyList()
        }
        return runCatching {
            val localDir = localDirFor(source)
            if (localDir.exists()) pull(localDir, source.ref, progress)
            else clone(source.url, localDir, source.ref, progress)
            parser.scanDirectory(localDir, source.url, source.ref)
        }.onFailure { e ->
            log.warn("Failed to fetch registry ${source.url}: ${e.message}")
        }.getOrDefault(emptyList())
    }

    /** Read previously-fetched skills without network IO. */
    fun getCached(source: RegistrySource): List<SkillManifest> {
        val dir = localDirFor(source)
        return if (dir.exists()) parser.scanDirectory(dir, source.url, source.ref) else emptyList()
    }

    /**
     * Cache directory for [source]. The key includes both URL and ref so that the same
     * repository registered at two different refs (e.g. `main` and `feature/wip`) gets
     * two separate working copies and never overwrites itself.
     */
    fun localDirFor(source: RegistrySource): File {
        val hash = sha256("${source.url}@${source.ref}").take(16)
        val readable = source.url.substringAfterLast('/').removeSuffix(".git")
            .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            .take(40)
        return File(AgentryPaths.registryCacheRoot, "$hash-$readable")
    }

    private fun clone(url: String, target: File, ref: String, progress: ProgressIndicator?) {
        Files.createDirectories(target.toPath())
        val cmd = mutableListOf("git", "clone", "--quiet", "--depth=1")
        if (ref != "HEAD") {
            cmd += listOf("--branch", ref)
        }
        // `--` terminates option parsing so url/target can never be interpreted as flags.
        cmd += listOf("--", url, target.absolutePath)
        runGit(cmd, progress)
    }

    /**
     * Fetch the configured ref and reset to it. This is the loop that makes branch-based WIP
     * skill development work: pin a registry to a branch, push commits, click Refresh, see
     * the latest manifest. Tags and commit SHAs are supported the same way.
     */
    private fun pull(dir: File, ref: String, progress: ProgressIndicator?) {
        val fetchRef = if (ref == "HEAD") "HEAD" else ref
        runGit(
            listOf("git", "-C", dir.absolutePath, "fetch", "--quiet", "--depth=1", "origin", fetchRef),
            progress
        )
        runGit(
            listOf("git", "-C", dir.absolutePath, "reset", "--hard", "--quiet", "FETCH_HEAD"),
            progress
        )
    }

    /**
     * Run a git command with hardened env and progress-aware cancellation. The args list is
     * passed directly to `ProcessBuilder` (no shell), so there's no shell-injection surface;
     * the `--` separator + InputValidation block git's own flag parsing for user inputs.
     */
    private fun runGit(cmd: List<String>, progress: ProgressIndicator?) {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        // Restrict transports git will use. Blocks `ext::`, `file://`, etc.
        pb.environment()["GIT_ALLOW_PROTOCOL"] = "https:http:ssh:git"
        // Treat all URLs/refs as user-supplied so the allowlist above applies.
        pb.environment()["GIT_PROTOCOL_FROM_USER"] = "1"
        // No interactive credential prompts (would hang headless agents).
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"

        val proc = pb.start()
        val output = StringBuilder()
        val reader = proc.inputStream.bufferedReader()
        while (proc.isAlive) {
            if (progress?.isCanceled == true) {
                proc.destroyForcibly()
                throw InterruptedException("Cancelled")
            }
            if (proc.waitFor(200, TimeUnit.MILLISECONDS)) break
            while (reader.ready()) output.appendLine(reader.readLine() ?: break)
        }
        // Drain whatever's left.
        reader.readText().let { if (it.isNotEmpty()) output.append(it) }
        val exit = proc.exitValue()
        if (exit != 0) {
            throw RuntimeException("git exited $exit: ${cmd.joinToString(" ")}\n$output")
        }
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun getInstance(): RegistryManager =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(RegistryManager::class.java)
    }
}
