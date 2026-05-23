package dev.agentry.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.agentry.jetbrains.model.InstallTarget

/**
 * Persistent application-level settings for Agentry.
 * Stored in agentry.xml in the IDE's config directory.
 */
@State(
    name = "AgentrySettings",
    storages = [Storage("agentry.xml")]
)
class AgentrySettings : PersistentStateComponent<AgentrySettings.State> {

    data class State(
        var registrySources: MutableList<RegistrySourceState> = mutableListOf(),
        var defaultInstallTarget: String = InstallTarget.CLAUDE_PROJECT.name,
        var enabledAgents: MutableList<String> = mutableListOf("CLAUDE", "JUNIE", "COPILOT"),
        var autoUpdate: Boolean = false,
        var sidecarPath: String = ""
    )

    data class RegistrySourceState(
        var url: String = "",
        var ref: String = "HEAD",
        var enabled: Boolean = true,
        var displayName: String = ""
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var registrySources: MutableList<RegistrySourceState>
        get() = state.registrySources
        set(value) { state.registrySources = value }

    var defaultInstallTarget: InstallTarget
        get() = runCatching { InstallTarget.valueOf(state.defaultInstallTarget) }.getOrDefault(InstallTarget.CLAUDE_PROJECT)
        set(value) { state.defaultInstallTarget = value.name }

    var autoUpdate: Boolean
        get() = state.autoUpdate
        set(value) { state.autoUpdate = value }

    var sidecarPath: String
        get() = state.sidecarPath
        set(value) { state.sidecarPath = value }

    companion object {
        fun getInstance(): AgentrySettings =
            ApplicationManager.getApplication().getService(AgentrySettings::class.java)
    }
}
