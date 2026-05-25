package dev.agentry.jetbrains.install.installers

import dev.agentry.jetbrains.install.InstallScope
import java.io.File

/**
 * One source of truth for the on-disk locations each component type lands in,
 * given the user's chosen [InstallScope]. Centralised so the spec's component-to-target
 * mapping table (see `docs/plans/microsoft-agent-plugins-spec.md`) is encoded once.
 *
 * Project paths are rooted at [InstallScope.Project.projectDir]; global paths are rooted
 * at `~/.copilot/...` or `~/.agentry/...` per the spec's JetBrains-Copilot reference.
 */
internal object InstallPaths {

    private val userHome: File get() = File(System.getProperty("user.home"))

    /**
     * Stable install roots a skill's destinations belong inside, in the same order as
     * [skillDestinations]. Derived from [scope] alone (no user input) so a path-traversal
     * in the skill name can't shift the root that containment checks validate against.
     * [SkillBundleInstaller] zips this with [destinationsFor] to do per-destination
     * `isInsideDir` checks.
     */
    fun skillInstallRoots(scope: InstallScope): List<File> = when (scope) {
        is InstallScope.Project -> listOf(
            File(scope.projectDir, ".claude/skills")
        )
        is InstallScope.Global -> listOf(
            File(userHome, ".copilot/skills"),
            File(userHome, ".claude/skills")
        )
    }

    /**
     * Skill dual-write list. At Global scope, both `~/.copilot/skills/<name>/` (read by
     * Copilot for JetBrains, VS Code Copilot, Copilot CLI) and `~/.claude/skills/<name>/`
     * (read by Claude Code and Copilot for JetBrains) are written so a single install reaches
     * every tool. At Project scope only `.claude/skills/<name>/` is needed — every tool
     * reads it from there, so no second location is required.
     */
    private fun skillDestinations(skillName: String, scope: InstallScope): List<File> = when (scope) {
        is InstallScope.Project -> listOf(File(scope.projectDir, ".claude/skills/$skillName"))
        is InstallScope.Global -> listOf(
            File(userHome, ".copilot/skills/$skillName"),
            File(userHome, ".claude/skills/$skillName")
        )
    }

    /**
     * Primary skill destination (first element of [skillDestinations]). Retained for
     * [SkillBundleInstaller] compatibility; callers that need all destinations should use
     * [destinationsFor] instead.
     *
     * @deprecated Use [destinationsFor] to get the full destination list so global installs
     *   dual-write to both `~/.copilot/skills/` and `~/.claude/skills/`.
     */
    @Deprecated(
        "Use destinationsFor(component, plugin, scope) to get all destinations",
        ReplaceWith("destinationsFor(component, plugin, scope).first()")
    )
    fun skillDir(skillName: String, scope: InstallScope): File =
        skillDestinations(skillName, scope).first()

    /** Slash commands → JetBrains "Prompt Files": `<project>/.github/prompts/<name>.prompt.md`. */
    fun promptFile(commandName: String, scope: InstallScope): File = when (scope) {
        is InstallScope.Project -> File(scope.projectDir, ".github/prompts/$commandName.prompt.md")
        is InstallScope.Global -> File(userHome, ".github/prompts/$commandName.prompt.md")
    }

    /** Hooks (preview): `<project>/.github/hooks/<pluginName>/`. */
    fun hookDir(pluginName: String, scope: InstallScope): File = when (scope) {
        is InstallScope.Project -> File(scope.projectDir, ".github/hooks/$pluginName")
        is InstallScope.Global -> File(userHome, ".agentry/plugin-data/$pluginName/hooks")
    }

    /**
     * MCP config landing dir — placeholder until the IntelliJ Copilot's on-disk MCP path
     * is confirmed. We write to a deterministic-but-clearly-Agentry-owned location for
     * now; future Phase 3 hardening picks the right Copilot path.
     */
    fun mcpDir(pluginName: String, scope: InstallScope): File = when (scope) {
        is InstallScope.Project -> File(scope.projectDir, ".github/mcp/$pluginName")
        is InstallScope.Global -> File(userHome, ".agentry/plugin-data/$pluginName/mcp")
    }

