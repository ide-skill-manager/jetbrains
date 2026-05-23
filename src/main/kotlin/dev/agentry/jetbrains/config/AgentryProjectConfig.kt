package dev.agentry.jetbrains.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.util.InputValidation
import java.io.File

/**
 * Project-level `.agentry/config.yaml`: the "package.json for agent skills" that lets a
 * team share registry sources and skill dependencies via the repo.
 */
data class AgentryProjectConfig(
    val version: String = "1",
    val sources: List<RegistrySourceConfig> = emptyList(),
    val skills: List<SkillDependency> = emptyList(),
    val defaultTarget: String = InstallTarget.CLAUDE_PROJECT.name
) {

    data class RegistrySourceConfig(
        val url: String = "",
        val ref: String = "HEAD",
        val name: String = ""
    )

    data class SkillDependency(
        val name: String = "",
        val version: String = "*",
        val registry: String = "",
        val target: String = InstallTarget.CLAUDE_PROJECT.name
    )

    /** Convert YAML sources to runtime [RegistrySource]s, dropping any that fail validation. */
    fun toRegistrySources(): List<RegistrySource> = sources.mapNotNull { src ->
        if (!InputValidation.isValidRegistryUrl(src.url)) {
            log.warn("Skipping registry with invalid URL in .agentry/config.yaml: '${src.url}'")
            return@mapNotNull null
        }
        if (src.ref != "HEAD" && !InputValidation.isValidGitRef(src.ref)) {
            log.warn("Skipping registry '${src.url}' with invalid ref: '${src.ref}'")
            return@mapNotNull null
        }
        RegistrySource(url = src.url, ref = src.ref, displayName = src.name.ifBlank { src.url })
    }

    companion object {
        private val log = logger<AgentryProjectConfig>()
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        const val CONFIG_PATH = ".agentry/config.yaml"

        fun configFile(projectBasePath: String): File =
            File(projectBasePath, CONFIG_PATH)

        fun loadFrom(projectBasePath: String): AgentryProjectConfig? {
            val file = configFile(projectBasePath)
            if (!file.exists()) return null
            return runCatching { mapper.readValue(file, AgentryProjectConfig::class.java) }
                .onFailure { log.warn("Failed to parse $CONFIG_PATH: ${it.message}") }
                .getOrNull()
        }

        fun saveTo(config: AgentryProjectConfig, projectBasePath: String) {
            val file = configFile(projectBasePath)
            file.parentFile.mkdirs()
            mapper.writeValue(file, config)
        }
    }
}
