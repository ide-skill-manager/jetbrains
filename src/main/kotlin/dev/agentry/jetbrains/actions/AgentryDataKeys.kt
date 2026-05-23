package dev.agentry.jetbrains.actions

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.util.messages.Topic

/**
 * Cross-action plumbing: data keys for passing pre-resolved input to actions (so a UI
 * button can fire an action with the selected item, bypassing the action's normal prompt),
 * and a message-bus topic for signalling that skills have changed (so the tool window can
 * refresh its view after any action — UI-triggered or agent-triggered — mutates state).
 */
object AgentryDataKeys {
    val SKILL_NAME: DataKey<String> = DataKey.create("AgentrySkillName")
}

/**
 * Fires whenever Agentry installs, uninstalls, or refreshes registries. Any view that
 * shows skill state should subscribe and re-render — this is what keeps human-driven and
 * agent-driven mutations in sync without each call site knowing about every listener.
 */
fun interface SkillsChangedListener {
    fun skillsChanged()
}

object AgentryTopics {
    @JvmField
    val SKILLS_CHANGED: Topic<SkillsChangedListener> =
        Topic.create("Agentry.SkillsChanged", SkillsChangedListener::class.java)
}
