package dev.agentry.jetbrains.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.PluginInstallReport
import dev.agentry.jetbrains.install.PluginInstaller
import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.ui.toolwindow.AgentryNode
import dev.agentry.jetbrains.util.InputValidation
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.swing.tree.TreeNode

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

class InstallComponentsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        @Suppress("UNCHECKED_CAST")
        val nodes = (e.dataContext.getData(SELECTED_COMPONENTS_DATA_KEY) as? List<AgentryNode.Component>).orEmpty()
        if (nodes.isEmpty()) return
        runComponentOp(project, nodes, install = true)
    }
}

class UninstallComponentsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        @Suppress("UNCHECKED_CAST")
        val nodes = (e.dataContext.getData(SELECTED_COMPONENTS_DATA_KEY) as? List<AgentryNode.Component>).orEmpty()
        if (nodes.isEmpty()) return
        runComponentOp(project, nodes, install = false)
    }
}

/**
 * Shared scaffold for both component-install and component-uninstall flows. Groups the
 * selected components by their parent plugin manifest, dispatches one batch per plugin,
 * aggregates results into a single end-of-task notification, and publishes SKILLS_CHANGED.
 */
private fun runComponentOp(project: Project, nodes: List<AgentryNode.Component>, install: Boolean) {
    val verb = if (install) "install" else "uninstall"
    val title = "Agentry: ${verb}ing ${nodes.size} component(s)"
    ProgressManager.getInstance().run(
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val basePath = project.basePath?.let { File(it) }
                val scope: InstallScope = if (basePath != null) InstallScope.Project(basePath) else InstallScope.Global
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
private fun uninstallComponents(
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
            destinations.forEach { dest ->
                val destPath = dest.toPath()
                if (!Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) return@forEach
                if (Files.isSymbolicLink(destPath)) {
                    throw SecurityException("Refusing to delete symlinked install destination: $dest")
                }
                if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
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
