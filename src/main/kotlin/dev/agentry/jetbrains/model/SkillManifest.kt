package dev.agentry.jetbrains.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Skill manifest parsed from a registry's `skill.json` / `package.json` / `manifest.json`.
 * Only fields the plugin actively uses are modelled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SkillManifest(
    val name: String = "",
    val version: String = "0.0.1",
    val displayName: String = "",
    val description: String = "",
    /** Registry this manifest was fetched from (the source URL). Empty when loaded locally. */
    val sourceRegistry: String = ""
)

/** Installation status surfaced in the tool window. */
enum class InstallStatus { NOT_INSTALLED, INSTALLED, ERROR }

/** A manifest plus its current install status, used by the UI. */
data class SkillEntry(
    val manifest: SkillManifest,
    val status: InstallStatus = InstallStatus.NOT_INSTALLED
)

/**
 * A skill that has been written to disk. Decoupled from [SkillManifest] so the manifest
 * stays pure metadata — no leaking of installation state into the domain type.
 */
data class InstalledSkill(
    val manifest: SkillManifest,
    val location: java.io.File,
    val target: InstallTarget
)
