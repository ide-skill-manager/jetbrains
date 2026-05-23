package dev.agentry.jetbrains.registry

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.util.InputValidation
import java.io.File

/**
 * Parses skill manifest JSON files. Accepts the VS Code Marketplace shape but only
 * extracts the fields we actually use.
 */
class ManifestParser {

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerKotlinModule()

    /** Parse a single manifest. Returns null if it can't be read or has no usable name. */
    fun parseFile(
        file: File,
        sourceRegistry: String = "",
        sourceRef: String = "HEAD"
    ): SkillManifest? = runCatching {
        val raw = mapper.readTree(file)
        val name = raw.path("name").asText("")
        if (!InputValidation.isValidSkillName(name)) return@runCatching null
        SkillManifest(
            name = name,
            version = raw.path("version").asText("0.0.1"),
            displayName = raw.path("displayName").asText(name),
            description = raw.path("description").asText(""),
            sourceRegistry = sourceRegistry,
            sourceRef = sourceRef
        )
    }.getOrNull()

    /** Find manifests in [dir] itself and each immediate subdirectory. */
    fun scanDirectory(
        dir: File,
        sourceRegistry: String = "",
        sourceRef: String = "HEAD"
    ): List<SkillManifest> {
        if (!dir.isDirectory) return emptyList()
        val candidates = listOf(dir) + (dir.listFiles()?.filter { it.isDirectory }.orEmpty())
        return candidates.mapNotNull { c ->
            findManifestIn(c)?.let { parseFile(it, sourceRegistry, sourceRef) }
        }
    }

    private fun findManifestIn(dir: File): File? =
        MANIFEST_FILENAMES.asSequence()
            .map { File(dir, it) }
            .firstOrNull { it.isFile }

    companion object {
        private val MANIFEST_FILENAMES = listOf("skill.json", "package.json", "manifest.json")
    }
}
