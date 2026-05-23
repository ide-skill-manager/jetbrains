package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallStatus
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillEntry
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The Agentry tool window. All heavy work (git, file IO) runs in `Task.Backgroundable` —
 * the previous version did filesystem IO on the EDT during uninstall, which is now fixed.
 */
class AgentryToolWindowPanel(private val project: Project) {

    private val skillModel = DefaultListModel<SkillEntry>()
    private val skillList = JBList(skillModel).apply {
        cellRenderer = SimpleListCellRenderer.create<SkillEntry>("") { entry ->
            // Manifest fields come from untrusted registries — escape before embedding in HTML.
            val name = StringUtil.escapeXmlEntities(
                entry.manifest.displayName.ifBlank { entry.manifest.name }
            )
            val version = StringUtil.escapeXmlEntities(entry.manifest.version)
            val description = StringUtil.escapeXmlEntities(entry.manifest.description.take(120))
            val status = when (entry.status) {
                InstallStatus.INSTALLED -> "✓ installed"
                InstallStatus.ERROR -> "⚠ error"
                else -> ""
            }
            "<html><b>$name</b> <small>v$version</small>" +
                "<br/><small>$description</small>" +
                "<br/><small style='color:#888'>$status</small></html>"
        }
    }
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Ready")
    private val installButton = JButton("Install")
    private val removeButton = JButton("Remove")
    private val refreshButton = JButton("Refresh")

    val root: JPanel = buildPanel()

    private var allEntries: List<SkillEntry> = emptyList()

    init {
        refreshButton.addActionListener { refresh() }
        installButton.addActionListener { installSelected() }
        removeButton.addActionListener { removeSelected() }
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterList()
            override fun removeUpdate(e: DocumentEvent) = filterList()
            override fun changedUpdate(e: DocumentEvent) = filterList()
        })
    }

    private fun buildPanel(): JPanel {
        val panel = JPanel(BorderLayout(4, 4))
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(searchField); add(Box.createHorizontalGlue()); add(refreshButton)
        }
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(skillList), BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout()).apply {
            add(JPanel().apply { add(installButton); add(removeButton) }, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }
        panel.add(bottom, BorderLayout.SOUTH)
        return panel
    }

    /** Re-fetch all enabled registries and rebuild the list. */
    fun refresh() {
        val settings = AgentrySettings.getInstance()
        val sources = settings.registrySources.map {
            RegistrySource(it.url, it.ref, it.enabled, it.displayName)
        }
        if (sources.none { it.enabled }) {
            statusLabel.text = "No enabled registries. Add one in Settings | Tools | Agentry."
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: loading skills", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val manifests = RegistryManager.getInstance().fetchAll(sources, indicator).values.flatten()
                    val installer = SkillInstaller.getInstance()
                    val basePath = project.basePath
                    val entries = manifests.map { m ->
                        val installed = installer.isInstalled(m.name, settings.defaultInstallTarget, basePath)
                        SkillEntry(m, if (installed) InstallStatus.INSTALLED else InstallStatus.NOT_INSTALLED)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        allEntries = entries
                        filterList()
                        statusLabel.text = "${entries.size} skill(s) found"
                    }
                }
            }
        )
    }

    private fun filterList() {
        val q = searchField.text.trim().lowercase()
        skillModel.clear()
        allEntries
            .filter { q.isBlank() || it.manifest.name.lowercase().contains(q) || it.manifest.description.lowercase().contains(q) }
            .forEach { skillModel.addElement(it) }
    }

    private fun installSelected() {
        val entry = skillList.selectedValue ?: run {
            statusLabel.text = "Select a skill to install"; return
        }
        val settings = AgentrySettings.getInstance()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: installing ${entry.manifest.name}", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val result = SkillInstaller.getInstance()
                        .install(entry.manifest, settings.defaultInstallTarget, project.basePath)
                    ApplicationManager.getApplication().invokeLater {
                        if (result.isSuccess) {
                            statusLabel.text = "Installed '${entry.manifest.name}'"
                            notify("Skill '${entry.manifest.name}' installed.", NotificationType.INFORMATION)
                        } else {
                            statusLabel.text = "Failed to install '${entry.manifest.name}'"
                            notify(
                                "Failed to install '${entry.manifest.name}': ${result.exceptionOrNull()?.message}",
                                NotificationType.ERROR
                            )
                        }
                        refresh()
                    }
                }
            }
        )
    }

    private fun removeSelected() {
        val entry = skillList.selectedValue ?: run {
            statusLabel.text = "Select a skill to remove"; return
        }
        val settings = AgentrySettings.getInstance()
        // Uninstall does file IO — push it off the EDT.
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: removing ${entry.manifest.name}", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val result = SkillInstaller.getInstance()
                        .uninstall(entry.manifest.name, settings.defaultInstallTarget, project.basePath)
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = if (result.isSuccess) {
                            "Removed '${entry.manifest.name}'"
                        } else {
                            "Failed to remove '${entry.manifest.name}'"
                        }
                        refresh()
                    }
                }
            }
        )
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Agentry")
            .createNotification(content, type)
            .notify(project)
    }
}
