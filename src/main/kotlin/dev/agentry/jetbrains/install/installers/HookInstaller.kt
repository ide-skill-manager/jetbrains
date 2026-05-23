package dev.agentry.jetbrains.install.installers

import dev.agentry.jetbrains.install.ExpansionEnv
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.VariableExpansion
import dev.agentry.jetbrains.install.copySafe
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File
import java.nio.file.Files

/**
 * Installs a hook bundle (`hooks.json` + referenced scripts) under `<project>/.github/hooks/<pluginName>/`.
 *
 * Variable expansion at install time (per the spec's policy table):
 *  - `${CLAUDE_PLUGIN_ROOT}` → the *installed* bundle's absolute path (the new directory
 *    we're writing into). Scripts referenced by that variable will resolve correctly on
 *    the user's machine without needing the original plugin source tree.
 *  - `${CLAUDE_PLUGIN_DATA}` → `~/.agentry/plugin-data/<pluginName>/` (created lazily on
 *    first access by the hook runtime; we just write the absolute path).
 *  - `${CLAUDE_PROJECT_DIR}` → **left as a literal** for JetBrains Copilot's hook runtime
 *    to expand. If that runtime doesn't expand it, the future fix is to substitute at
 *    install time using the project dir — but that ties installs to the project's path.
 */
internal class HookInstaller : ComponentInstaller<PluginComponent.Hook> {

    override fun install(
        component: PluginComponent.Hook,
        plugin: PluginManifest,
        scope: InstallScope
    ): File {
        val dest = InstallPaths.hookDir(plugin.name, scope)
        val destConfig = File(dest, "hooks.json")
        Files.createDirectories(dest.toPath())

        // 1. Copy scripts so the ${CLAUDE_PLUGIN_ROOT}/scripts/foo.sh references resolve.
        val pluginRoot = plugin.pluginRoot.canonicalFile
        component.scripts.forEach { src ->
            val rel = src.canonicalFile.toRelativeString(pluginRoot)
            val target = File(dest, rel)
            copySafe(src, target)
        }

        // 2. Rewrite the config file with expanded variables and write to destination.
        val configText = component.configFile.readText()
        val expanded = VariableExpansion.expand(
            configText,
            ExpansionEnv.forJetBrainsInstall(dest, InstallPaths.pluginDataDir(plugin.name))
        )
        Files.writeString(destConfig.toPath(), expanded)
        return dest
    }
}
