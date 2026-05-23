package dev.agentry.jetbrains.install

import java.io.File

/**
 * Where a single component is installed on disk.
 *
 *   - [Project]: under the active project directory ([projectDir]).
 *   - [Global] : under the user's home (`~/.copilot/...`, `~/.agentry/...`).
 *
 * Components within a single plugin can pick different scopes — e.g. a skill installed
 * globally and a hook installed project-locally are routine. The [PluginInstaller] takes
 * a default scope and may receive per-component overrides.
 */
sealed class InstallScope {
    data class Project(val projectDir: File) : InstallScope()
    object Global : InstallScope()
}
