package dev.agentry.jetbrains.util

import java.io.File

/**
 * Single source of truth for on-disk locations Agentry uses. Previously these were duplicated
 * across `RegistryManager`, `SkillInstaller`, and `InstallTarget`; consolidating prevents
 * silent drift if one location changes.
 */
object AgentryPaths {

    private val userHome: File get() = File(System.getProperty("user.home"))

    /** Registry working copies live here, one subdirectory per source. */
    val registryCacheRoot: File get() = File(userHome, ".agentry/cache")
}
