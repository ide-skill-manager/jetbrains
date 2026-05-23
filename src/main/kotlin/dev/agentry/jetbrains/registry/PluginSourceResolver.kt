package dev.agentry.jetbrains.registry

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.model.PluginSource
import dev.agentry.jetbrains.util.AgentryPaths
import dev.agentry.jetbrains.util.InputValidation
import java.io.File

/**
 * Resolves a [PluginSource] to an on-disk plugin root.
 *
 * Three shapes:
 *
 *   - [PluginSource.Local]: path relative to the registry's cloned working tree (or to
 *     `metadata.pluginRoot` from the marketplace catalog, when set). No network IO.
 *   - [PluginSource.Github]: `{ "source": "github", "repo": "owner/name", "ref"?: "...", "sha"?: "..." }`.
 *     Shallow-cloned into `~/.agentry/cache/plugins/<sha-or-key>/` and pinned to `sha` or `ref`.
 *   - [PluginSource.Url]: `{ "source": "url", "url": "https://...", "ref"?: "..." }`.
 *     Shallow-cloned the same way.
 *
 * External clones share the same cache layout as registries (see [RegistryManager.localDirFor]),
 * but live under a `plugins/` sub-prefix so the registry cache doesn't get confused with
 * per-plugin checkouts. Caching is content-addressed when [PluginSource.Github.sha] is
 * present; otherwise it's keyed by `(redacted-url, ref)` and re-fetched on TTL miss.
 */
@Service(Service.Level.APP)
class PluginSourceResolver internal constructor() {

    private val log = logger<PluginSourceResolver>()
    // Resolved lazily so plain-JUnit tests of local-path resolution don't drag in the
    // application-level RegistryManager service (which isn't constructed outside
    // BasePlatformTestCase).
    private val registry: RegistryManager by lazy { RegistryManager.getInstance() }

    /**
     * @param registryRoot the cloned-on-disk root of the *registry* that declared this
     *   plugin source. Used to resolve [PluginSource.Local] paths.
     * @param pluginRoot the value of `metadata.pluginRoot` from the marketplace, if any.
     *   Locally-pathed plugins are resolved relative to this when set, falling back to
     *   [registryRoot] otherwise.
     */
    fun resolve(
        source: PluginSource,
        registryRoot: File,
        pluginRoot: String? = null
    ): Result<File> = runCatching {
        when (source) {
            is PluginSource.Local -> resolveLocal(source, registryRoot, pluginRoot)
            is PluginSource.Github -> resolveExternal(
                url = "https://github.com/${source.repo}.git",
                ref = source.sha ?: source.ref ?: "HEAD",
                cacheKey = "gh-${source.repo.replace('/', '_')}-${source.sha ?: source.ref ?: "HEAD"}"
            )
            is PluginSource.Url -> resolveExternal(
                url = source.url,
                ref = source.ref ?: "HEAD",
                cacheKey = null
            )
        }
    }

    private fun resolveLocal(source: PluginSource.Local, registryRoot: File, pluginRoot: String?): File {
        val base = if (!pluginRoot.isNullOrBlank()) {
            val resolved = File(registryRoot, pluginRoot)
            if (!InputValidation.isInsideDir(resolved, registryRoot)) {
                throw SecurityException("marketplace.metadata.pluginRoot escapes the registry root: $pluginRoot")
            }
            resolved
        } else {
            registryRoot
        }
        val resolved = File(base, source.relativePath)
        if (!InputValidation.isInsideDir(resolved, registryRoot)) {
            throw SecurityException("plugin source path escapes the registry: ${source.relativePath}")
        }
        if (!resolved.isDirectory) {
            throw IllegalArgumentException("plugin source path is not a directory: ${source.relativePath}")
        }
        return resolved
    }

    /**
     * Clone an external plugin source into the per-plugin cache and return its root.
     * Reuses [RegistryManager]'s git-runner indirectly by treating the plugin as a
     * single-source registry of its own; the resulting working tree IS the plugin root.
     */
    private fun resolveExternal(url: String, ref: String, cacheKey: String?): File {
        if (!InputValidation.isValidRegistryUrl(url)) {
            throw IllegalArgumentException("Invalid plugin source URL")
        }
        if (ref != "HEAD" && !InputValidation.isValidGitRef(ref)) {
            throw IllegalArgumentException("Invalid plugin source ref: '$ref'")
        }
        // We piggy-back on RegistryManager's existing clone-into-cache machinery by
        // pretending the plugin URL is a registry of its own. The result is exactly
        // the on-disk path we want.
        val pseudoSource = dev.agentry.jetbrains.model.RegistrySource(url = url, ref = ref)
        val target = pluginCacheDir(cacheKey, pseudoSource).takeIf { it != registry.localDirFor(pseudoSource) }
            ?: registry.localDirFor(pseudoSource)
        // `fetchSource` swallows clone errors and returns an empty manifest list; if the
        // working tree didn't actually appear on disk, the clone failed (bad URL, auth,
        // network) and we need to surface that as a typed failure instead of returning a
        // non-existent path.
        registry.fetchSource(pseudoSource)
        if (!target.isDirectory) {
            throw RuntimeException("Could not fetch plugin source: $url@$ref")
        }
        return target
    }

    /**
     * Cache root for external plugin clones. Distinct from the registry cache root so a
     * future "list cached plugins" call doesn't get confused.
     */
    private fun pluginCacheDir(cacheKey: String?, fallbackSource: dev.agentry.jetbrains.model.RegistrySource): File {
        if (cacheKey != null) {
            return File(AgentryPaths.registryCacheRoot.parentFile ?: AgentryPaths.registryCacheRoot, "cache/plugins/$cacheKey")
        }
        return registry.localDirFor(fallbackSource)
    }

    companion object {
        fun getInstance(): PluginSourceResolver =
            ApplicationManager.getApplication().getService(PluginSourceResolver::class.java)
    }
}
