package dev.agentry.jetbrains

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import dev.agentry.jetbrains.config.AgentryProjectConfig
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings

/**
 * Hooks into project open events to auto-sync skills declared in .agentry/config.yaml.
 */
class AgentryStartup : ProjectManagerListener {

    private val log = logger<AgentryStartup>()

    override fun projectOpened(project: Project) {
        val basePath = project.basePath ?: return
        val config = AgentryProjectConfig.loadFrom(basePath) ?: return

        if (config.skills.isEmpty() && config.sources.isEmpty()) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: syncing skills", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    syncProjectSkills(project, config, indicator)
                }
            }
        )
    }

    private fun syncProjectSkills(
        project: Project,
        config: AgentryProjectConfig,
        indicator: com.intellij.openapi.progress.ProgressIndicator
    ) {
        val settings = AgentrySettings.getInstance()
        val installer = SkillInstaller()
        val registryManager = RegistryManager()
        val sources = config.toRegistrySources()
        val allSkills = registryManager.fetchAll(sources, indicator).values.flatten()
        val basePath = project.basePath ?: return

        var installed = 0
        config.skills.forEach { dep ->
            indicator.text = "Installing skill: ${dep.name}"
            val manifest = allSkills.firstOrNull { it.name == dep.name } ?: return@forEach
            val target = runCatching { InstallTarget.valueOf(dep.target) }
                .getOrDefault(settings.defaultInstallTarget)
            if (!installer.isInstalled(dep.name, target, basePath)) {
                installer.install(manifest, target, basePath)
                installed++
            }
        }

        if (installed > 0) {
            notify(project, "Agentry", "Installed $installed skill(s) from .agentry/config.yaml", NotificationType.INFORMATION)
        }
    }

    private fun notify(project: Project, groupId: String, content: String, type: NotificationType) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(groupId)
                .createNotification(content, type)
                .notify(project)
        }
    }
}
