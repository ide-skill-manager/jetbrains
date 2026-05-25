package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import dev.agentry.jetbrains.install.InstallScope
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/**
 * Tool-window tree that groups skills by their source registry.
 *
 * Why a `CheckboxTree`: the user can multi-select skills via checkboxes and then run a
 * single Install/Uninstall against the chosen set, instead of having to click each row
 * one at a time.
 *
 * Only leaf rows (`Skill` / `Orphan` / `Component`) are checkable. Two layers enforce
 * that: the renderer hides the checkbox on header rows for visual consistency, and
 * [setNodeState] refuses toggles on non-leaves so click / keyboard / space all no-op on
 * a header. Without the [setNodeState] override the helper would silently flip
 * `isChecked` on a hidden checkbox — confusing for the user, and a tripwire for any
 * future code that walks `isChecked` without filtering by node type.
 */
class SkillTree : CheckboxTree(SkillTreeRenderer(), CheckedTreeNode(null), NO_PROPAGATION_POLICY) {

    init {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0 // let the renderer dictate row heights (multi-line cells)
    }

    override fun setNodeState(node: CheckedTreeNode, checked: Boolean) {
        if (node !is AgentryNode.Skill && node !is AgentryNode.Orphan && node !is AgentryNode.Component) {
            return
        }
        super.setNodeState(node, checked)
    }

    /**
     * Replace the entire tree contents. Preserves checked state by skill name and
     * scroll position. Auto-expands every registry the first time, then leaves
     * subsequent expansion state alone (registry headers stay open by default).
     */
    fun setRoot(root: AgentryNode.Root) {
        val previouslyChecked = collectCheckedSkillNames(model.root as TreeNode)
        val expandedUrls = collectExpandedRegistryUrls()
        val scrollY = (parent as? javax.swing.JViewport)?.viewPosition?.y ?: 0

        model = DefaultTreeModel(root)
        applyCheckedState(root, previouslyChecked)
        expandRegistries(root, expandedUrls)

        // Restore scroll asynchronously so layout has settled.
        javax.swing.SwingUtilities.invokeLater {
            (parent as? javax.swing.JViewport)?.let { it.viewPosition = java.awt.Point(0, scrollY) }
        }
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

    /** Components (skill/command/agent/hook/mcp inside a plugin) the user has ticked. */
    fun selectedComponents(): List<AgentryNode.Component> = allLeaves()
        .filterIsInstance<AgentryNode.Component>()
        .filter { it.isChecked }
        .toList()

    // --- Helpers ---------------------------------------------------------------------

    private fun allLeaves(): Sequence<AgentryNode> = sequence {
        val root = model.root as? TreeNode ?: return@sequence
        val stack = ArrayDeque<TreeNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is AgentryNode.Skill || node is AgentryNode.Orphan || node is AgentryNode.Component) {
                yield(node as AgentryNode)
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
            when (node) {
                is AgentryNode.Skill -> if (node.isChecked) out += node.name
                is AgentryNode.Orphan -> if (node.isChecked) out += node.name
                is AgentryNode.Component -> if (node.isChecked) out += componentKey(node)
                else -> {}
            }
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
                is AgentryNode.Component -> if (componentKey(node) in previouslyChecked) node.isChecked = true
                else -> {}
            }
            for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i))
        }
    }

    /** Component identity for check-state preservation: kind + name disambiguates same-named components. */
    private fun componentKey(node: AgentryNode.Component): String = "${node.kind.name}:${node.name}"

    /**
     * Expand registry rows in O(n) via path-based expansion. If [previouslyExpanded] is
     * empty (first load), expand all registries; otherwise restore prior expansion state
     * keyed by registry URL+ref.
     */
    private fun expandRegistries(root: AgentryNode.Root, previouslyExpanded: Set<String>) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is AgentryNode.Registry) {
                val key = registryKey(child)
                if (previouslyExpanded.isEmpty() || key in previouslyExpanded) {
                    expandPath(javax.swing.tree.TreePath(arrayOf<Any>(root, child)))
                }
            }
            if (child is AgentryNode.OrphanGroup) {
                expandPath(javax.swing.tree.TreePath(arrayOf<Any>(root, child)))
            }
        }
        // Auto-expand every plugin / component group so the user sees the leaves.
        expandAllDescendants(root)
    }

    private fun expandAllDescendants(root: AgentryNode.Root) {
        // Walk the tree and expand every interior node so checkable leaves are visible.
        val stack = ArrayDeque<javax.swing.tree.TreePath>()
        for (i in 0 until root.childCount) {
            stack.addLast(javax.swing.tree.TreePath(arrayOf<Any>(root, root.getChildAt(i))))
        }
        while (stack.isNotEmpty()) {
            val path = stack.removeLast()
            val node = path.lastPathComponent
            if (node is AgentryNode.Plugin || node is AgentryNode.ComponentGroup) {
                expandPath(path)
            }
            if (node is TreeNode) {
                for (j in 0 until node.childCount) {
                    val childPath = path.pathByAddingChild(node.getChildAt(j))
                    stack.addLast(childPath)
                }
            }
        }
    }

    private fun collectExpandedRegistryUrls(): Set<String> {
        val out = mutableSetOf<String>()
        val root = model.root as? TreeNode ?: return out
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is AgentryNode.Registry) {
                val path = javax.swing.tree.TreePath(arrayOf<Any>(root, child))
                if (isExpanded(path)) out += registryKey(child)
            }
        }
        return out
    }

    private fun registryKey(node: AgentryNode.Registry): String =
        "${node.source.url}@${node.source.ref}"
}

