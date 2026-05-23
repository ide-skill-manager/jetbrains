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

    /** Skills: `<project>/.claude/skills/<name>/` or `~/.copilot/skills/<name>/`. */
    fun skillDir(skillName: String, scope: InstallScope): File = when (scope) {
        is InstallScope.Project -> File(scope.projectDir, ".claude/skills/$skillName")
        is InstallScope.Global -> File(userHome, ".copilot/skills/$skillName")
    }

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
     * Custom Chat Agents — `<project>/.github/agents/<name>.agent.md` (Project) or
     * `~/.copilot/agents/<name>.agent.md` (Global).
     *
     * Both paths are documented by the Copilot for JetBrains team:
     * [Agent Configuration and Extensibility wiki](https://github.com/microsoft/copilot-intellij-feedback/wiki/Agent-Configuration-and-Extensibility)
     * lists `$PROJECT_ROOT/.github/agents/**/*.agent.md` (Local Agent Harness, project)
     * and `$HOME/.copilot/agents/**/*.agent.md` (Local Agent Harness, user) as the
     * canonical discovery globs. Files are auto-discovered; no manual registration.
     *
     * (The wiki also lists `$PROJECT_ROOT/.claude/agents/**/*.agent.md` as a project
     * pickup path — Copilot for JetBrains reads both `.github/` and `.claude/` agents
     * dirs. Worth a future enhancement: dual-write so a single Agentry install reaches
     * Claude Code too. Tracked as a follow-up.)
     */
    fun agentFile(agentName: String, scope: InstallScope): File = when (scope) {
        is InstallScope.Project -> File(scope.projectDir, ".github/agents/$agentName.agent.md")
        is InstallScope.Global -> File(userHome, ".copilot/agents/$agentName.agent.md")
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
     * are checked by [dev.agentry.jetbrains.install.PluginInstallState.isInstalled], and
     * all are removed on uninstall.
     */
    fun destinationsFor(
        component: dev.agentry.jetbrains.model.PluginComponent,
        plugin: dev.agentry.jetbrains.model.PluginManifest,
        scope: InstallScope
    ): List<File> = when (component) {
        is dev.agentry.jetbrains.model.PluginComponent.Skill -> listOf(skillDir(component.name, scope))
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
     * Agent dual-write list. `.github/agents/` is documented by every Copilot variant;
     * `.claude/agents/` is read by Claude Code and Copilot for JetBrains. Writing to both
     * (per scope) hits every tool. The Global scope file is namespaced by plugin id
     * because `~/.copilot/agents/` and `~/.claude/agents/` are shared cross-IDE writable
     * directories — two tools shipping a same-named agent would otherwise collide.
     */
    private fun agentDestinations(
        component: dev.agentry.jetbrains.model.PluginComponent.Agent,
        plugin: dev.agentry.jetbrains.model.PluginManifest,
        scope: InstallScope
    ): List<File> = when (scope) {
        is InstallScope.Project -> listOf(
            File(scope.projectDir, ".github/agents/${component.name}.agent.md"),
            File(scope.projectDir, ".claude/agents/${component.name}.agent.md")
        )
        is InstallScope.Global -> {
            val nsName = "${plugin.name}__${component.name}.agent.md"
            listOf(
                File(userHome, ".copilot/agents/$nsName"),
                File(userHome, ".claude/agents/$nsName")
            )
        }
    }
}
