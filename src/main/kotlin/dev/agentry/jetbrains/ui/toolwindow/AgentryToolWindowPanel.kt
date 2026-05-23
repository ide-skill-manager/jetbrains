package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
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
import dev.agentry.jetbrains.AgentryDisposable
import dev.agentry.jetbrains.actions.AgentryDataKeys
import dev.agentry.jetbrains.actions.AgentryTopics
import dev.agentry.jetbrains.actions.SkillsChangedListener
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
 * The Agentry tool window. Button clicks fire the matching `Agentry.*` actions through
 * `ActionManager`, threading the selected skill name through the action data context so
 * there's no duplicate install/remove/refresh logic in this file. When actions complete
 * they publish on [AgentryTopics.SKILLS_CHANGED]; this panel subscribes to that topic and
 * re-renders the list — keeping the UI in sync regardless of whether the trigger was a
 * human click, the menu, or an agent firing the same action via `ActionManager.fireAction`.
 */
class AgentryToolWindowPanel(private val project: Project) {

    private val skillModel = DefaultListModel<SkillEntry>()
    private val skillList = JBList(skillModel).apply {
        cellRenderer = SimpleListCellRenderer.create<SkillEntry>("") { entry ->
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
        refreshButton.addActionListener {
            fireAction("Agentry.Refresh", skillName = null)
        }
        installButton.addActionListener {
            val name = skillList.selectedValue?.manifest?.name ?: run {
                statusLabel.text = "Select a skill to install"; return@addActionListener
            }
            fireAction("Agentry.Install", skillName = name)
        }
        removeButton.addActionListener {
            val name = skillList.selectedValue?.manifest?.name ?: run {
                statusLabel.text = "Select a skill to remove"; return@addActionListener
            }
            fireAction("Agentry.Remove", skillName = name)
        }
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterList()
            override fun removeUpdate(e: DocumentEvent) = filterList()
            override fun changedUpdate(e: DocumentEvent) = filterList()
        })

        // Subscribe to the cross-action "skills changed" topic. Anything that mutates state
        // — whether the user clicked Install, an agent fired Agentry.Install via the CLI,
        // or the file watcher synced .agentry/config.yaml — lands a refresh here.
        project.messageBus.connect(AgentryDisposable.forProject(project))
            .subscribe(AgentryTopics.SKILLS_CHANGED, SkillsChangedListener { reloadEntries() })

        reloadEntries()
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

    /**
     * Fire one of the registered Agentry actions with a data context optionally containing
     * a pre-selected skill name (so the action skips its dialog prompt). This is the
     * delegation point — the tool window never inlines install/remove/refresh logic.
     */
    private fun fireAction(actionId: String, skillName: String?) {
        val action = ActionManager.getInstance().getAction(actionId) ?: run {
            statusLabel.text = "Action '$actionId' not registered"
            return
        }
        val dataContext = DataContext { dataId ->
            when {
                skillName != null && dataId == AgentryDataKeys.SKILL_NAME.name -> skillName
                dataId == com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> project
                else -> null
            }
        }
        val event = AnActionEvent.createFromDataContext("AgentryToolWindow", Presentation(), dataContext)
        ActionManager.getInstance().tryToExecute(action, event.inputEvent, root, "AgentryToolWindow", true)
        // tryToExecute is async; the SKILLS_CHANGED topic will trigger reloadEntries() on
        // completion. Update transient status text for immediate feedback.
        if (skillName != null) statusLabel.text = "Running ${actionId.removePrefix("Agentry.")} on '$skillName'…"
        else statusLabel.text = "Running ${actionId.removePrefix("Agentry.")}…"
    }

    /** Re-fetch the latest manifests and refresh the displayed list. */
    private fun reloadEntries() {
        val settings = AgentrySettings.getInstance()
        val sources = settings.registrySources.map {
            RegistrySource(it.url, it.ref, it.enabled, it.displayName)
        }
        if (sources.none { it.enabled }) {
            ApplicationManager.getApplication().invokeLater {
                allEntries = emptyList()
                filterList()
                statusLabel.text = "No enabled registries. Add one in Settings | Tools | Agentry."
            }
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
}
