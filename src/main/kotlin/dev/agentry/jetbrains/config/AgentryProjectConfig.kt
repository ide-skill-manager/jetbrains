package dev.agentry.jetbrains.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import java.io.File

/**
 * Parses and writes the project-level .agentry/config.yaml file.
 * This is the "package.json for agent skills" — committed to the repo so
 * teammates get the same agent setup with one click.
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

    fun toRegistrySources(): List<RegistrySource> = sources.map {
        RegistrySource(url = it.url, ref = it.ref, displayName = it.name.ifBlank { it.url })
    }

    companion object {
        private val log = logger<AgentryProjectConfig>()
        private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        const val CONFIG_FILE = ".agentry/config.yaml"

        fun loadFrom(projectBasePath: String): AgentryProjectConfig? {
            val file = File("$projectBasePath/$CONFIG_FILE")
            if (!file.exists()) return null
            return runCatching {
                mapper.readValue(file, AgentryProjectConfig::class.java)
            }.onFailure { e ->
                log.warn("Failed to parse $CONFIG_FILE: ${e.message}")
            }.getOrNull()
        }

        fun saveTo(config: AgentryProjectConfig, projectBasePath: String) {
            val file = File("$projectBasePath/$CONFIG_FILE")
            file.parentFile.mkdirs()
            mapper.writeValue(file, config)
        }

        /** Create a default config file if none exists. */
        fun createDefault(projectBasePath: String): AgentryProjectConfig {
            val config = AgentryProjectConfig()
            saveTo(config, projectBasePath)
            return config
        }
    }
}
