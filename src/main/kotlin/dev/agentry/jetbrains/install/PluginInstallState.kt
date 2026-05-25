package dev.agentry.jetbrains.install

import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Cheap on-disk check of where a plugin component is currently installed.
 *
 * The tree builder uses [locationsOf] to render the `[U]` / `[P]` / `[U][P]` badge.
 * Symlink-aware: a symlinked destination doesn't count — we want a true positive so the
 * badge can't be spoofed and uninstall can't be tricked into walking out of the install root.
 *
 * Tracks the *primary* destination per scope only (`destFor`, not all of `destinationsFor`).
 * Partial dual-write loss (user manually deleted one of the two `.github/` + `.claude/`
 * agent files) leaves the install considered present as long as the primary survives —
 * documented in `docs/plans/install-target-picker.md`.
 */
object PluginInstallState {

    /**
     * The set of scopes [component] is currently installed in. Empty when not installed anywhere;
     * one-element set when only project or user; two-element set when both.
     */
    fun locationsOf(
        component: PluginComponent,
        plugin: PluginManifest,
        projectBasePath: String?
    ): Set<InstallScope> {
        val scopes = buildList<InstallScope> {
            projectBasePath?.let { add(InstallScope.Project(File(it))) }
            add(InstallScope.Global)
        }
        return scopes.filterTo(mutableSetOf()) { scope ->
            val primary = InstallPaths.destFor(component, plugin, scope).toPath()
            Files.exists(primary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(primary)
        }
    }
}
