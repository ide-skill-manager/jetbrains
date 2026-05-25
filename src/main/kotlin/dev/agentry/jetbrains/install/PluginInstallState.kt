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
 * Canonical-containment guard (Project scope only): even if the leaf exists and is not itself
 * a symlink, an intermediate directory (e.g. `<project>/.claude`) could be a symlink pointing
 * outside the project. For [InstallScope.Project], [locationsOf] resolves the canonical primary
 * path and verifies it sits inside the project dir. This guard is intentionally skipped for
 * [InstallScope.Global]: users commonly symlink `~/.claude` or `~/.copilot` to a different
 * filesystem (NAS, separate disk, `~/Library/...`), which is legitimate user-managed
 * configuration. The leaf-is-not-symlink check still applies to both scopes.
 *
 * Tracks the *primary* destination per scope only (`destFor`, not all of `destinationsFor`).
 * Partial dual-write loss (user manually deleted one of the dual-write siblings for any
 * component type — agents at `.github/` + `.claude/`, skills at `.copilot/` + `.claude/`,
 * etc.) leaves the install considered present as long as the primary survives —
 * documented in `docs/plans/install-target-picker.md`.
 */
object PluginInstallState {

    /**
     * The set of scopes [component] is currently installed in. Empty when not installed anywhere;
     * one-element set when only project or user; two-element set when both.
     *
     * Only counts a scope as present when the primary destination (a) exists without
     * `NOFOLLOW_LINKS`, (b) is not itself a symlink, **and** (c) its canonical path is
     * contained within the scope's expected base directory. Condition (c) catches the
     * intermediate-symlink spoof where `<project>/.claude` is a symlink to `/etc/` — the
     * leaf could be a real file, yet the install is outside the project.
     */
    fun locationsOf(
        component: PluginComponent,
        plugin: PluginManifest,
        projectBasePath: String?
    ): Set<InstallScope> {
        val scopes = buildList<InstallScope> {
            projectBasePath?.takeIf { it.isNotBlank() }?.let { add(InstallScope.Project(File(it))) }
            add(InstallScope.Global)
        }
        return scopes.filterTo(mutableSetOf()) { scope ->
            val primaryFile = InstallPaths.destFor(component, plugin, scope)
            val primaryPath = primaryFile.toPath()
            if (!Files.exists(primaryPath, LinkOption.NOFOLLOW_LINKS)) return@filterTo false
            if (Files.isSymbolicLink(primaryPath)) return@filterTo false
            // Scope-appropriate intermediate-symlink defence:
            //   - Project scope: project dir is untrusted (cloned repo, potential attacker-controlled
            //     intermediate symlinks). Enforce canonical primary path under canonical projectDir.
            //   - Global scope: user's own home — symlinking ~/.claude or ~/.copilot to a NAS,
            //     separate disk, or ~/Library/... is legitimate user-managed configuration. Skip
            //     the canonical-root check; the leaf-is-not-symlink check (above) still applies.
            when (scope) {
                is InstallScope.Project -> runCatching {
                    val canonicalRoot = scope.projectDir.canonicalFile.toPath()
                    val canonicalPrimary = primaryFile.canonicalFile.toPath()
                    canonicalPrimary.startsWith(canonicalRoot)
                }.getOrElse { false }
                is InstallScope.Global -> true  // user-managed symlinks honoured
            }
        }
    }
}
