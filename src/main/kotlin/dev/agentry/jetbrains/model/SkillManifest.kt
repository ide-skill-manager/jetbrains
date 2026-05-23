package dev.agentry.jetbrains.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Represents a skill/agent manifest parsed from the VS Code marketplace JSON schema.
 * A manifest describes a single skill (tool, prompt, or agent definition).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SkillManifest(
    val name: String = "",
    val version: String = "0.0.1",
    val displayName: String = "",
    val description: String = "",
    val publisher: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val repository: String? = null,
    val license: String? = null,
    val files: List<String> = emptyList(),
    val engines: Map<String, String> = emptyMap(),
    /** Source registry this manifest came from */
    val sourceRegistry: String = "",
    /** Local path on disk after install */
    val installedPath: String? = null
)

/** Installation status of a skill */
enum class InstallStatus {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    INSTALLING,
    ERROR
}

/** A skill with its current installation status */
data class SkillEntry(
    val manifest: SkillManifest,
    val status: InstallStatus = InstallStatus.NOT_INSTALLED,
    val errorMessage: String? = null
)
