package dev.agentry.jetbrains.install

import dev.agentry.jetbrains.model.ComponentKind
import dev.agentry.jetbrains.model.PluginComponent
import java.io.File

/**
 * Outcome of installing a whole plugin: the components that landed on disk, and the
 * components that didn't with the reason why.
 *
 * Partial success is a real outcome. A plugin with one skill, one hook, and one MCP
 * server might land the skill, log a warning for the hook (unsupported on this IDE),
 * and fail the MCP server (script not found). The UI surfaces all three states in one
 * notification with per-component badges.
 */
data class PluginInstallReport(
    val pluginName: String,
    val installed: List<InstalledComponent>,
    val failed: List<ComponentError>
) {
    val isFullSuccess: Boolean get() = failed.isEmpty() && installed.isNotEmpty()
    val isPartial: Boolean get() = failed.isNotEmpty() && installed.isNotEmpty()
    val isFullFailure: Boolean get() = installed.isEmpty()
}

data class InstalledComponent(
    val kind: ComponentKind,
    val name: String,
    val installPath: File,
    val scope: InstallScope
)

data class ComponentError(
    val kind: ComponentKind,
    val name: String,
    val reason: String,
    val recoverable: Boolean
)

/**
 * Thrown by an individual component installer when the target IDE doesn't support that
 * component type (e.g. agents on JetBrains until the on-disk format is resolved). The
 * [PluginInstaller] catches it and records a non-fatal [ComponentError] in the report.
 */
class UnsupportedComponentException(
    val component: PluginComponent,
    message: String
) : RuntimeException(message)
