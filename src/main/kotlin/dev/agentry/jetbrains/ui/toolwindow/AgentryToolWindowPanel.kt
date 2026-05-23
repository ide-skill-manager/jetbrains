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
import dev.agentry.jetbrains.AgentryDisposable
import dev.agentry.jetbrains.actions.AgentryTopics
import dev.agentry.jetbrains.actions.SELECTED_SKILLS_DATA_KEY
import dev.agentry.jetbrains.actions.SkillsChangedListener
import dev.agentry.jetbrains.model.AgentryNode
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

    init {
        refreshButton.addActionListener { reloadEntries() }
        addButton.addActionListener { fireAction("Agentry.AddRegistry", emptyList()) }
        installButton.addActionListener {
            val picks = skillTree.selectedSkills().filter { !it.installed }.map { it.name }
            if (picks.isEmpty()) {
                statusLabel.text = "No not-installed skills selected."; return@addActionListener
            }
            fireAction("Agentry.InstallSelected", picks)
        }
        uninstallButton.addActionListener {
            val picks = (skillTree.selectedSkills().filter { it.installed }.map { it.name } +
                skillTree.selectedOrphans().map { it.name })
            if (picks.isEmpty()) {
                statusLabel.text = "No installed skills selected."; return@addActionListener
            }
            fireAction("Agentry.UninstallSelected", picks)
        }

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
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
                        lastBuilt = root
                        skillTree.setRoot(root)
                        statusLabel.text = describeStatus(root)
                        updateActionButtonState()
                        applyFilter()
                    }
                }
            }
        )
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
        val q = searchField.text.trim().lowercase()
        if (q.isBlank()) {
            // Nothing to do — full tree is already shown.
            skillTree.setRoot(lastBuilt)
            return
        }
        // Re-build a filtered tree from lastBuilt.
        val filteredRoot = AgentryNode.Root()
        for (i in 0 until lastBuilt.childCount) {
            val child = lastBuilt.getChildAt(i)
            when (child) {
                is AgentryNode.Registry -> {
                    val matching = (0 until child.childCount)
                        .map { child.getChildAt(it) as AgentryNode.Skill }
                        .filter { it.matches(q) }
                    if (matching.isNotEmpty()) {
                        val copy = AgentryNode.Registry(child.source, child.status, matching.size)
                        matching.forEach {
                            val skill = AgentryNode.Skill(it.manifest, it.installed)
                            skill.isChecked = it.isChecked
                            copy.add(skill)
                        }
                        filteredRoot.add(copy)
                    }
                }
                is AgentryNode.OrphanGroup -> {
                    val matching = (0 until child.childCount)
                        .map { child.getChildAt(it) as AgentryNode.Orphan }
                        .filter { it.name.lowercase().contains(q) }
                    if (matching.isNotEmpty()) {
                        val copy = AgentryNode.OrphanGroup(matching.size)
                        matching.forEach { copy.add(AgentryNode.Orphan(it.installed).apply { isChecked = it.isChecked }) }
                        filteredRoot.add(copy)
                    }
                }
                else -> {}
            }
        }
        skillTree.setRoot(filteredRoot)
    }

    private fun AgentryNode.Skill.matches(q: String): Boolean =
        manifest.name.lowercase().contains(q)
            || manifest.displayName.lowercase().contains(q)
            || manifest.description.lowercase().contains(q)

    private fun updateActionButtonState() {
        val checkedSkills = skillTree.selectedSkills()
        val checkedOrphans = skillTree.selectedOrphans()
        val toInstall = checkedSkills.count { !it.installed }
        val toUninstall = checkedSkills.count { it.installed } + checkedOrphans.size
        installButton.text = if (toInstall > 0) "Install selected ($toInstall)" else "Install selected"
        uninstallButton.text = if (toUninstall > 0) "Uninstall selected ($toUninstall)" else "Uninstall selected"
        installButton.isEnabled = toInstall > 0
        uninstallButton.isEnabled = toUninstall > 0
    }

    /**
     * Fire a named action with a data context carrying the project and (optionally) the
     * pre-selected skill names. Mirrors the [com.intellij.openapi.actionSystem.DataContext]
     * pattern used in single-skill actions.
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
        ActionManager.getInstance().tryToExecute(action, event.inputEvent, root, "AgentryToolWindow", true)
    }
}
