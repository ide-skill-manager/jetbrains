package dev.agentry.jetbrains.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.PluginInstallReport
import dev.agentry.jetbrains.install.PluginInstaller
import dev.agentry.jetbrains.install.deleteRecursivelySymlinkSafe
import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.settings.AgentrySettings
import dev.agentry.jetbrains.ui.toolwindow.AgentryNode
import dev.agentry.jetbrains.util.InputValidation
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.swing.tree.TreeNode

private val log = logger<InstallComponentsAction>()

/**
 * Actions that install / uninstall plugin components (skills, commands, hooks, MCP
 * servers, agents) via the [PluginInstaller] pipeline. Distinct from the older
 * `InstallSelectedAction` which installs legacy flat-skill registries through
 * `BatchOperations`. The two pipelines coexist because flat-skill registries don't yet
 * adopt the marketplace shape.
 *
 * Each action receives the user's selected [AgentryNode.Component] list via the
 * [SELECTED_COMPONENTS_DATA_KEY] data key. Components are grouped by their parent
 * plugin manifest so the installer can name and version each install report correctly.
 */

/**
 * Map the action's [DataContext] to the [InstallScope] the install/uninstall should
 * target. Reads the user's pick from the tool-window combo via [INSTALL_TARGET_DATA_KEY];
 * falls back to `AgentrySettings.defaultInstallTarget` for CLI / agent-fired paths that
 * never set the key. `CLAUDE_PROJECT` with a null project base path falls back to
 * `CLAUDE_USER` to keep the resolution total — should be unreachable from the panel
 * (it disables `Project` in the combo when no project is open) but matters for CLI.
 */
internal fun resolveInstallScope(
    dataContext: DataContext,
    projectBasePath: String?,
): InstallScope {
    val target = dataContext.getData(INSTALL_TARGET_DATA_KEY)
        ?: AgentrySettings.getInstance().defaultInstallTarget
    val basePath = projectBasePath?.takeIf { it.isNotBlank() }
    return when (target) {
        InstallTarget.CLAUDE_USER -> InstallScope.Global
        InstallTarget.CLAUDE_PROJECT ->
            if (basePath != null) target.toScope(basePath)
            else {
                log.warn(
                    "resolveInstallScope: CLAUDE_PROJECT requested but no project base path; falling back to Global"
                )
                InstallScope.Global
            }
    }
}

class InstallComponentsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val nodes = e.getData(SELECTED_COMPONENTS_DATA_KEY).orEmpty()
        if (nodes.isEmpty()) return
        val scope = resolveInstallScope(e.dataContext, project.basePath)
        runComponentOp(scope, project, nodes, install = true)
    }
}

class UninstallComponentsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val nodes = e.getData(SELECTED_COMPONENTS_DATA_KEY).orEmpty()
        if (nodes.isEmpty()) return
        val scope = resolveInstallScope(e.dataContext, project.basePath)
        runComponentOp(scope, project, nodes, install = false)
    }
}

/**
 * Shared scaffold for both component-install and component-uninstall flows. Groups the
 * selected components by their parent plugin manifest, dispatches one batch per plugin,
 * aggregates results into a single end-of-task notification, and publishes SKILLS_CHANGED.
 */
private fun runComponentOp(scope: InstallScope, project: Project, nodes: List<AgentryNode.Component>, install: Boolean) {
    val verb = if (install) "install" else "uninstall"
    val title = "Agentry: ${verb}ing ${nodes.size} component(s)"
    ProgressManager.getInstance().run(
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val byPlugin: Map<PluginManifest, List<AgentryNode.Component>> = nodes
                    .mapNotNull { node -> node.parentPluginManifest()?.let { it to node } }
                    .groupBy({ it.first }, { it.second })

                val reports = mutableListOf<PluginInstallReport>()
                val entries = byPlugin.entries.toList()
                entries.forEachIndexed { idx, entry ->
                    if (indicator.isCanceled) return@forEachIndexed
                    val manifest = entry.key
                    val components = entry.value
                    indicator.fraction = idx.toDouble() / entries.size
                    indicator.text = "${verb.replaceFirstChar { it.titlecase() }}ing plugin '${manifest.name}'"
                    val report = if (install) {
                        PluginInstaller.getInstance()
                            .installPlugin(manifest, components.map { it.component }, scope)
                    } else {
                        uninstallComponents(manifest, components.map { it.component }, scope)
                    }
                    reports += report
                }
                notify(project, verb, reports, cancelled = indicator.isCanceled)
                publishChanged(project)
            }
        }
    )
}

/**
 * Walks back up the tree to find the [PluginManifest] this component belongs to.
 * Orphaned nodes (with no `Plugin` parent) are skipped by callers via `mapNotNull` —
 * we never want to install a component without knowing which plugin it came from
 * (the plugin name drives install paths, variable expansion, etc.).
 */
private fun AgentryNode.Component.parentPluginManifest(): PluginManifest? {
    var p: TreeNode? = this.parent
    while (p != null) {
        if (p is AgentryNode.Plugin) return p.manifest
        p = p.parent
    }
    return null
}

