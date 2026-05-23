package dev.agentry.jetbrains.install

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.agentry.jetbrains.actions.AgentryTopics
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings

/**
 * Batch install / uninstall. One [Task.Backgroundable] per batch so the UI shows a single
 * determinate progress bar; per-skill failures are collected and surfaced as one summary
 * notification at the end (not one balloon per skill).
 *
 * Always publishes [AgentryTopics.SKILLS_CHANGED] when done — even on partial failure —
 * so any subscribed view re-renders.
 */
@Service(Service.Level.APP)
class BatchOperations {

    fun installByNames(project: Project, skillNames: List<String>) {
        if (skillNames.isEmpty()) return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: installing ${skillNames.size} skill(s)", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    val settings = AgentrySettings.getInstance()
                    val sources = settings.registrySources.map {
                        RegistrySource(it.url, it.ref, it.enabled, it.displayName)
                    }.filter { it.enabled }
                    indicator.text = "Refreshing registries…"
                    val manifests = RegistryManager.getInstance().fetchAll(sources, indicator).values.flatten()
                    val byName: Map<String, SkillManifest> = manifests.associateBy { it.name }
                    val results = mutableListOf<BatchOutcome>()
                    skillNames.forEachIndexed { idx, name ->
                        if (indicator.isCanceled) return@forEachIndexed
                        indicator.fraction = idx.toDouble() / skillNames.size
                        indicator.text = "Installing $name…"
                        val manifest = byName[name]
                        if (manifest == null) {
                            results += BatchOutcome(name, success = false, "not found in any enabled registry")
                            return@forEachIndexed
                        }
                        val r = SkillInstaller.getInstance()
                            .install(manifest, settings.defaultInstallTarget, project.basePath)
                        results += BatchOutcome(
                            name = name,
                            success = r.isSuccess,
                            errorMessage = r.exceptionOrNull()?.message
                        )
                    }
                    notifySummary(project, "install", results)
                    publishChanged(project)
                }
            }
        )
    }

    fun uninstallByNames(project: Project, skillNames: List<String>) {
        if (skillNames.isEmpty()) return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: removing ${skillNames.size} skill(s)", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    val settings = AgentrySettings.getInstance()
                    val results = mutableListOf<BatchOutcome>()
                    skillNames.forEachIndexed { idx, name ->
                        if (indicator.isCanceled) return@forEachIndexed
                        indicator.fraction = idx.toDouble() / skillNames.size
                        indicator.text = "Removing $name…"
                        val r = SkillInstaller.getInstance()
                            .uninstall(name, settings.defaultInstallTarget, project.basePath)
                        results += BatchOutcome(
                            name = name,
                            success = r.isSuccess,
                            errorMessage = r.exceptionOrNull()?.message
                        )
                    }
                    notifySummary(project, "remove", results)
                    publishChanged(project)
                }
            }
        )
    }

    private fun notifySummary(project: Project, verb: String, results: List<BatchOutcome>) {
        val succeeded = results.count { it.success }
        val failed = results.count { !it.success }
        val (type, body) = when {
            failed == 0 -> NotificationType.INFORMATION to "${verb.replaceFirstChar { it.titlecase() }}ed $succeeded skill(s)."
            succeeded == 0 -> NotificationType.ERROR to "Failed to $verb ${results.size} skill(s). First error: ${results.first { !it.success }.message()}"
            else -> NotificationType.WARNING to "${verb.replaceFirstChar { it.titlecase() }}ed $succeeded skill(s), $failed failed. First error: ${results.first { !it.success }.message()}"
        }
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Agentry")
                .createNotification(body, type)
                .notify(project)
        }
    }

    private fun publishChanged(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(AgentryTopics.SKILLS_CHANGED).skillsChanged()
        }
    }

    private data class BatchOutcome(val name: String, val success: Boolean, val errorMessage: String?) {
        fun message(): String = "${name}: ${errorMessage ?: "unknown error"}"
    }

    companion object {
        fun getInstance(): BatchOperations =
            ApplicationManager.getApplication().getService(BatchOperations::class.java)
    }
}
