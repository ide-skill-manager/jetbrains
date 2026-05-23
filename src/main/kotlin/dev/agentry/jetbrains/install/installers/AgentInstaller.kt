package dev.agentry.jetbrains.install.installers

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.UnsupportedComponentException
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File

/**
 * Custom-agent installer.
 *
 * **Currently a stub.** Per the spec's open questions, the JetBrains Copilot
 * Customizations panel exposes a Chat Agents registry, but the persistent on-disk format
 * isn't documented yet — agents may be `.md` files at a fixed path or IDE-state XML.
 *
 * Until that resolves, agent components surface in the UI with a warning badge and
 * install attempts return a non-fatal [UnsupportedComponentException] (recorded in the
 * plugin install report as `recoverable = true`). The rest of the plugin's components
 * still install normally.
 */
internal class AgentInstaller : ComponentInstaller<PluginComponent.Agent> {

    override fun install(
        component: PluginComponent.Agent,
        plugin: PluginManifest,
        scope: InstallScope
    ): File {
        throw UnsupportedComponentException(
            component,
            "Custom-agent install target on JetBrains Copilot is pending resolution. " +
                "See docs/plans/microsoft-agent-plugins-spec.md → Open questions."
        )
    }
}
