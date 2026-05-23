package dev.agentry.jetbrains.install

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.install.installers.AgentInstaller
import dev.agentry.jetbrains.install.installers.CommandInstaller
import dev.agentry.jetbrains.install.installers.ComponentInstaller
import dev.agentry.jetbrains.install.installers.HookInstaller
import dev.agentry.jetbrains.install.installers.McpInstaller
import dev.agentry.jetbrains.install.installers.SkillBundleInstaller
import dev.agentry.jetbrains.model.ComponentKind
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest

/**
 * Entry point for installing a plugin. Routes each [PluginComponent] to the per-type
 * installer based on the sealed subtype, collects results, and produces a
 * [PluginInstallReport] that the UI can render as a single end-of-batch summary.
 *
 * Per-component scope overrides flow through [scopeFor] — by default every component
 * uses [defaultScope], but callers can pass a map to install some components globally
 * and others project-locally (the UI's scope picker drives that map).
 */
@Service(Service.Level.APP)
class PluginInstaller {

    private val log = logger<PluginInstaller>()

    /**
     * @param scopeOverrides map of `(ComponentKind, componentName)` → preferred scope.
     *   Missing entries fall back to [defaultScope].
     */
    fun installPlugin(
        manifest: PluginManifest,
        components: List<PluginComponent>,
        defaultScope: InstallScope,
        scopeOverrides: Map<Pair<ComponentKind, String>, InstallScope> = emptyMap()
    ): PluginInstallReport {
        val installed = mutableListOf<InstalledComponent>()
        val failed = mutableListOf<ComponentError>()

        components.forEach { component ->
            val kind = kindOf(component)
            val scope = scopeOverrides[kind to component.name] ?: defaultScope
            val installer = installerFor(component)
            runCatching { installer.install(component, manifest, scope) }
                .onSuccess { dest -> installed += InstalledComponent(kind, component.name, dest, scope) }
                .onFailure { e ->
                    val recoverable = e is UnsupportedComponentException
                    log.info("Component ${component.name} (${kind.name}) install failed: ${e.message}")
                    failed += ComponentError(
                        kind = kind,
                        name = component.name,
                        reason = e.message ?: e::class.simpleName.orEmpty(),
                        recoverable = recoverable
                    )
                }
        }
        return PluginInstallReport(manifest.name, installed, failed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun installerFor(component: PluginComponent): ComponentInstaller<PluginComponent> = when (component) {
        is PluginComponent.Skill -> SkillBundleInstaller() as ComponentInstaller<PluginComponent>
        is PluginComponent.Command -> CommandInstaller() as ComponentInstaller<PluginComponent>
        is PluginComponent.Agent -> AgentInstaller() as ComponentInstaller<PluginComponent>
        is PluginComponent.Hook -> HookInstaller() as ComponentInstaller<PluginComponent>
        is PluginComponent.McpServer -> McpInstaller() as ComponentInstaller<PluginComponent>
    }

    private fun kindOf(component: PluginComponent): ComponentKind = when (component) {
        is PluginComponent.Skill -> ComponentKind.SKILL
        is PluginComponent.Command -> ComponentKind.COMMAND
        is PluginComponent.Agent -> ComponentKind.AGENT
        is PluginComponent.Hook -> ComponentKind.HOOK
        is PluginComponent.McpServer -> ComponentKind.MCP_SERVER
    }

    companion object {
        fun getInstance(): PluginInstaller =
            ApplicationManager.getApplication().getService(PluginInstaller::class.java)
    }
}
