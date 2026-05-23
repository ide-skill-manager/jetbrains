package dev.agentry.jetbrains

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.agentry.jetbrains.config.AgentryProjectConfig
import dev.agentry.jetbrains.settings.AgentrySettings
import dev.agentry.jetbrains.sync.ProjectSyncService

/**
 * Project-open hook (modern `ProjectActivity`, replacing the deprecated `ProjectManagerListener`
 * pattern). Two behaviours:
 *
 * 1. If `.agentry/config.yaml` exists and the project is trusted in [AgentrySettings] *and*
 *    auto-sync is enabled, sync immediately.
 * 2. Otherwise, surface a balloon notification offering "Trust and sync" / "Sync once" so the
 *    user gets one-click opt-in without giving blanket consent to every repo they open.
 *
 * A VFS listener on `.agentry/config.yaml` is also registered for the lifetime of the
 * project. When the file changes and the project is trusted *and* `autoSyncOnOpen` is on,
 * the listener triggers an automatic re-sync. In the non-trusted / opt-out case it stays a
 * no-op — users have to explicitly re-run the sync via the `Agentry: Sync Config` action.
 */
class AgentryStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        if (AgentryProjectConfig.loadFrom(basePath) == null) return

        val settings = AgentrySettings.getInstance()
        if (settings.autoSyncOnOpen && settings.isTrusted(basePath)) {
            ProjectSyncService.getInstance(project).syncAsync(showSummary = true)
        } else {
            offerTrustPrompt(project, basePath, settings)
        }

        registerConfigWatcher(project)
    }

    private fun offerTrustPrompt(project: Project, basePath: String, settings: AgentrySettings) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Agentry")
        val notification: Notification = group.createNotification(
            "Agentry configuration detected",
            "This project has a .agentry/config.yaml. Sync skills from the listed registries?",
            NotificationType.INFORMATION
        )
        notification.addAction(object : NotificationAction("Sync once") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                n.expire()
                ProjectSyncService.getInstance(project).syncAsync(showSummary = true)
            }
        })
        notification.addAction(object : NotificationAction("Trust and auto-sync") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                n.expire()
                settings.trustProject(basePath)
                settings.autoSyncOnOpen = true
                ProjectSyncService.getInstance(project).syncAsync(showSummary = true)
            }
        })
        notification.notify(project)
    }

    private fun registerConfigWatcher(project: Project) {
        val basePath = project.basePath ?: return
        // VFS event paths are always system-independent (forward slashes) regardless of
        // platform; `project.basePath` on Windows can be `C:\…`. Normalise both sides so
        // the equality check actually fires on Windows.
        val configPath = FileUtil.toSystemIndependentName("$basePath/${AgentryProjectConfig.CONFIG_PATH}")
        val listener = AsyncFileListener { events: List<VFileEvent> ->
            val touched = events.any { FileUtil.toSystemIndependentName(it.path) == configPath }
            if (!touched) null
            else object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    val settings = AgentrySettings.getInstance()
                    if (settings.autoSyncOnOpen && settings.isTrusted(basePath)) {
                        ProjectSyncService.getInstance(project).syncAsync(showSummary = true)
                    }
                }
            }
        }
        // The plugin disposable lives as long as the project is open.
        VirtualFileManager.getInstance()
            .addAsyncFileListener(listener, AgentryDisposable.forProject(project))
    }
}
