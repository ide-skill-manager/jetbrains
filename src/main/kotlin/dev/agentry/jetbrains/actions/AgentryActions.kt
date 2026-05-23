package dev.agentry.jetbrains.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings
import dev.agentry.jetbrains.sync.ProjectSyncService

/**
 * Discoverable, scriptable actions for every operation the tool window exposes.
 * Agents — and humans using "Find Action" — drive these via `ActionManager.fireAction(id)`
 * or by name. Logic lives in the underlying services; these are thin shells.
 *
 * Action IDs:
 *   Agentry.Install      — prompt for skill name, install from any enabled registry
 *   Agentry.Remove       — prompt for skill name, uninstall from default target
 *   Agentry.Refresh      — fetch all enabled registries
 *   Agentry.SyncConfig   — apply .agentry/config.yaml for the current project
 *   Agentry.AddRegistry  — prompt for URL+ref, add to settings
 */

class RefreshAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Agentry: refresh", true) {
            override fun run(indicator: ProgressIndicator) {
                val settings = AgentrySettings.getInstance()
                val sources = settings.registrySources.map {
                    RegistrySource(it.url, it.ref, it.enabled, it.displayName)
                }
                RegistryManager.getInstance().fetchAll(sources, indicator)
            }
        })
    }
}

class SyncConfigAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProjectSyncService.getInstance(project).syncAsync()
    }
}

class InstallSkillAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val name = Messages.showInputDialog(
            project, "Skill name to install:", "Agentry: install skill", null
        )?.trim()?.takeIf { it.isNotBlank() } ?: return
        installByName(project, name)
    }

    private fun installByName(project: Project, skillName: String) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: install $skillName", true) {
                override fun run(indicator: ProgressIndicator) {
                    val settings = AgentrySettings.getInstance()
                    val sources = settings.registrySources.map {
                        RegistrySource(it.url, it.ref, it.enabled, it.displayName)
                    }
                    val manifest = RegistryManager.getInstance()
                        .fetchAll(sources, indicator).values.flatten()
                        .firstOrNull { it.name == skillName }
                    if (manifest == null) {
                        notifyOnEdt(project, "Skill '$skillName' not found in any enabled registry")
                        return
                    }
                    SkillInstaller.getInstance()
                        .install(manifest, settings.defaultInstallTarget, project.basePath)
                }
            }
        )
    }
}

class RemoveSkillAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val name = Messages.showInputDialog(
            project, "Skill name to remove:", "Agentry: remove skill", null
        )?.trim()?.takeIf { it.isNotBlank() } ?: return
        val settings = AgentrySettings.getInstance()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: remove $name", false) {
                override fun run(indicator: ProgressIndicator) {
                    SkillInstaller.getInstance()
                        .uninstall(name, settings.defaultInstallTarget, project.basePath)
                }
            }
        )
    }
}

class AddRegistryAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val url = Messages.showInputDialog(
            project, "Registry git URL:", "Agentry: add registry", null
        )?.trim() ?: return
        val ref = Messages.showInputDialog(
            project, "Ref (branch / tag / commit; default HEAD):", "Agentry: add registry", null
        )?.trim()?.ifBlank { "HEAD" } ?: "HEAD"
        val settings = AgentrySettings.getInstance()
        settings.registrySources.add(
            AgentrySettings.RegistrySourceState(url = url, ref = ref, enabled = true, displayName = url)
        )
    }
}

private fun notifyOnEdt(project: Project, message: String) {
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        Messages.showWarningDialog(project, message, "Agentry")
    }
}

/** Helper: which install targets accept a null project base path. */
@Suppress("unused")
internal val PROJECT_INDEPENDENT_TARGETS = setOf(InstallTarget.CLAUDE_USER, InstallTarget.AGENTRY_CACHE)
