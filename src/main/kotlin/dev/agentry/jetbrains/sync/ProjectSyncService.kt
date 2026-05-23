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
        val settings = AgentrySettings.getInstance()
        val registry = RegistryManager.getInstance()
        val installer = SkillInstaller.getInstance()
        val manifests = registry.fetchAll(sources, indicator).values.flatten()

        var installed = 0
        config.skills.forEach { dep ->
            indicator?.text = "Installing skill: ${dep.name}"
            val manifest = manifests.firstOrNull { it.name == dep.name } ?: run {
                log.warn("Skill '${dep.name}' not found in any configured registry")
                return@forEach
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

    /** Sync in a background task, notify on completion. */
    fun syncAsync(showSummary: Boolean = true) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: syncing skills", true) {
                override fun run(indicator: ProgressIndicator) {
                    val installed = syncBlocking(indicator)
                    if (showSummary && installed > 0) {
                        notify("Installed $installed skill(s) from .agentry/config.yaml")
                    }
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
