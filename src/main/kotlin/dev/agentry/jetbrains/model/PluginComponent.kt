package dev.agentry.jetbrains.model

import java.io.File

/**
 * One installable artefact discovered inside a plugin. Each subtype knows where its source
 * files live on disk and which files travel with it. The component-specific installer (see
 * Phase 3) is responsible for translating these into IDE-side install paths and rewriting
 * any `${CLAUDE_PLUGIN_*}` variables (see Phase 5).
 */
sealed class PluginComponent {
    abstract val name: String
    /** Every file that must be copied for this component to function on disk. */
    abstract val files: List<File>
    /** Which sealed subtype this is — lets dispatchers avoid `when (component) is …`. */
    abstract val kind: ComponentKind

    /** A `SKILL.md`-shaped folder under `skills/<name>/` (or a root `SKILL.md` plugin). */
    data class Skill(
        override val name: String,
        val sourceDir: File,
        val skillFile: File,
        val supportFiles: List<File>
    ) : PluginComponent() {
        override val files: List<File> get() = listOf(skillFile) + supportFiles
        override val kind: ComponentKind get() = ComponentKind.SKILL
    }

    /** A slash command — a single `.md` under `commands/`. */
    data class Command(
        override val name: String,
        val sourceFile: File,
        /** Frontmatter `description` if present; otherwise null. */
        val description: String?,
        /** Frontmatter `argument-hint` if present; we'll drop this on JetBrains install but log it. */
        val argumentHint: String?
    ) : PluginComponent() {
        override val files: List<File> get() = listOf(sourceFile)
        override val kind: ComponentKind get() = ComponentKind.COMMAND
    }

    /** A custom subagent — `.agent.md` (preferred) or `.md` under `agents/`. */
    data class Agent(
        override val name: String,
        val sourceFile: File,
        val description: String?
    ) : PluginComponent() {
        override val files: List<File> get() = listOf(sourceFile)
        override val kind: ComponentKind get() = ComponentKind.AGENT
    }

    /**
     * A hook bundle: `hooks/hooks.json` plus any scripts it references. Script paths
     * captured in [scripts] are absolute, already resolved against the plugin root.
     */
    data class Hook(
        override val name: String,
        val configFile: File,
        val scripts: List<File>
    ) : PluginComponent() {
        override val files: List<File> get() = listOf(configFile) + scripts
        override val kind: ComponentKind get() = ComponentKind.HOOK
    }

    /**
     * An MCP server config: `.mcp.json` or `plugin.json#mcpServers`, plus any bundled
     * binaries it references via `command` paths.
     */
    data class McpServer(
        override val name: String,
        val configFile: File,
        val bundledFiles: List<File>
    ) : PluginComponent() {
        override val files: List<File> get() = listOf(configFile) + bundledFiles
        override val kind: ComponentKind get() = ComponentKind.MCP_SERVER
    }
}

enum class ComponentKind { SKILL, COMMAND, AGENT, HOOK, MCP_SERVER }

/**
 * Headline frontmatter fields read from a skill / command / agent `.md`. The reader only
 * extracts the small set of fields the installer + UI actually consume — `name`,
 * `description`, `argument-hint`. Unknown YAML keys are dropped.
 */
data class Frontmatter(
    val name: String?,
    val description: String?,
    val argumentHint: String?
)
