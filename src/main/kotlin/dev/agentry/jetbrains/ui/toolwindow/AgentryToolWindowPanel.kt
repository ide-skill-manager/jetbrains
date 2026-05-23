package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallStatus
import dev.agentry.jetbrains.model.SkillEntry
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * The main Agentry tool window panel.
 * Shows available skills from configured registries, installed skills,
 * and provides install/update/remove actions.
 */
class AgentryToolWindowPanel(private val project: Project) {

    private val skillListModel = DefaultListModel<SkillEntry>()
    private val skillList = JBList(skillListModel).apply {
        cellRenderer = SkillListCellRenderer()
    }
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Ready")
    private val installButton = JButton("Install")
    private val removeButton = JButton("Remove")
    private val refreshButton = JButton("Refresh")

    val root: JPanel = buildPanel()

    init {
        setupActions()
    }

    private fun buildPanel(): JPanel {
        val panel = JPanel(BorderLayout(4, 4))

        // Toolbar at top
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(searchField)
            add(Box.createHorizontalGlue())
            add(refreshButton)
        }
        panel.add(toolbar, BorderLayout.NORTH)

        // Skill list in center
        panel.add(JBScrollPane(skillList), BorderLayout.CENTER)

        // Action buttons + status at bottom
        val bottomPanel = JPanel(BorderLayout())
        val btnPanel = JPanel().apply {
            add(installButton)
            add(removeButton)
        }
        bottomPanel.add(btnPanel, BorderLayout.WEST)
        bottomPanel.add(statusLabel, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun setupActions() {
        refreshButton.addActionListener { refresh() }
        installButton.addActionListener { installSelected() }
        removeButton.addActionListener { removeSelected() }

        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = filterList()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = filterList()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = filterList()
        })
    }

    private var allEntries: List<SkillEntry> = emptyList()

    fun refresh() {
        val settings = AgentrySettings.getInstance()
        val sources = settings.registrySources.map {
            dev.agentry.jetbrains.model.RegistrySource(url = it.url, ref = it.ref, enabled = it.enabled, displayName = it.displayName)
        }

        if (sources.isEmpty()) {
            statusLabel.text = "No registry sources configured. Add some in Settings | Tools | Agentry."
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: loading skills", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.text = "Fetching skill registries..."
                    val registryManager = RegistryManager()
                    val installer = SkillInstaller()
                    val manifests = registryManager.fetchAll(sources, indicator).values.flatten()
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
        val query = searchField.text.trim().lowercase()
        skillListModel.clear()
        allEntries
            .filter { query.isBlank() || it.manifest.name.lowercase().contains(query) || it.manifest.description.lowercase().contains(query) }
            .forEach { skillListModel.addElement(it) }
    }

    private fun installSelected() {
        val entry = skillList.selectedValue ?: run {
            statusLabel.text = "Select a skill to install"
            return
        }
        val settings = AgentrySettings.getInstance()
        val installer = SkillInstaller()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: installing ${entry.manifest.name}", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val result = installer.install(entry.manifest, settings.defaultInstallTarget, project.basePath)
                    ApplicationManager.getApplication().invokeLater {
                        if (result.isSuccess) {
                            statusLabel.text = "Installed '${entry.manifest.name}'"
                            notify("Skill '${entry.manifest.name}' installed successfully.", NotificationType.INFORMATION)
                        } else {
                            statusLabel.text = "Failed to install '${entry.manifest.name}'"
                            notify("Failed to install '${entry.manifest.name}': ${result.exceptionOrNull()?.message}", NotificationType.ERROR)
                        }
                        refresh()
                    }
                }
            }
        )
    }

    private fun removeSelected() {
        val entry = skillList.selectedValue ?: run {
            statusLabel.text = "Select a skill to remove"
            return
        }
        val settings = AgentrySettings.getInstance()
        val installer = SkillInstaller()
        installer.uninstall(entry.manifest.name, settings.defaultInstallTarget, project.basePath)
        statusLabel.text = "Removed '${entry.manifest.name}'"
        refresh()
    }

    private fun notify(content: String, type: NotificationType) {
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Agentry")
                .createNotification(content, type)
                .notify(project)
        }
    }

    /** Custom cell renderer for skill list items. */
    private inner class SkillListCellRenderer : ListCellRenderer<SkillEntry> {
        override fun getListCellRendererComponent(
            list: JList<out SkillEntry>,
            value: SkillEntry,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val panel = JPanel(BorderLayout())
            val nameLabel = JBLabel("<html><b>${value.manifest.displayName.ifBlank { value.manifest.name }}</b> v${value.manifest.version}</html>")
            val descLabel = JBLabel("<html><small>${value.manifest.description.take(80)}</small></html>")
            val statusLabel = JBLabel(value.status.name).apply {
                foreground = when (value.status) {
                    InstallStatus.INSTALLED -> java.awt.Color(0, 128, 0)
                    InstallStatus.UPDATE_AVAILABLE -> java.awt.Color(200, 100, 0)
                    InstallStatus.ERROR -> java.awt.Color(200, 0, 0)
                    else -> java.awt.Color.GRAY
                }
            }
            val textPanel = JPanel(BorderLayout())
            textPanel.add(nameLabel, BorderLayout.NORTH)
            textPanel.add(descLabel, BorderLayout.CENTER)
            panel.add(textPanel, BorderLayout.CENTER)
            panel.add(statusLabel, BorderLayout.EAST)
            panel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            if (isSelected) {
                panel.background = list.selectionBackground
                nameLabel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
            }
            return panel
        }
    }
}
