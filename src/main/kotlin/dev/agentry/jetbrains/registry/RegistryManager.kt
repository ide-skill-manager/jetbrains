package dev.agentry.jetbrains.registry

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import java.io.File
import java.nio.file.Files

/**
 * Manages registry sources: clones/pulls git repositories and indexes the available skills.
 */
class RegistryManager(
    private val cacheRoot: File = File(System.getProperty("user.home"), ".agentry/cache"),
    private val parser: ManifestParser = ManifestParser()
) {

    private val log = logger<RegistryManager>()

    /**
     * Fetch skills from all enabled registry sources.
     * Uses shallow clones for speed. Calls [progress] with status updates.
     */
    fun fetchAll(
        sources: List<RegistrySource>,
        progress: ProgressIndicator? = null
    ): Map<RegistrySource, List<SkillManifest>> {
        val result = mutableMapOf<RegistrySource, List<SkillManifest>>()
        val enabled = sources.filter { it.enabled }
        enabled.forEachIndexed { idx, source ->
            progress?.text = "Fetching registry: ${source.displayName}"
            progress?.fraction = idx.toDouble() / enabled.size
            result[source] = fetchSource(source)
        }
        return result
    }

    /**
     * Fetch (clone or pull) a single registry source and return its skills.
     */
    fun fetchSource(source: RegistrySource): List<SkillManifest> {
        return runCatching {
            val localDir = localDirFor(source)
            if (localDir.exists()) {
                pull(localDir, source.ref)
            } else {
                clone(source.url, localDir, source.ref)
            }
            parser.scanDirectory(localDir, source.url)
        }.onFailure { e ->
            log.warn("Failed to fetch registry ${source.url}: ${e.message}")
        }.getOrDefault(emptyList())
    }

    /** Return cached (previously fetched) skills without network access. */
    fun getCached(source: RegistrySource): List<SkillManifest> {
        val localDir = localDirFor(source)
        if (!localDir.exists()) return emptyList()
        return parser.scanDirectory(localDir, source.url)
    }

    private fun localDirFor(source: RegistrySource): File {
        // Use a sanitized version of the URL as directory name
        val safeName = source.url
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .take(80)
        return File(cacheRoot, safeName)
    }

    private fun clone(url: String, target: File, ref: String) {
        Files.createDirectories(target.toPath())
        val depth = if (ref == "HEAD" || ref.startsWith("refs/")) "--depth=1" else ""
        exec(buildList {
            addAll(listOf("git", "clone", "--quiet"))
            if (depth.isNotEmpty()) add(depth)
            if (ref != "HEAD") addAll(listOf("--branch", ref))
            addAll(listOf(url, target.absolutePath))
        })
    }

    private fun pull(dir: File, ref: String) {
        exec(listOf("git", "-C", dir.absolutePath, "fetch", "--quiet", "--depth=1", "origin"))
        exec(listOf("git", "-C", dir.absolutePath, "reset", "--hard", "FETCH_HEAD"))
    }

    private fun exec(cmd: List<String>) {
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw RuntimeException("Command failed (exit $exit): ${cmd.joinToString(" ")}\n$output")
        }
    }
}
