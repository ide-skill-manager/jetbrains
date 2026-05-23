package dev.agentry.jetbrains.install.installers

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.copySafe
import dev.agentry.jetbrains.install.expandPluginVariables
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File
import java.nio.file.Files

/**
 * Installs an MCP server config (`.mcp.json` + bundled binaries). Same variable-expansion
 * policy as the hook installer — see `HookInstaller`'s class KDoc.
 *
 * The final MCP-config destination is a *placeholder* — the JetBrains Copilot MCP
 * on-disk format isn't documented as of the Mar 2026 changelog (open question in the
 * spec). We write a rewritten copy to a deterministic Agentry-owned location so the
 * server scripts and config travel together; future work points JetBrains at it once
 * the on-disk format is known.
 */
internal class McpInstaller : ComponentInstaller<PluginComponent.McpServer> {

    override fun install(
        component: PluginComponent.McpServer,
        plugin: PluginManifest,
        scope: InstallScope
    ): File {
        val dest = InstallPaths.mcpDir(plugin.name, scope)
        val destConfig = File(dest, ".mcp.json")
        Files.createDirectories(dest.toPath())

        val pluginRoot = plugin.pluginRoot.canonicalFile
        component.bundledFiles.forEach { src ->
            val rel = src.canonicalFile.toRelativeString(pluginRoot)
            copySafe(src, File(dest, rel))
        }

        val configText = component.configFile.readText()
        val expanded = expandPluginVariables(
            text = configText,
            pluginRoot = dest,
            pluginDataDir = InstallPaths.pluginDataDir(plugin.name),
            projectDir = if (scope is InstallScope.Project) scope.projectDir else null
        )
        Files.writeString(destConfig.toPath(), expanded)
        return dest
    }
}
