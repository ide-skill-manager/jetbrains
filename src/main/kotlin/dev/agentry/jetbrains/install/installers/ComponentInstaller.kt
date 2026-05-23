package dev.agentry.jetbrains.install.installers

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File

/**
 * Per-component-type installer. One implementation per [PluginComponent] subtype.
 * Returns the on-disk install destination (a file or directory) so the report can render
 * it and so uninstall later can find what to remove.
 */
internal interface ComponentInstaller<C : PluginComponent> {
    fun install(component: C, plugin: PluginManifest, scope: InstallScope): File
}
