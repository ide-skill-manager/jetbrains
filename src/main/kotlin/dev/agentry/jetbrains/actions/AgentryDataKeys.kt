package dev.agentry.jetbrains.actions

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.util.messages.Topic
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.ui.toolwindow.AgentryNode

/**
 * Cross-action plumbing. Two pieces:
 *
 * 1. Typed [DataKey] constants — the tool window stuffs values into a `DataContext` under
 *    these keys; actions read them back instead of prompting. Typed keys (vs. raw strings)
 *    let consumers skip the unchecked-cast dance and let producers/consumers stay in sync
 *    on element type at compile time.
 *
 * 2. [AgentryTopics.SKILLS_CHANGED] — a message-bus topic so any view that displays skill
 *    state can re-render after a mutation, regardless of whether the mutation came from a
 *    UI button, an action invocation, or the headless CLI.
 */
val SKILL_NAME_DATA_KEY: DataKey<String> = DataKey.create("AgentrySkillName")

/**
 * Skill names the user has selected in the tool window. Read by `InstallSelectedAction` /
 * `UninstallSelectedAction` so batch operations can be driven from the same DataContext
 * pattern as the single-skill actions.
 */
val SELECTED_SKILLS_DATA_KEY: DataKey<List<String>> = DataKey.create("AgentrySelectedSkills")

/**
 * Plugin components the user has selected. Read by `InstallComponentsAction` /
 * `UninstallComponentsAction` so the plugin-install pipeline has the [PluginComponent] +
 * parent plugin manifest in hand.
 */
val SELECTED_COMPONENTS_DATA_KEY: DataKey<List<AgentryNode.Component>> =
    DataKey.create("AgentrySelectedComponents")

/**
 * The user's currently-selected install target from the tool-window picker. Actions
 * route their `InstallScope` decision through this when set, falling back to
 * `AgentrySettings.defaultInstallTarget` for CLI / agent-fired paths that never populate
 * the data context.
 */
val INSTALL_TARGET_DATA_KEY: DataKey<InstallTarget> = DataKey.create("AgentryInstallTarget")

fun interface SkillsChangedListener {
    fun skillsChanged()
}

object AgentryTopics {
    @JvmField
    val SKILLS_CHANGED: Topic<SkillsChangedListener> =
        Topic.create("Agentry.SkillsChanged", SkillsChangedListener::class.java)
}
