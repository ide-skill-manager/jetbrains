package dev.agentry.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.agentry.jetbrains.model.InstallTarget

/**
 * Application-level Agentry settings (persisted in `agentry.xml`).
 *
 * Note: `autoSyncOnOpen` defaults to **false** — opening a repo with `.agentry/config.yaml`
 * never silently clones from arbitrary URLs. The user must opt in (per-project decision,
 * recorded in [State.trustedProjects]).
 */
@Service(Service.Level.APP)
@State(name = "AgentrySettings", storages = [Storage("agentry.xml")])
class AgentrySettings : PersistentStateComponent<AgentrySettings.State> {

    data class State(
        var registrySources: MutableList<RegistrySourceState> = mutableListOf(),
        var defaultInstallTarget: String = InstallTarget.CLAUDE_USER.name,
        var autoSyncOnOpen: Boolean = false,
        /** Project base paths the user has approved for auto-sync. */
        var trustedProjects: MutableSet<String> = mutableSetOf()
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
        // Coerce values persisted by older versions (AGENTRY_CACHE, JUNIE_PROJECT) — both
        // removed in 0.1.2. Any unrecognised value falls back to the current default so the
        // settings panel never shows an enum the combo can't display.
        if (enumValues<InstallTarget>().none { it.name == state.defaultInstallTarget }) {
            state.defaultInstallTarget = InstallTarget.CLAUDE_USER.name
        }
    }

    var registrySources: MutableList<RegistrySourceState>
        get() = state.registrySources
        set(value) { state.registrySources = value }

    var defaultInstallTarget: InstallTarget
        get() = enumValues<InstallTarget>().firstOrNull { it.name == state.defaultInstallTarget }
            ?: InstallTarget.CLAUDE_USER
        set(value) { state.defaultInstallTarget = value.name }

    var autoSyncOnOpen: Boolean
        get() = state.autoSyncOnOpen
        set(value) { state.autoSyncOnOpen = value }

    fun isTrusted(projectBasePath: String): Boolean =
        projectBasePath in state.trustedProjects

    fun trustProject(projectBasePath: String) {
        state.trustedProjects.add(projectBasePath)
    }

    companion object {
        fun getInstance(): AgentrySettings =
            ApplicationManager.getApplication().getService(AgentrySettings::class.java)
    }
}
