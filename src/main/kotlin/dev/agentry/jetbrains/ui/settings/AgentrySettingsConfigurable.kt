package dev.agentry.jetbrains.ui.settings

import com.intellij.openapi.options.Configurable
import dev.agentry.jetbrains.settings.AgentrySettings
import javax.swing.JComponent

/**
 * Registers the Agentry settings page under Settings | Tools | Agentry.
 */
class AgentrySettingsConfigurable : Configurable {

    private var panel: AgentrySettingsPanel? = null

    override fun getDisplayName(): String = "Agentry"

    override fun createComponent(): JComponent {
        val p = AgentrySettingsPanel()
        panel = p
        p.loadSettings(AgentrySettings.getInstance())
        return p.root
    }

    override fun isModified(): Boolean = panel?.isModified(AgentrySettings.getInstance()) ?: false

    override fun apply() {
        panel?.applySettings(AgentrySettings.getInstance())
    }

    override fun reset() {
        panel?.loadSettings(AgentrySettings.getInstance())
    }

    override fun disposeUIResources() {
        panel = null
    }
}
