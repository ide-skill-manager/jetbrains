package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.UIUtil
import dev.agentry.jetbrains.model.AgentryNode
import dev.agentry.jetbrains.model.RegistryStatus
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/**
 * Tool-window tree that groups skills by their source registry.
 *
 * Why a `CheckboxTree`: the user can multi-select skills via checkboxes and then run a
 * single Install/Uninstall against the chosen set, instead of having to click each row
 * one at a time. IntelliJ's `CheckboxTree` already handles click-region detection and
 * keyboard toggling, so we just override [isCheckable] to suppress checkboxes on the
 * non-leaf rows (registry headers, orphan group header, root).
 */
class SkillTree : CheckboxTree(SkillTreeRenderer(), CheckedTreeNode(null)) {

    init {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0 // let the renderer dictate row heights (multi-line cells)
    }

    /** Replace the entire tree contents. Restores checked state by skill name. */
    fun setRoot(root: AgentryNode.Root) {
        val previouslyChecked = collectCheckedSkillNames(model.root as TreeNode)
        model = DefaultTreeModel(root)
        applyCheckedState(root, previouslyChecked)
        expandRegistryRowsByDefault()
    }

    /** Skills the user has currently ticked. */
    fun selectedSkills(): List<AgentryNode.Skill> = allLeaves()
        .filterIsInstance<AgentryNode.Skill>()
        .filter { it.isChecked }
        .toList()

    /** Orphans the user has currently ticked. */
    fun selectedOrphans(): List<AgentryNode.Orphan> = allLeaves()
        .filterIsInstance<AgentryNode.Orphan>()
        .filter { it.isChecked }
        .toList()

    // --- Helpers ---------------------------------------------------------------------

    private fun allLeaves(): Sequence<AgentryNode> = sequence {
        val root = model.root as? TreeNode ?: return@sequence
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is AgentryNode && (node is AgentryNode.Skill || node is AgentryNode.Orphan)) {
                yield(node)
            }
            for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i))
        }
    }

    private fun collectCheckedSkillNames(root: TreeNode): Set<String> {
        val out = mutableSetOf<String>()
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is AgentryNode.Skill && node.isChecked) out += node.name
            if (node is AgentryNode.Orphan && node.isChecked) out += node.name
            for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i))
        }
        return out
    }

    private fun applyCheckedState(root: AgentryNode.Root, previouslyChecked: Set<String>) {
        if (previouslyChecked.isEmpty()) return
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when (node) {
                is AgentryNode.Skill -> if (node.name in previouslyChecked) node.isChecked = true
                is AgentryNode.Orphan -> if (node.name in previouslyChecked) node.isChecked = true
                else -> {}
            }
            for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i))
        }
    }

    private fun expandRegistryRowsByDefault() {
        // Auto-expand every registry row so the user sees the skills without an extra click.
        var row = 0
        while (row < rowCount) {
            expandRow(row)
            row++
        }
    }
}

/**
 * Renders one row in the [SkillTree]. Three row layouts:
 *
 *   Registry  →  `▶  example-skills @ main   (3 skills)  ✓ enabled`
 *   Skill     →  `[ ] Code Reviewer   v1.0.0    installed`
 *   Orphan    →  `[ ] legacy-formatter   v0.4.2    orphaned`
 *
 * Theme colors flow through `JBColor`/`SimpleTextAttributes` so the cell adapts to
 * light/dark themes.
 */
private class SkillTreeRenderer : CheckboxTree.CheckboxTreeCellRenderer(/*opaque=*/true, /*usePartial=*/false) {

    override fun customizeRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? AgentryNode ?: return
        // Suppress the checkbox column on header/grouping rows. Only Skill and Orphan are
        // checkable leaves; everything else (Root, Registry header, OrphanGroup header)
        // gets its checkbox hidden so the row looks like a header, not a togglable item.
        val isCheckableLeaf = node is AgentryNode.Skill || node is AgentryNode.Orphan
        checkbox.isVisible = isCheckableLeaf

        textRenderer.clear()
        when (node) {
            is AgentryNode.Root -> { /* hidden */ }
            is AgentryNode.Registry -> renderRegistry(node)
            is AgentryNode.Skill -> renderSkill(node)
            is AgentryNode.OrphanGroup -> renderOrphanGroup(node)
            is AgentryNode.Orphan -> renderOrphan(node)
        }
    }

    private fun renderRegistry(node: AgentryNode.Registry) {
        textRenderer.append(node.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        textRenderer.append("   (${node.skillCount} ${pluralize("skill", node.skillCount)})  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        val (badgeText, badgeColor) = when (node.status) {
            RegistryStatus.OK -> "✓ enabled" to successFg()
            RegistryStatus.DISABLED -> "⊘ disabled" to JBColor.GRAY
            RegistryStatus.UNREACHABLE -> "⚠ unreachable" to JBColor.RED
            RegistryStatus.EMPTY -> "∅ empty" to JBColor.GRAY
        }
        textRenderer.append(
            badgeText,
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, badgeColor)
        )
    }

    private fun renderSkill(node: AgentryNode.Skill) {
        val display = StringUtil.notNullize(
            node.manifest.displayName.ifBlank { node.manifest.name }
        )
        textRenderer.append(display, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        textRenderer.append("  v${node.manifest.version}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (node.installed) {
            textRenderer.append(
                "installed",
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, successFg())
            )
        }
        val desc = node.manifest.description.take(120)
        if (desc.isNotBlank()) {
            textRenderer.append("   ${desc}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun renderOrphanGroup(node: AgentryNode.OrphanGroup) {
        textRenderer.append(
            "Installed (no registered registry)",
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        )
        textRenderer.append("   (${node.count} ${pluralize("skill", node.count)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderOrphan(node: AgentryNode.Orphan) {
        textRenderer.append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        textRenderer.append("  v${node.installed.manifest.version}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        textRenderer.append(
            "orphaned",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE)
        )
    }

    private fun successFg() = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(120, 200, 120))
    private fun pluralize(word: String, n: Int): String = if (n == 1) word else "${word}s"
}

@Suppress("unused")
private val _muteUnusedImport = UIUtil::class // keep import for future styling additions
@Suppress("unused")
private val _muteCheckboxTreeBase = CheckboxTreeBase::class // imported by inheritance documentation
