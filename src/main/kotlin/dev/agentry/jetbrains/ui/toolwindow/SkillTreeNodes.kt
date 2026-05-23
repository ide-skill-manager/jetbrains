package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.ui.CheckedTreeNode
import dev.agentry.jetbrains.model.ComponentKind
import dev.agentry.jetbrains.model.InstalledSkill
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import java.io.File

/**
 * Tree-node hierarchy backing the Agentry tool window. Designed to be extensible — when
 * commands / subagents become first-class, add a new leaf type and a new header type
 * without disturbing the registry/skill ones.
 *
 * Header nodes (Root, Registry, OrphanGroup) are technically `CheckedTreeNode`s because
 * IntelliJ's `CheckboxTree` requires every node to be one, but we suppress their
 * checkbox via `CheckboxTree.isCheckable` so only leaves can actually be ticked.
 */
sealed class AgentryNode(userObject: Any?) : CheckedTreeNode(userObject) {

    class Root : AgentryNode(null)

    class Registry(
        val source: RegistrySource,
        val status: RegistryStatus,
        val skillCount: Int
    ) : AgentryNode(source) {
        /** Short label shown in the tree row: `"url @ ref"`. */
        val label: String get() = "${source.displayName.ifBlank { source.url }} @ ${source.ref}"
    }

    class Skill(
        val manifest: SkillManifest,
        var installed: Boolean
    ) : AgentryNode(manifest) {
        val name: String get() = manifest.name
    }

    /**
     * A marketplace plugin — one of N inside a [Registry] that ships a `marketplace.json`,
     * or the sole plugin inside a registry whose root carries a `plugin.json`. Children
     * are grouped by [ComponentKind] under [ComponentGroup] headers.
     */
    class Plugin(
        val manifest: PluginManifest,
        val componentCount: Int
    ) : AgentryNode(manifest) {
        /** `displayName (vX.Y.Z)` — fallback to the plugin name when no displayName is set. */
        val label: String get() = buildString {
            append(manifest.displayName ?: manifest.name)
            manifest.version?.let { append(" v$it") }
        }
    }

    /**
     * Header row grouping a plugin's components by [kind]. The count is the number of
     * children rendered inside this group (e.g. "3 skills", "1 hook").
     */
    class ComponentGroup(val kind: ComponentKind, val count: Int) :
        AgentryNode(kind)

    /**
     * Individual component leaf: a skill / command / agent / hook / mcp inside a plugin.
     * Checkable so the user can include/exclude it from a batch install.
     */
    class Component(
        val component: PluginComponent,
        val kind: ComponentKind,
        var installed: Boolean
    ) : AgentryNode(component) {
        val name: String get() = component.name
    }

    class OrphanGroup(val count: Int) : AgentryNode("Installed (no registered registry)")

    class Orphan(
        val installed: InstalledSkill
    ) : AgentryNode(installed) {
        val name: String get() = installed.manifest.name
        val location: File get() = installed.location
    }
}

enum class RegistryStatus {
    /** Source is enabled and successfully fetched. */
    OK,
    /** Source is disabled in settings; we may still have cached skills. */
    DISABLED,
    /** Fetch failed (network, auth, host). */
    UNREACHABLE,
    /** Fetched but yielded no manifests. */
    EMPTY,
}
