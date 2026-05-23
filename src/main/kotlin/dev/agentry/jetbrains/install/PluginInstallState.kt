package dev.agentry.jetbrains.install

import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest

/**
 * Cheap on-disk check of whether a plugin component is currently installed.
 *
 * The tree builder uses this to render "installed" badges per component. Tracks two
 * candidate scopes (Project + Global) and reports the first one that has files on disk.
 *
 * We don't keep a persistent record of *which* scope installed which component yet — that's
 * the spec's "scope persistence" open question. For now, file presence is the source of truth.
 */
object PluginInstallState {

    /**
     * Returns true if [component] appears installed for [plugin] in either Project (using
     * [projectBasePath]) or Global scope. Used to populate `Component.installed` for the tree.
     */
    fun isInstalled(
        component: PluginComponent,
        plugin: PluginManifest,
        projectBasePath: String?
    ): Boolean {
        val projectScope = projectBasePath?.let { InstallScope.Project(java.io.File(it)) }
        return scopes(projectScope).any { destFor(component, plugin, it).exists() }
    }

    private fun scopes(project: InstallScope.Project?): List<InstallScope> =
        listOfNotNull(project, InstallScope.Global)

    /** Mirror [installers] dispatch — destination depends on the component kind. */
    private fun destFor(component: PluginComponent, plugin: PluginManifest, scope: InstallScope): java.io.File =
        when (component) {
            is PluginComponent.Skill -> InstallPaths.skillDir(component.name, scope)
            is PluginComponent.Command -> InstallPaths.promptFile(component.name, scope)
            is PluginComponent.Agent -> InstallPaths.skillDir("agent-${component.name}", scope) // placeholder; agents are stubbed in installers
            is PluginComponent.Hook -> InstallPaths.hookDir(plugin.name, scope)
            is PluginComponent.McpServer -> InstallPaths.mcpDir(plugin.name, scope)
        }
}
