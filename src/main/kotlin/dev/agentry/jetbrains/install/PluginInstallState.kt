package dev.agentry.jetbrains.install

import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Cheap on-disk check of whether a plugin component is currently installed.
 *
 * The tree builder uses this to render "installed" badges per component. Tracks two
 * candidate scopes (Project + Global) and reports the first one that has files on disk.
 *
 * Symlink-aware: a symlinked install destination (which a malicious project could pre-plant)
 * does **not** count as installed. We want a true positive signal so the badge can't be
 * spoofed and so uninstall can't be tricked into walking out of the install root.
 *
 * We don't keep a persistent record of *which* scope installed which component yet — that's
 * the spec's "scope persistence" open question. For now, real-file presence is the source
 * of truth.
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
        return scopes(projectScope).any {
            val dest = InstallPaths.destFor(component, plugin, it).toPath()
            Files.exists(dest, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dest)
        }
    }

    private fun scopes(project: InstallScope.Project?): List<InstallScope> =
        listOfNotNull(project, InstallScope.Global)
}
