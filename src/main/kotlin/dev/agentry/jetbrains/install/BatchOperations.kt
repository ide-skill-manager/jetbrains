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
        runBatch(project, "install", skillNames, prefetchManifests = true) { name, indicator, manifestsByName ->
            val manifest = manifestsByName[name]
                ?: return@runBatch BatchOutcome(name, false, "not found in any enabled registry")
            indicator.text = "Installing $name…"
            val r = SkillInstaller.getInstance()
                .install(manifest, AgentrySettings.getInstance().defaultInstallTarget, project.basePath)
            BatchOutcome(name, r.isSuccess, r.exceptionOrNull()?.message)
        }
    }

    fun uninstallByNames(project: Project, skillNames: List<String>) {
        runBatch(project, "remove", skillNames, prefetchManifests = false) { name, indicator, _ ->
            indicator.text = "Removing $name…"
            val r = SkillInstaller.getInstance()
                .uninstall(name, AgentrySettings.getInstance().defaultInstallTarget, project.basePath)
            BatchOutcome(name, r.isSuccess, r.exceptionOrNull()?.message)
        }
    }

    /**
     * Shared scaffolding for both install and uninstall. One background task per batch,
     * determinate progress, per-item Result aggregation, single summary notification,
     * single `SKILLS_CHANGED` event. Cancellation interrupts the loop and the summary
     * reports it explicitly.
     */
    private fun runBatch(
        project: Project,
        verb: String,
        skillNames: List<String>,
        prefetchManifests: Boolean,
        perItem: (name: String, ProgressIndicator, Map<String, SkillManifest>) -> BatchOutcome
    ) {
        if (skillNames.isEmpty()) return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: ${verb}ing ${skillNames.size} skill(s)", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    val manifestsByName: Map<String, SkillManifest> = if (prefetchManifests) {
                        indicator.text = "Refreshing registries…"
                        val sources = AgentrySettings.getInstance().registrySources
                            .map { RegistrySource(it.url, it.ref, it.enabled, it.displayName) }
                            .filter { it.enabled }
                        RegistryManager.getInstance().fetchAll(sources, indicator).values.flatten()
                            .associateBy { it.name }
                    } else emptyMap()
                    val results = mutableListOf<BatchOutcome>()
                    skillNames.forEachIndexed { idx, name ->
                        if (indicator.isCanceled) return@forEachIndexed
                        indicator.fraction = idx.toDouble() / skillNames.size
                        results += perItem(name, indicator, manifestsByName)
                    }
                    notifySummary(project, verb, results, cancelled = indicator.isCanceled)
                    publishChanged(project)
                }
            }
        )
    }

    private fun notifySummary(
        project: Project,
        verb: String,
        results: List<BatchOutcome>,
        cancelled: Boolean
    ) {
        val succeeded = results.count { it.success }
        val failed = results.count { !it.success }
        val verbed = "${verb.replaceFirstChar { it.titlecase() }}ed"
        val firstErr = results.firstOrNull { !it.success }?.message()?.let { " First error: $it" }.orEmpty()
        val type = when {
            failed == 0 && !cancelled -> NotificationType.INFORMATION
            succeeded == 0 -> NotificationType.ERROR
            else -> NotificationType.WARNING
        }
        val body = buildString {
            append("$verbed $succeeded skill(s)")
            if (failed > 0) append(", $failed failed.$firstErr") else append(".")
            if (cancelled) append(" (cancelled)")
        }
        ApplicationManager.getApplication().invokeLater(
            { if (!project.isDisposed) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Agentry")
                    .createNotification(body, type)
                    .notify(project)
            } },
            { project.isDisposed }
        )
    }

    private fun publishChanged(project: Project) {
        ApplicationManager.getApplication().invokeLater(
            { if (!project.isDisposed) project.messageBus.syncPublisher(AgentryTopics.SKILLS_CHANGED).skillsChanged() },
            { project.isDisposed }
        )
    }

    private data class BatchOutcome(val name: String, val success: Boolean, val errorMessage: String?) {
        fun message(): String = "${name}: ${errorMessage ?: "unknown error"}"
    }

    companion object {
        fun getInstance(): BatchOperations =
            ApplicationManager.getApplication().getService(BatchOperations::class.java)
    }
}
