package dev.agentry.jetbrains.actions

import com.intellij.util.messages.Topic

/**
 * Cross-action plumbing. Two pieces:
 *
 * 1. [SKILL_NAME_DATA_KEY] — a plain string key the tool window stuffs into a `DataContext`
 *    so the action can use the pre-selected skill name instead of prompting. We use a raw
 *    string rather than the platform's `DataKey<T>` because some platform versions expose
 *    `DataKey.create` differently (Kotlin companion vs. Java static), and the verifier
 *    flags the resulting cross-version reference. Strings work uniformly on every IDE.
 *
 * 2. [AgentryTopics.SKILLS_CHANGED] — a message-bus topic so any view that displays skill
 *    state can re-render after a mutation, regardless of whether the mutation came from a
 *    UI button, an action invocation, or the headless CLI.
 */
const val SKILL_NAME_DATA_KEY: String = "AgentrySkillName"

/**
 * A `List<String>` of skill names the user has selected in the tool window. Read by
 * `InstallSelectedAction` / `UninstallSelectedAction` so batch operations can be driven
 * from the same DataContext pattern as the single-skill actions.
 */
const val SELECTED_SKILLS_DATA_KEY: String = "AgentrySelectedSkills"

/**
 * A `List<AgentryNode.Component>` of plugin components the user has selected. Read by
 * `InstallComponentsAction` / `UninstallComponentsAction` so the plugin-install pipeline
 * has the [PluginComponent] + parent plugin manifest in hand.
 */
const val SELECTED_COMPONENTS_DATA_KEY: String = "AgentrySelectedComponents"

fun interface SkillsChangedListener {
    fun skillsChanged()
}

object AgentryTopics {
    @JvmField
    val SKILLS_CHANGED: Topic<SkillsChangedListener> =
        Topic.create("Agentry.SkillsChanged", SkillsChangedListener::class.java)
}
