package dev.agentry.jetbrains.model

/**
 * A `marketplace.json` catalog — the Microsoft agent-plugins / Claude Code shape.
 * Listed at the registry root in one of two canonical paths:
 *
 *   `.github/plugin/marketplace.json`     (Microsoft / Copilot CLI / VS Code Copilot)
 *   `.claude-plugin/marketplace.json`     (Claude Code)
 *
 * Each `plugins[*]` entry refers to a plugin's location, either inline at a path relative
 * to the registry root (`metadata.pluginRoot` if set) or by an out-of-tree [PluginSource]
 * (handled in Phase 6).
 *
 * Unknown top-level fields (including `$schema`) are tolerated and discarded — Claude Code
 * does the same at load time.
 */
data class MarketplaceManifest(
    val name: String,
    val description: String?,
    val owner: Owner?,
    val metadata: Metadata?,
    val plugins: List<PluginEntry>
) {
    data class Owner(
        val name: String?,
        val email: String?,
        val url: String?
    )

    data class Metadata(
        val pluginRoot: String?,
        val version: String?
    )
}

/**
 * A `plugins[*]` entry inside a marketplace catalog. Carries enough metadata to render the
 * plugin in the tool window before the full per-plugin manifest is loaded.
 */
data class PluginEntry(
    val name: String,
    val source: PluginSource,
    /** Overlapping `plugin.json` fields that some catalogs duplicate inline; informational only. */
    val displayName: String?,
    val description: String?,
    val version: String?,
    val author: PluginAuthor?,
    val category: String?,
    val tags: List<String>
)

/**
 * Where the plugin's files live, as declared in `marketplace.json`.
 *
 *   - [Local]: a path string relative to the registry root (or to `metadata.pluginRoot` if set).
 *   - [Github]: object form `{ "source": "github", "repo": "owner/name", "ref"?: "...", "sha"?: "..." }`.
 *   - [Url]:    object form `{ "source": "url", "url": "https://..." }`.
 *
 * Object-form sources are resolved in Phase 6 (`PluginSourceResolver`); only [Local] is
 * walked directly off the registry's cloned working tree.
 */
sealed class PluginSource {
    data class Local(val relativePath: String) : PluginSource()
    data class Github(val repo: String, val ref: String?, val sha: String?) : PluginSource()
    data class Url(val url: String, val ref: String?) : PluginSource()
}

data class PluginAuthor(
    val name: String?,
    val email: String?,
    val url: String?
)
