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

        // Security gate: validate manifest + every component name before any installer
        // touches the filesystem. SkillBundleInstaller used to do this on its own; the
        // other installers (Hook / MCP / Command) interpolate names directly into paths
        // via InstallPaths, so an unvalidated name like `"../../etc"` would escape.
        if (!dev.agentry.jetbrains.util.InputValidation.isValidComponentName(manifest.name)) {
            return PluginInstallReport(
                pluginName = manifest.name,
                installed = emptyList(),
                failed = components.map {
                    ComponentError(it.kind, it.name, "invalid plugin name: '${manifest.name}'", recoverable = false)
                }
            )
        }

        components.forEach { component ->
            val scope = scopeOverrides[component.kind to component.name] ?: defaultScope
            if (!dev.agentry.jetbrains.util.InputValidation.isValidComponentName(component.name)) {
                failed += ComponentError(component.kind, component.name, "invalid component name", recoverable = false)
                return@forEach
            }
            runCatching { dispatch(component, manifest, scope) }
                .onSuccess { dest -> installed += InstalledComponent(component.kind, component.name, dest, scope) }
                .onFailure { e ->
                    val recoverable = e is UnsupportedComponentException
                    log.info("Component ${component.name} (${component.kind.name}) install failed: ${e.message}")
                    failed += ComponentError(
                        kind = component.kind,
                        name = component.name,
                        reason = e.message ?: e::class.simpleName.orEmpty(),
                        recoverable = recoverable
                    )
                }
        }
        return PluginInstallReport(manifest.name, installed, failed)
    }

    /**
     * Type-safe dispatch on the sealed [PluginComponent]: the `when` exhaustively maps each
     * subtype to its installer with no unchecked cast. Each branch keeps the precise
     * generic type, so [SkillBundleInstaller] sees `Skill`, [HookInstaller] sees `Hook`, etc.
     */
    private fun dispatch(component: PluginComponent, manifest: PluginManifest, scope: InstallScope): java.io.File =
        when (component) {
            is PluginComponent.Skill -> SkillBundleInstaller().install(component, manifest, scope)
            is PluginComponent.Command -> CommandInstaller().install(component, manifest, scope)
            is PluginComponent.Agent -> AgentInstaller().install(component, manifest, scope)
            is PluginComponent.Hook -> HookInstaller().install(component, manifest, scope)
            is PluginComponent.McpServer -> McpInstaller().install(component, manifest, scope)
        }

    companion object {
        fun getInstance(): PluginInstaller =
            ApplicationManager.getApplication().getService(PluginInstaller::class.java)
    }
}
