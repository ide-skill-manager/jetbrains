package dev.agentry.jetbrains.sync

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.agentry.jetbrains.config.AgentryProjectConfig
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings

/**
 * Project-scoped service that reads `.agentry/config.yaml` and installs any declared
 * skills that aren't already present. Used by both the startup activity and the
 * "Agentry: Sync Config" action — single code path for human and agent triggers.
 */
@Service(Service.Level.PROJECT)
class ProjectSyncService(private val project: Project) {

    private val log = logger<ProjectSyncService>()

    /** Sync now, on the calling thread. Returns the number of skills installed. */
    fun syncBlocking(indicator: ProgressIndicator? = null): Int {
        val basePath = project.basePath ?: return 0
        val config = AgentryProjectConfig.loadFrom(basePath) ?: return 0
        if (config.skills.isEmpty() && config.sources.isEmpty()) return 0

        val sources = config.toRegistrySources()
        // Map of `source name` (as declared in config) → resolved registry URL. Used to
        // honour the `registry` field on each skill dependency so we don't accidentally
        // install a same-named skill from the wrong source.
        val urlByName = config.sources.associate { it.name to it.url }
        val settings = AgentrySettings.getInstance()
        val registry = RegistryManager.getInstance()
        val installer = SkillInstaller.getInstance()
        val manifests = registry.fetchAll(sources, indicator).values.flatten()

        var installed = 0
        config.skills.forEach { dep ->
            indicator?.text = "Installing skill: ${dep.name}"
            // Constrain by registry when declared. Falls back to first-match when the
            // dep doesn't pin a registry — but we log so operators can see ambiguity.
            val candidates = manifests.filter { it.name == dep.name }
            val manifest = when {
                dep.registry.isNotBlank() -> {
                    val url = urlByName[dep.registry]
                    if (url == null) {
                        log.warn("Skill '${dep.name}' references unknown registry '${dep.registry}'")
                        return@forEach
                    }
                    candidates.firstOrNull { it.sourceRegistry == url }
                }
                candidates.size > 1 -> {
                    log.warn(
                        "Skill '${dep.name}' is offered by ${candidates.size} registries; " +
                            "pin one via the dep's `registry` field. Picking ${candidates.first().sourceRegistry}."
                    )
                    candidates.first()
                }
                else -> candidates.firstOrNull()
            }
            if (manifest == null) {
                log.warn("Skill '${dep.name}' not found in any configured registry")
                return@forEach
            }
            if (dep.version != "*" && manifest.version != dep.version) {
                log.warn(
                    "Skill '${dep.name}' version mismatch: config wants '${dep.version}', " +
                        "registry offers '${manifest.version}'. Installing what the registry has — " +
                        "version pinning is registry-side via the source `ref`, not skill-side."
                )
            }
            val target = enumValues<InstallTarget>().firstOrNull { it.name == dep.target }
                ?: settings.defaultInstallTarget
            if (!installer.isInstalled(dep.name, target, basePath)) {
                val result = installer.install(manifest, target, basePath)
                if (result.isSuccess) installed++
            }
        }
        return installed
    }

    /**
     * Sync in a background task. Optionally notify on completion and fire [onComplete]
     * once the task finishes (whether or not anything was installed) — used by callers
     * that need to publish a state-changed event after the async work lands.
     */
    fun syncAsync(showSummary: Boolean = true, onComplete: () -> Unit = {}) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: syncing skills", true) {
                override fun run(indicator: ProgressIndicator) {
                    val installed = syncBlocking(indicator)
                    if (showSummary && installed > 0) {
                        notify("Installed $installed skill(s) from .agentry/config.yaml")
                    }
                    onComplete()
                }
            }
        )
    }

    private fun notify(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Agentry")
            .createNotification(content, NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): ProjectSyncService =
            project.getService(ProjectSyncService::class.java)
    }
}
