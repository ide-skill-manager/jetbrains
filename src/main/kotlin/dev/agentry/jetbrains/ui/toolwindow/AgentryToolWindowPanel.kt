package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import dev.agentry.jetbrains.AgentryDisposable
import dev.agentry.jetbrains.actions.AgentryTopics
import dev.agentry.jetbrains.actions.SELECTED_SKILLS_DATA_KEY
import dev.agentry.jetbrains.actions.SkillsChangedListener
import dev.agentry.jetbrains.ui.toolwindow.AgentryNode
import dev.agentry.jetbrains.settings.AgentrySettings
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.TreeNode

/**
 * Agentry tool window.
 *
 * The list is now a [SkillTree] grouped by registry. Buttons act on the *checked* set
 * of skills (multi-select), firing the matching `Agentry.*` actions via `ActionManager`
 * so human clicks and agent-fired actions take the same code path. State changes from
 * any source land back here through the [AgentryTopics.SKILLS_CHANGED] message-bus topic.
 */
class AgentryToolWindowPanel(private val project: Project) {

    private val skillTree = SkillTree()
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Ready")
    private val refreshButton = JButton("Refresh")
    private val addButton = JButton("+ Add Registry…")
    private val installButton = JButton("Install selected")
    private val uninstallButton = JButton("Uninstall selected")

    val root: JPanel = buildPanel()

