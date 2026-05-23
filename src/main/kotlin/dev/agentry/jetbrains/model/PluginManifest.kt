package dev.agentry.jetbrains.model

/**
 * Per-plugin manifest, read from one of:
 *
 *   `<plugin>/.github/plugin.json`        (Microsoft canonical; takes precedence)
 *   `<plugin>/.claude-plugin/plugin.json` (Claude Code dialect)
 *
 * Component-path fields ([skills], [commands], [agents], [hooks], [mcpServers]) accept
 * either a single path string or a list of paths in the source JSON. The parser normalises
 * both to a `List<String>` here. Discovery defaults — `skills/`, `commands/`, etc. —
 * still apply when these fields are absent or empty; see [PluginScanner].
 *
 * Path fields are relative to the **plugin root**, not the `.github/` or `.claude-plugin/`
 * directory. Per the spec.
 */
data class PluginManifest(
    val name: String,
    val displayName: String?,
    val version: String?,
    val description: String?,
    val author: PluginAuthor?,
    val homepage: String?,
    val repository: String?,
    val license: String?,
    val keywords: List<String>,
    val skills: List<String>,
    val commands: List<String>,
    val agents: List<String>,
    val hooks: List<String>,
    val mcpServers: List<String>,
    /**
     * The on-disk root of this plugin (the directory containing `.github/plugin.json`
     * or `.claude-plugin/plugin.json`). Path fields above are resolved relative to this.
     */
    val pluginRoot: java.io.File,
    /**
     * Which dialect the manifest was loaded from. Useful for diagnostics and dual-publish
     * conflict warnings.
     */
    val dialect: ManifestDialect
)

enum class ManifestDialect {
    /** Read from `.github/plugin.json`. */
    GITHUB,
    /** Read from `.claude-plugin/plugin.json`. */
    CLAUDE,
    /**
     * No manifest file present. Name was derived from the directory basename;
     * every list field is empty. Component discovery still works off the default
     * directories (`skills/`, `commands/`, etc.).
     */
    DIRNAME_ONLY,
    /**
     * Loaded from the legacy `skill.json` / `package.json` / `manifest.json` shape via
     * [dev.agentry.jetbrains.registry.ManifestParser]. Surfaces in the UI as
     * "legacy manifest — consider migrating to a plugin.json".
     */
    LEGACY_SINGLE_SKILL,
}
