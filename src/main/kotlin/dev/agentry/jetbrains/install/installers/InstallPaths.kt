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
     * Source: [GitHub Docs — Creating custom agents](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/create-custom-agents-in-your-ide).
     * The Copilot for JetBrains plugin auto-discovers `.agent.md` files at the project
     * path — no manual registration. The global path is documented for VS Code; we use
     * the same convention for JetBrains and call out the empirical gap in the spec.
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
     * Centralised destination resolver. Single source of truth for "where does component
     * X for plugin Y land at scope Z". Used by [dev.agentry.jetbrains.install.PluginInstaller]
     * for install, [dev.agentry.jetbrains.actions.ComponentActions] for uninstall, and
     * [dev.agentry.jetbrains.install.PluginInstallState] for install-state detection.
     */
    fun destFor(
        component: dev.agentry.jetbrains.model.PluginComponent,
        plugin: dev.agentry.jetbrains.model.PluginManifest,
        scope: InstallScope
    ): File = when (component) {
        is dev.agentry.jetbrains.model.PluginComponent.Skill -> skillDir(component.name, scope)
        is dev.agentry.jetbrains.model.PluginComponent.Command -> promptFile(component.name, scope)
        is dev.agentry.jetbrains.model.PluginComponent.Agent -> agentFile(component.name, scope)
        is dev.agentry.jetbrains.model.PluginComponent.Hook -> hookDir(plugin.name, scope)
        is dev.agentry.jetbrains.model.PluginComponent.McpServer -> mcpDir(plugin.name, scope)
    }
}
