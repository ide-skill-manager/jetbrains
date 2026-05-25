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
import com.intellij.openapi.ui.Messages
import dev.agentry.jetbrains.install.BatchOperations
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings
import dev.agentry.jetbrains.sync.ProjectSyncService
import dev.agentry.jetbrains.util.InputValidation

/**
 * Discoverable, scriptable actions for every operation the tool window exposes.
 * Agents — and humans using "Find Action" — drive these via `ActionManager.fireAction(id)`
 * or by name. UI buttons in the tool window fire these same actions (via `ActionManager`
 * + a data context carrying the pre-selected skill name) so there's a single code path
 * for human and agent flows.
 *
 * When state changes, actions publish to [AgentryTopics.SKILLS_CHANGED] so any subscribed
 * view (currently the tool window) re-renders without each action knowing about each view.
 *
 * Action IDs:
 *   Agentry.Install      — install a skill by name (prompt if no data context)
 *   Agentry.Remove       — uninstall a skill by name (prompt if no data context)
 *   Agentry.Refresh      — fetch all enabled registries
 *   Agentry.SyncConfig   — apply .agentry/config.yaml for the current project
 *   Agentry.AddRegistry  — prompt for URL+ref, add to settings (validates first)
 */

class RefreshAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runRefresh(project)
    }

    companion object {
        fun runRefresh(project: Project) {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Agentry: refresh", true) {
                    override fun run(indicator: ProgressIndicator) {
                        val settings = AgentrySettings.getInstance()
                        val sources = settings.registrySources.map {
                            RegistrySource(it.url, it.ref, it.enabled, it.displayName)
                        }
                        RegistryManager.getInstance().fetchAll(sources, indicator)
                        publishChanged(project)
                    }
                }
            )
        }
    }
}

class SyncConfigAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Run with an onComplete hook so SKILLS_CHANGED actually fires when the background
        // sync finishes — without this any subscribed tool window would miss the new state.
        ProjectSyncService.getInstance(project).syncAsync(
            showSummary = true,
            onComplete = { publishChanged(project) }
        )
    }
}

class InstallSkillAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Prefer pre-supplied name (e.g. selected list item from the tool window).
        val name = e.getData(SKILL_NAME_DATA_KEY)?.trim()?.ifBlank { null }
            ?: Messages.showInputDialog(
                project, "Skill name to install:", "Agentry: install skill", null
            )?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        runInstall(project, name)
    }

    companion object {
        fun runInstall(project: Project, skillName: String) {
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
                            notify(
                                project,
                                "Skill '$skillName' not found in any enabled registry",
                                NotificationType.WARNING
                            )
                            return
                        }
                        val result = SkillInstaller.getInstance()
                            .install(manifest, settings.defaultInstallTarget, project.basePath)
                        if (result.isSuccess) {
                            notify(project, "Skill '$skillName' installed.", NotificationType.INFORMATION)
                        } else {
                            notify(
                                project,
                                "Failed to install '$skillName': ${result.exceptionOrNull()?.message}",
                                NotificationType.ERROR
                            )
                        }
                        publishChanged(project)
                    }
                }
            )
        }
    }
}

class RemoveSkillAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val name = e.getData(SKILL_NAME_DATA_KEY)?.trim()?.ifBlank { null }
            ?: Messages.showInputDialog(
                project, "Skill name to remove:", "Agentry: remove skill", null
            )?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        runRemove(project, name)
    }

    companion object {
        fun runRemove(project: Project, skillName: String) {
            val settings = AgentrySettings.getInstance()
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Agentry: remove $skillName", false) {
                    override fun run(indicator: ProgressIndicator) {
                        val result = SkillInstaller.getInstance()
                            .uninstall(skillName, settings.defaultInstallTarget, project.basePath)
                        if (result.isFailure) {
                            notify(
                                project,
                                "Failed to remove '$skillName': ${result.exceptionOrNull()?.message}",
                                NotificationType.ERROR
                            )
                        }
                        publishChanged(project)
                    }
                }
            )
        }
    }
}

class InstallSelectedAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val names = e.getData(SELECTED_SKILLS_DATA_KEY).orEmpty()
        if (names.isEmpty()) return
        val target = e.getData(INSTALL_TARGET_DATA_KEY)
            ?: AgentrySettings.getInstance().defaultInstallTarget
        BatchOperations.getInstance().installByNames(project, names, target)
    }
}

class UninstallSelectedAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val names = e.getData(SELECTED_SKILLS_DATA_KEY).orEmpty()
        if (names.isEmpty()) return
        val target = e.getData(INSTALL_TARGET_DATA_KEY)
            ?: AgentrySettings.getInstance().defaultInstallTarget
        BatchOperations.getInstance().uninstallByNames(project, names, target)
    }
}

class AddRegistryAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = dev.agentry.jetbrains.ui.dialogs.AddRegistryDialog(project)
        if (!dialog.showAndGet()) return
        val result = dialog.result ?: return
        val settings = AgentrySettings.getInstance()
        settings.registrySources.add(
            AgentrySettings.RegistrySourceState(
                url = result.url,
                ref = result.ref,
                enabled = result.enabled,
                displayName = result.name.ifBlank { result.url }
            )
        )
        publishChanged(project)
    }
}

/** Broadcast a "skills changed" event so any subscribed view re-renders. */
internal fun publishChanged(project: Project) {
    ApplicationManager.getApplication().invokeLater {
        project.messageBus.syncPublisher(AgentryTopics.SKILLS_CHANGED).skillsChanged()
    }
}

private fun notify(project: Project, content: String, type: NotificationType) {
    ApplicationManager.getApplication().invokeLater {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Agentry")
            .createNotification(content, type)
            .notify(project)
    }
}