    /**
     * `${CLAUDE_PLUGIN_DATA}` resolves here. The spec is explicit: "directory created
     * lazily on first access." Component installers create it on demand.
     */
    fun pluginDataDir(pluginId: String): File =
        File(userHome, ".agentry/plugin-data/$pluginId")

    /**
     * Centralised destination resolver. Returns **every** path the component should land at
     * for the given scope — most component types have one, but [PluginComponent.Agent]
     * dual-writes to `.github/agents/` and `.claude/agents/` so a single install reaches
     * Claude Code, Copilot for JetBrains, VS Code Copilot, Copilot CLI, and the cloud
     * agent without the user having to pick a "side".
     *
     * The first element is the **primary** destination — used as the return value of
     * [dev.agentry.jetbrains.install.PluginInstaller.installPlugin]'s `InstalledComponent`
     * (so reports / notifications cite one canonical path). All elements are written, all
     * are checked by [dev.agentry.jetbrains.install.PluginInstallState.locationsOf], and
     * all are removed on uninstall.
     */
    fun destinationsFor(
        component: dev.agentry.jetbrains.model.PluginComponent,
        plugin: dev.agentry.jetbrains.model.PluginManifest,
        scope: InstallScope
    ): List<File> = when (component) {
        is dev.agentry.jetbrains.model.PluginComponent.Skill -> skillDestinations(component.name, scope)
        is dev.agentry.jetbrains.model.PluginComponent.Command -> listOf(promptFile(component.name, scope))
        is dev.agentry.jetbrains.model.PluginComponent.Agent -> agentDestinations(component, plugin, scope)
        is dev.agentry.jetbrains.model.PluginComponent.Hook -> listOf(hookDir(plugin.name, scope))
        is dev.agentry.jetbrains.model.PluginComponent.McpServer -> listOf(mcpDir(plugin.name, scope))
    }

    /** Single-destination convenience. Returns the primary path from [destinationsFor]. */
    fun destFor(
        component: dev.agentry.jetbrains.model.PluginComponent,
        plugin: dev.agentry.jetbrains.model.PluginManifest,
        scope: InstallScope
    ): File = destinationsFor(component, plugin, scope).first()

    /**
     * Stable install roots an agent's destinations belong inside, in the same order as
     * [agentDestinations]. Derived from [scope] alone (no user input) so a path-traversal
     * in the agent or plugin name can't shift the root that containment checks validate
     * against. `AgentInstaller` zips this with [destinationsFor] to do per-destination
     * `isInsideDir` checks.
     */
    fun agentInstallRoots(scope: InstallScope): List<File> = when (scope) {
        is InstallScope.Project -> listOf(
            File(scope.projectDir, ".github/agents"),
            File(scope.projectDir, ".claude/agents")
        )
        is InstallScope.Global -> listOf(
            File(userHome, ".copilot/agents"),
            File(userHome, ".claude/agents")
        )
    }

    /**
     * Agent dual-write list. `.github/agents/` is documented by every Copilot variant;
     * `.claude/agents/` is read by Claude Code and Copilot for JetBrains. Writing to both
     * (per scope) hits every tool. The Global scope file is namespaced by plugin id
     * because `~/.copilot/agents/` and `~/.claude/agents/` are shared cross-IDE writable
     * directories — two tools shipping a same-named agent would otherwise collide.
     *
     * Each destination is constructed *inside* its corresponding [agentInstallRoots]
     * entry, so the two lists are positionally aligned (project: index 0 = .github,
     * 1 = .claude; global: same order).
     */
    private fun agentDestinations(
        component: dev.agentry.jetbrains.model.PluginComponent.Agent,
        plugin: dev.agentry.jetbrains.model.PluginManifest,
        scope: InstallScope
    ): List<File> {
        val filename = when (scope) {
            is InstallScope.Project -> "${component.name}.agent.md"
            is InstallScope.Global -> "${plugin.name}__${component.name}.agent.md"
        }
        return agentInstallRoots(scope).map { root -> File(root, filename) }
    }
}
