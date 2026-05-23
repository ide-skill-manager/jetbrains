package dev.agentry.jetbrains.ui.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import dev.agentry.jetbrains.actions.AgentryTopics
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.settings.AgentrySettings
import dev.agentry.jetbrains.ui.dialogs.AddRegistryDialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Settings panel under **Settings | Tools | Agentry**.
 *
 * Backs the registry list with a typed `DefaultListModel<RegistrySourceState>` instead of
 * the previous "url @ ref" string-split round-trip, which silently dropped `enabled` and
 * `displayName` on every apply and corrupted URLs containing ` @ `.
 */
class AgentrySettingsPanel {

    private val registryModel = DefaultListModel<AgentrySettings.RegistrySourceState>()
    private val registryList = JBList(registryModel).apply {
        cellRenderer = SimpleListCellRenderer.create<AgentrySettings.RegistrySourceState>("") { src ->
            "${src.url} @ ${src.ref}${if (!src.enabled) "  (disabled)" else ""}"
        }
    }
    private val defaultTargetCombo = JComboBox(InstallTarget.values())
    private val autoSyncCheck = JCheckBox(
        "Auto-sync .agentry/config.yaml on project open (only for trusted projects)"
    )

    val root: JPanel = buildPanel()

    private fun buildPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(JBLabel("<html><b>Registry Sources (git URLs)</b></html>"), gbc)

        gbc.gridy = 1; gbc.weighty = 0.4; gbc.fill = GridBagConstraints.BOTH
        panel.add(
            JBScrollPane(registryList).apply { preferredSize = Dimension(500, 140) },
            gbc
        )

        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("+ Add Registry…").also { it.addActionListener { onAdd() } })
            add(JButton("Remove").also { it.addActionListener { onRemove() } })
            add(JButton("Toggle enabled").also { it.addActionListener { onToggleEnabled() } })
        }
        gbc.gridy = 2; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(controls, gbc)

        gbc.gridwidth = 1; gbc.gridy = 3; gbc.gridx = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Default install target:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(defaultTargetCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2
        panel.add(autoSyncCheck, gbc)

        // Spacer.
        gbc.gridy = 5; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        return panel
    }

    /**
     * Open [AddRegistryDialog] (same dialog the tool window's "+ Add Registry…" uses) so
     * the user gets URL validation, the `git ls-remote`-driven branch dropdown, and the
     * other affordances. Settings doesn't have a Project directly — we pull it from the
     * panel's data context, falling back to the first open project.
     */
    private fun onAdd() {
        val project = DataManager.getInstance().getDataContext(root).getData(CommonDataKeys.PROJECT)
            ?: ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            javax.swing.JOptionPane.showMessageDialog(
                root,
                "Open a project first — the registry-add dialog needs one to run validation.",
                "Agentry",
                javax.swing.JOptionPane.INFORMATION_MESSAGE
            )
            return
        }
        val dialog = AddRegistryDialog(project)
        if (!dialog.showAndGet()) return
        val result = dialog.result ?: return
        registryModel.addElement(
            AgentrySettings.RegistrySourceState(
                url = result.url,
                ref = result.ref,
                enabled = result.enabled,
                displayName = result.name.ifBlank { result.url }
            )
        )
    }

    private fun onRemove() {
        val selected = registryList.selectedIndex
        if (selected >= 0) registryModel.remove(selected)
    }

    private fun onToggleEnabled() {
        val idx = registryList.selectedIndex
        if (idx < 0) return
        val src = registryModel.get(idx)
        registryModel.set(idx, src.copy(enabled = !src.enabled))
    }

    fun loadSettings(settings: AgentrySettings) {
        registryModel.clear()
        // `data class` already provides a member `copy()` — use it directly.
        settings.registrySources.forEach { registryModel.addElement(it.copy()) }
        defaultTargetCombo.selectedItem = settings.defaultInstallTarget
        autoSyncCheck.isSelected = settings.autoSyncOnOpen
    }

    fun applySettings(settings: AgentrySettings) {
        settings.registrySources = (0 until registryModel.size())
            .map { registryModel.get(it).copy() }
            .toMutableList()
        settings.defaultInstallTarget = defaultTargetCombo.selectedItem as InstallTarget
        settings.autoSyncOnOpen = autoSyncCheck.isSelected
        // Tell every open project's tool window that the registry list changed so it
        // re-fetches and re-renders. Without this, the user's "Apply" doesn't visibly
        // do anything until they hit Refresh in the tool window.
        ProjectManager.getInstance().openProjects.forEach { project ->
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(AgentryTopics.SKILLS_CHANGED).skillsChanged()
            }
        }
    }

    fun isModified(settings: AgentrySettings): Boolean {
        val panelSources = (0 until registryModel.size()).map { registryModel.get(it) }
        return panelSources != settings.registrySources
            || defaultTargetCombo.selectedItem != settings.defaultInstallTarget
            || autoSyncCheck.isSelected != settings.autoSyncOnOpen
    }
}