/**
 * Uninstall components from disk. Mirror of the install side — every dest path is name-
 * validated AND symlink-checked before removal so an attacker-controlled plugin name
 * can't trick us into walking out of the install root or following a symlink to delete
 * the wrong files. Future work folds this back into `PluginInstaller` proper as a sibling
 * `uninstallPlugin`.
 */
internal fun uninstallComponents(
    plugin: PluginManifest,
    components: List<PluginComponent>,
    scope: InstallScope
): PluginInstallReport {
    val installed = mutableListOf<dev.agentry.jetbrains.install.InstalledComponent>()
    val failed = mutableListOf<dev.agentry.jetbrains.install.ComponentError>()
    val pluginNameOk = InputValidation.isValidComponentName(plugin.name)
    components.forEach { c ->
        if (!pluginNameOk || !InputValidation.isValidComponentName(c.name)) {
            failed += dev.agentry.jetbrains.install.ComponentError(
                c.kind, c.name, "invalid plugin or component name", recoverable = false
            )
            return@forEach
        }
        // Remove every destination the install would have written to — for dual-writing
        // installers (Agent) that's two files; otherwise it's one. A symlink at any dest
        // aborts the whole component to avoid following the link outside the install root.
        val destinations = InstallPaths.destinationsFor(c, plugin, scope)
        runCatching {
            // Scope-appropriate intermediate-symlink defence:
            //   - Project scope: project dir comes from a possibly-untrusted source (cloned repo,
            //     potential attacker-controlled intermediate symlinks). Enforce canonicalDest under
            //     canonical projectDir.
            //   - Global scope: the user's own home — symlinking ~/.claude or ~/.copilot to a NAS,
            //     separate disk, or ~/Library/... is legitimate user-managed configuration. Skip the
            //     canonical-root check; the leaf-is-not-symlink check (below) still applies.
            // Resolved INSIDE runCatching so an IOException here fails this component only —
            // the remaining components in the batch still get processed.
            val canonicalRoot: File? = when (scope) {
                is InstallScope.Project -> scope.projectDir.canonicalFile
                is InstallScope.Global -> null  // user-managed symlinks honoured
            }
            destinations.forEach { dest ->
                val destPath = dest.toPath()
                if (!Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) return@forEach
                if (Files.isSymbolicLink(destPath)) {
                    throw SecurityException("Refusing to delete symlinked install destination: $dest")
                }
                // The canonical path collapses symlinks anywhere along the way; if it doesn't sit
                // under the expected root, the dest reaches outside the install area and we refuse.
                // Only checked for Project scope — see comment above.
                if (canonicalRoot != null) {
                    val canonicalDest = dest.canonicalFile
                    if (!canonicalDest.toPath().startsWith(canonicalRoot.toPath())) {
                        throw SecurityException(
                            "Refusing to delete: canonical path '$canonicalDest' escapes install root '$canonicalRoot'"
                        )
                    }
                }
                val ok = if (dest.isDirectory) deleteRecursivelySymlinkSafe(dest) else dest.delete()
                if (!ok) {
                    throw IOException(
                        "Failed to delete '$dest' (deleteRecursivelySymlinkSafe returned false — likely a " +
                        "permission, open-file, or symlink-traversal issue; check the IDE log for I/O errors)"
                    )
                }
                log.info("Uninstalled '${c.name}' from ${dest.absolutePath}")
            }
        }.onSuccess {
            installed += dev.agentry.jetbrains.install.InstalledComponent(
                c.kind, c.name, destinations.first(), scope
            )
        }.onFailure { e ->
            failed += dev.agentry.jetbrains.install.ComponentError(
                c.kind, c.name, e.message ?: "unknown", recoverable = e is SecurityException
            )
        }
    }
    return PluginInstallReport(plugin.name, installed, failed)
}

private fun notify(project: Project, verb: String, reports: List<PluginInstallReport>, cancelled: Boolean) {
    val installed = reports.sumOf { it.installed.size }
    val failed = reports.sumOf { it.failed.size }
    val firstFail = reports.firstNotNullOfOrNull { it.failed.firstOrNull() }
    val verbed = "${verb.replaceFirstChar { it.titlecase() }}ed"
    val type = when {
        failed == 0 && !cancelled -> NotificationType.INFORMATION
        installed == 0 -> NotificationType.ERROR
        else -> NotificationType.WARNING
    }
    val body = buildString {
        append("$verbed $installed component(s) across ${reports.size} plugin(s)")
        if (failed > 0) append("; $failed failed. First: ${firstFail?.name} (${firstFail?.reason})") else append(".")
        if (cancelled) append(" (cancelled)")
    }
    ApplicationManager.getApplication().invokeLater(
        {
            if (!project.isDisposed) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Agentry")
                    .createNotification(body, type)
                    .notify(project)
            }
        },
        { project.isDisposed }
    )
}

// `publishChanged` is already defined as `internal` in AgentryActions.kt and used here too.
