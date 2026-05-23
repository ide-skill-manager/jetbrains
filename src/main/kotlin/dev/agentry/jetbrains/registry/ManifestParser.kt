package dev.agentry.jetbrains.registry

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.agentry.jetbrains.model.SkillManifest
import java.io.File

/**
 * Parses VS Code marketplace-style package.json / skill manifest files.
 */
class ManifestParser {

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerKotlinModule()

    /**
     * Parse a single manifest file (package.json or skill.json).
     * Returns null if the file cannot be parsed or doesn't look like a skill manifest.
     */
    fun parseFile(file: File, sourceRegistry: String = ""): SkillManifest? {
        return runCatching {
            val raw = mapper.readTree(file)
            SkillManifest(
                name = raw.path("name").asText(""),
                version = raw.path("version").asText("0.0.1"),
                displayName = raw.path("displayName").asText(raw.path("name").asText("")),
                description = raw.path("description").asText(""),
                publisher = raw.path("publisher").asText(""),
                categories = raw.path("categories").map { it.asText() },
                tags = raw.path("keywords").map { it.asText() },
                repository = raw.path("repository").path("url").asText().takeIf { it.isNotBlank() }
                    ?: raw.path("repository").asText("").takeIf { it.isNotBlank() },
                license = raw.path("license").asText("").takeIf { it.isNotBlank() },
                files = raw.path("files").map { it.asText() },
                engines = raw.path("engines").fields().asSequence()
                    .associate { (k, v) -> k to v.asText() },
                sourceRegistry = sourceRegistry
            ).takeIf { it.name.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Scan a directory for manifest files. Looks for package.json or skill.json
     * at depth 1 (each immediate subdirectory is a skill).
     */
    fun scanDirectory(dir: File, sourceRegistry: String = ""): List<SkillManifest> {
        if (!dir.isDirectory) return emptyList()
        val results = mutableListOf<SkillManifest>()

        // Check the directory itself first
        val rootManifest = findManifestIn(dir)
        if (rootManifest != null) {
            parseFile(rootManifest, sourceRegistry)?.let { results.add(it) }
        }

        // Check immediate subdirectories
        dir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
            val manifest = findManifestIn(subDir)
            if (manifest != null) {
                parseFile(manifest, sourceRegistry)?.let { results.add(it) }
            }
        }

        return results
    }

    private fun findManifestIn(dir: File): File? {
        return listOf("skill.json", "package.json", "manifest.json")
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.isFile }
    }
}