    private var lastBuilt: AgentryNode.Root = AgentryNode.Root()
    private val filterAlarm: Alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, AgentryDisposable.forProject(project))
    private val filterDebounceMs = 200

    init {
        refreshButton.addActionListener { reloadEntries() }
        addButton.addActionListener { fireAction("Agentry.AddRegistry", emptyList()) }
        installButton.addActionListener {
            // Two pipelines: legacy flat-skill installs (SkillManifest-based) and the
            // newer plugin-component installs (PluginComponent-based via PluginInstaller).
            // Fire whichever applies to the current selection; if both are present, run
            // both.
            val legacySkills = skillTree.selectedSkills().filter { !it.installed }.map { it.name }
            val components = skillTree.selectedComponents().filter { !it.installed }
            if (legacySkills.isEmpty() && components.isEmpty()) {
                statusLabel.text = "Nothing to install — select a skill or component."; return@addActionListener
            }
            if (legacySkills.isNotEmpty()) fireAction("Agentry.InstallSelected", legacySkills)
            if (components.isNotEmpty()) fireComponentAction("Agentry.InstallComponents", components)
        }
        uninstallButton.addActionListener {
            val legacy = skillTree.selectedSkills().filter { it.installed }.map { it.name } +
                skillTree.selectedOrphans().map { it.name }
            val components = skillTree.selectedComponents().filter { it.installed }
            if (legacy.isEmpty() && components.isEmpty()) {
                statusLabel.text = "Nothing to remove — select an installed skill or component."; return@addActionListener
            }
            if (legacy.isNotEmpty()) fireAction("Agentry.UninstallSelected", legacy)
            if (components.isNotEmpty()) fireComponentAction("Agentry.UninstallComponents", components)
        }

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleFilter()
            override fun removeUpdate(e: DocumentEvent) = scheduleFilter()
            override fun changedUpdate(e: DocumentEvent) = scheduleFilter()
        })

        // Re-render whenever something mutated state.
        project.messageBus.connect(AgentryDisposable.forProject(project))
            .subscribe(AgentryTopics.SKILLS_CHANGED, SkillsChangedListener { reloadEntries() })

        // Enable/disable action buttons as checkbox state changes.
        skillTree.addCheckboxTreeListener(object : com.intellij.ui.CheckboxTreeListener {
            override fun nodeStateChanged(node: com.intellij.ui.CheckedTreeNode) {
                updateActionButtonState()
            }
        })
        updateActionButtonState()
        reloadEntries()
    }

    private fun buildPanel(): JPanel {
        val panel = JPanel(BorderLayout(4, 4))
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(searchField)
            add(Box.createHorizontalGlue())
            add(addButton)
            add(Box.createHorizontalStrut(4))
            add(refreshButton)
        }
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(skillTree), BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout()).apply {
            val actions = JPanel().apply { add(installButton); add(uninstallButton) }
            add(actions, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }
        panel.add(bottom, BorderLayout.SOUTH)
        return panel
    }

    /** Re-fetch + re-render. Runs in a background task; the UI is updated via invokeLater. */
    fun reloadEntries() {
        val settings = AgentrySettings.getInstance()
        val basePath = project.basePath
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Agentry: loading skills", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val root = SkillTreeBuilder.build(settings.defaultInstallTarget, basePath)
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        lastBuilt = root
                        // Apply the current filter (if any) and install in one shot — avoids
                        // a flash of the unfiltered tree between setRoot and the filter pass.
                        skillTree.setRoot(filteredRoot(searchField.text.trim().lowercase()))
                        statusLabel.text = describeStatus(root)
                        updateActionButtonState()
                    }
                }
            }
        )
    }

    private fun scheduleFilter() {
        filterAlarm.cancelAllRequests()
        filterAlarm.addRequest({ applyFilter() }, filterDebounceMs)
    }

    private fun describeStatus(root: AgentryNode.Root): String {
        var skills = 0
        var registries = 0
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as TreeNode
            if (child is AgentryNode.Registry) {
                registries++
                skills += child.skillCount
            }
        }
        return "$registries registr${if (registries == 1) "y" else "ies"}, $skills total skill(s)"
    }

    private fun applyFilter() {
        if (project.isDisposed) return
        skillTree.setRoot(filteredRoot(searchField.text.trim().lowercase()))
    }

    /**
     * Build the tree shown to the user — either the full [lastBuilt] (when [query] is
     * blank) or a copy that includes only matching skills. Called by both [applyFilter]
     * and [reloadEntries] to keep one source of truth for what `setRoot` receives.
     */
    private fun filteredRoot(query: String): AgentryNode.Root {
        if (query.isBlank()) return lastBuilt
        val out = AgentryNode.Root()
        for (i in 0 until lastBuilt.childCount) {
            when (val child = lastBuilt.getChildAt(i)) {
                is AgentryNode.Registry -> {
                    val matching = (0 until child.childCount)
                        .map { child.getChildAt(it) as AgentryNode.Skill }
                        .filter { it.matches(query) }
                    if (matching.isNotEmpty()) {
                        val copy = AgentryNode.Registry(child.source, child.status, matching.size)
                        matching.forEach { src ->
                            val skill = AgentryNode.Skill(src.manifest, src.installed)
                            skill.isChecked = src.isChecked
                            copy.add(skill)
                        }
                        out.add(copy)
                    }
                }
                is AgentryNode.OrphanGroup -> {
                    val matching = (0 until child.childCount)
                        .map { child.getChildAt(it) as AgentryNode.Orphan }
                        .filter { it.name.lowercase().contains(query) }
                    if (matching.isNotEmpty()) {
                        val copy = AgentryNode.OrphanGroup(matching.size)
                        matching.forEach { src ->
                            copy.add(AgentryNode.Orphan(src.installed).apply { isChecked = src.isChecked })
                        }
                        out.add(copy)
                    }
                }
                else -> {}
            }
        }
        return out
    }

    private fun AgentryNode.Skill.matches(q: String): Boolean =
        manifest.name.lowercase().contains(q)
            || manifest.displayName.lowercase().contains(q)
            || manifest.description.lowercase().contains(q)

    private fun updateActionButtonState() {
        val checkedSkills = skillTree.selectedSkills()
        val checkedOrphans = skillTree.selectedOrphans()
        val checkedComponents = skillTree.selectedComponents()
        val toInstall = checkedSkills.count { !it.installed } + checkedComponents.count { !it.installed }
        val toUninstall = checkedSkills.count { it.installed } + checkedOrphans.size +
            checkedComponents.count { it.installed }
        installButton.text = if (toInstall > 0) "Install selected ($toInstall)" else "Install selected"
        uninstallButton.text = if (toUninstall > 0) "Uninstall selected ($toUninstall)" else "Uninstall selected"
        installButton.isEnabled = toInstall > 0
        uninstallButton.isEnabled = toUninstall > 0
    }

    /**
     * Fire a named action with a data context carrying the project and (optionally) the
     * pre-selected skill names.
     *
     * We invoke `action.actionPerformed(event)` directly rather than going through
     * `ActionManager.tryToExecute`. The latter requires a non-null `InputEvent` and
     * silently no-ops with our synthetic event when the action's update thread is BGT,
     * which would make the Install / Uninstall selected buttons appear to do nothing.
     */
    private fun fireAction(actionId: String, skillNames: List<String>) {
        val action = ActionManager.getInstance().getAction(actionId) ?: run {
            statusLabel.text = "Action '$actionId' not registered"; return
        }
        val dataContext = DataContext { dataId ->
            when (dataId) {
                SELECTED_SKILLS_DATA_KEY -> skillNames
                CommonDataKeys.PROJECT.name -> project
                else -> null
            }
        }
        val event = AnActionEvent.createFromDataContext("AgentryToolWindow", Presentation(), dataContext)
        action.actionPerformed(event)
    }

    /**
     * Fire a plugin-component action. Data key carries the actual `AgentryNode.Component`
     * list so the action has the full [PluginComponent] + plugin manifest context it
     * needs to dispatch through `PluginInstaller`.
     */
    private fun fireComponentAction(actionId: String, components: List<AgentryNode.Component>) {
        val action = ActionManager.getInstance().getAction(actionId) ?: run {
            statusLabel.text = "Action '$actionId' not registered"; return
        }
        val dataContext = DataContext { dataId ->
            when (dataId) {
                dev.agentry.jetbrains.actions.SELECTED_COMPONENTS_DATA_KEY -> components
                CommonDataKeys.PROJECT.name -> project
                else -> null
            }
        }
        val event = AnActionEvent.createFromDataContext("AgentryToolWindow", Presentation(), dataContext)
        action.actionPerformed(event)
    }
}
