package dev.agentry.jetbrains.ui.settings

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.settings.AgentrySettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * The Settings panel UI for Agentry (displayed under Settings | Tools | Agentry).
 */
class AgentrySettingsPanel {

    private val registryListModel = DefaultListModel<String>()
    private val registryList = JBList(registryListModel)
    private val urlField = JBTextField(40)
    private val refField = JBTextField(10).apply { text = "HEAD" }
    private val defaultTargetCombo = JComboBox(InstallTarget.values())
    private val autoUpdateCheck = JCheckBox("Automatically update skills")
    private val sidecarPathField = JBTextField(40)

    val root: JPanel = buildPanel()

    private fun buildPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        // Registry sources section
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(JBLabel("<html><b>Registry Sources (git URLs)</b></html>"), gbc)

        gbc.gridy = 1; gbc.weighty = 0.4; gbc.fill = GridBagConstraints.BOTH
        panel.add(JBScrollPane(registryList).apply { preferredSize = java.awt.Dimension(400, 120) }, gbc)

        val addRemovePanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val addBtn = JButton("Add")
        val removeBtn = JButton("Remove")
        addRemovePanel.add(JBLabel("URL:")); addRemovePanel.add(urlField)
        addRemovePanel.add(JBLabel("Ref:")); addRemovePanel.add(refField)
        addRemovePanel.add(addBtn); addRemovePanel.add(removeBtn)

        gbc.gridy = 2; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(addRemovePanel, gbc)

        // Add / remove actions
        addBtn.addActionListener {
            val url = urlField.text.trim()
            if (url.isNotBlank()) {
                registryListModel.addElement("${urlField.text.trim()} @ ${refField.text.trim()}")
                urlField.text = ""
                refField.text = "HEAD"
            }
        }
        removeBtn.addActionListener {
            val selected = registryList.selectedIndex
            if (selected >= 0) registryListModel.remove(selected)
        }

        // Default target
        gbc.gridwidth = 1; gbc.gridy = 3; gbc.gridx = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Default install target:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(defaultTargetCombo, gbc)

        // Auto-update
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2
        panel.add(autoUpdateCheck, gbc)

        // Sidecar path
        gbc.gridy = 5; gbc.gridwidth = 1; gbc.gridx = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Sidecar binary path (optional):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(sidecarPathField, gbc)

        // Filler
        gbc.gridy = 6; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        return panel
    }

    fun loadSettings(settings: AgentrySettings) {
        registryListModel.clear()
        settings.registrySources.forEach {
            registryListModel.addElement("${it.url} @ ${it.ref}")
        }
        defaultTargetCombo.selectedItem = settings.defaultInstallTarget
        autoUpdateCheck.isSelected = settings.autoUpdate
        sidecarPathField.text = settings.sidecarPath
    }

    fun applySettings(settings: AgentrySettings) {
        settings.registrySources = (0 until registryListModel.size()).map { i ->
            val entry = registryListModel.getElementAt(i)
            val parts = entry.split(" @ ", limit = 2)
            AgentrySettings.RegistrySourceState(
                url = parts[0].trim(),
                ref = parts.getOrElse(1) { "HEAD" }.trim(),
                enabled = true,
                displayName = parts[0].trim()
            )
        }.toMutableList()
        settings.defaultInstallTarget = defaultTargetCombo.selectedItem as InstallTarget
        settings.autoUpdate = autoUpdateCheck.isSelected
        settings.sidecarPath = sidecarPathField.text.trim()
    }

    fun isModified(settings: AgentrySettings): Boolean {
        val currentSources = settings.registrySources.map { "${it.url} @ ${it.ref}" }
        val panelSources = (0 until registryListModel.size()).map { i -> registryListModel.getElementAt(i) }
        return panelSources != currentSources
            || defaultTargetCombo.selectedItem != settings.defaultInstallTarget
            || autoUpdateCheck.isSelected != settings.autoUpdate
            || sidecarPathField.text.trim() != settings.sidecarPath
    }
}