/**
 * No-propagation check policy: each leaf is independent. The platform's `DEFAULT_POLICY`
 * (the 2-arg `CheckboxTree(renderer, root)` constructor used to dispatch to) propagates
 * checked state up and down the tree, which doesn't fit our model — only individual
 * skills / components / orphans are checkable (the renderer hides the checkbox on
 * registry / plugin / group headers). The 2-arg constructor was deprecated in 2025.3:
 * `"provide \`checkPolicy\` explicitly, as the default one is defective"`.
 */
private val NO_PROPAGATION_POLICY = CheckboxTreeBase.CheckPolicy(
    false, // checkChildrenWithCheckedParent
    false, // uncheckChildrenWithUncheckedParent
    false, // checkParentWithCheckedChild
    false, // uncheckParentWithUncheckedChild
)

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
        // Suppress the checkbox column on header/grouping rows. Only individual leaves
        // (Skill / Component / Orphan) are checkable; Registry / Plugin / ComponentGroup
        // headers and the Root are non-checkable structural rows.
        val isCheckableLeaf = node is AgentryNode.Skill ||
            node is AgentryNode.Orphan ||
            node is AgentryNode.Component
        // `checkbox` is deprecated on 2025.3+ in favour of `threeStateCheckBox`, but the
        // newer property doesn't exist on 2025.1/2 — our compile floor. Until sinceBuild
        // moves to 253, the verifier warning on 2025.3+ is unavoidable.
        @Suppress("DEPRECATION")
        checkbox.isVisible = isCheckableLeaf

        textRenderer.clear()
        when (node) {
            is AgentryNode.Root -> { /* hidden */ }
            is AgentryNode.Registry -> renderRegistry(node)
            is AgentryNode.Plugin -> renderPlugin(node)
            is AgentryNode.ComponentGroup -> renderComponentGroup(node)
            is AgentryNode.Component -> renderComponent(node)
            is AgentryNode.Skill -> renderSkill(node)
            is AgentryNode.OrphanGroup -> renderOrphanGroup(node)
            is AgentryNode.Orphan -> renderOrphan(node)
        }
    }

    private fun renderPlugin(node: AgentryNode.Plugin) {
        textRenderer.append(node.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        textRenderer.append("   (${node.componentCount} ${pluralize("component", node.componentCount)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderComponentGroup(node: AgentryNode.ComponentGroup) {
        val label = when (node.kind) {
            dev.agentry.jetbrains.model.ComponentKind.SKILL -> "Skills"
            dev.agentry.jetbrains.model.ComponentKind.COMMAND -> "Commands"
            dev.agentry.jetbrains.model.ComponentKind.AGENT -> "Agents"
            dev.agentry.jetbrains.model.ComponentKind.HOOK -> "Hooks"
            dev.agentry.jetbrains.model.ComponentKind.MCP_SERVER -> "MCP servers"
        }
        textRenderer.append("$label  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        textRenderer.append("(${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderComponent(node: AgentryNode.Component) {
        textRenderer.append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        val tag = scopeTag(node.installedScopes)
        if (tag.isNotEmpty()) {
            textRenderer.append("  $tag", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, successFg()))
        }
        val desc = describeComponent(node.component).take(120)
        if (desc.isNotBlank()) {
            textRenderer.append("  $desc", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun describeComponent(c: dev.agentry.jetbrains.model.PluginComponent): String = when (c) {
        is dev.agentry.jetbrains.model.PluginComponent.Skill ->
            c.skillFile.parentFile.name.let { "" }
        is dev.agentry.jetbrains.model.PluginComponent.Command ->
            c.description.orEmpty()
        is dev.agentry.jetbrains.model.PluginComponent.Agent ->
            c.description.orEmpty()
        is dev.agentry.jetbrains.model.PluginComponent.Hook ->
            "${c.scripts.size} script(s)"
        is dev.agentry.jetbrains.model.PluginComponent.McpServer ->
            "${c.bundledFiles.size} bundled file(s)"
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
        val display = StringUtil.notNullize(node.manifest.displayName.ifBlank { node.manifest.name })
        textRenderer.append(display, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        val tag = scopeTag(node.installedScopes)
        if (tag.isNotEmpty()) {
            textRenderer.append("  $tag", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, successFg()))
        }
        textRenderer.append("  v${node.manifest.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
        val tag = scopeTag(node.installedScopes)
        if (tag.isNotEmpty()) {
            textRenderer.append("  $tag", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, successFg()))
        }
        textRenderer.append("  v${node.installed.manifest.version}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        textRenderer.append(
            "orphaned",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE)
        )
    }

    private fun successFg() = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(120, 200, 120))
    private fun pluralize(word: String, n: Int): String = if (n == 1) word else "${word}s"
}

private fun scopeTag(installedScopes: Set<InstallScope>): String = buildString {
    if (installedScopes.any { it is InstallScope.Global }) append("[U]")
    if (installedScopes.any { it is InstallScope.Project }) append("[P]")
}

