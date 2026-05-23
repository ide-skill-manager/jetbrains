package dev.agentry.jetbrains.registry

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.model.ManifestDialect
import dev.agentry.jetbrains.model.PluginAuthor
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File

/**
 * Parses a per-plugin `plugin.json` from disk. Probes for the Microsoft canonical dialect
 * first (`.github/plugin.json`), then the Claude Code dialect (`.claude-plugin/plugin.json`).
 *
 * If neither file is present, returns a synthetic [PluginManifest] with `name` derived from
 * the plugin directory's basename and all component-path lists empty (component discovery
 * still works off the default subdirectories — see Phase 2's `PluginScanner`).
 *
 * Path-list fields (`skills`, `commands`, `agents`, `hooks`, `mcpServers`) accept either a
 * single string or a JSON array in the source; both shapes normalise to `List<String>`.
 * Unknown top-level fields (including `$schema`) are tolerated and discarded.
 */
class PluginManifestParser {

    private val log = logger<PluginManifestParser>()
    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerKotlinModule()

    /**
     * Resolve the plugin's manifest for [pluginRoot].
     *
     * Returns a [Result] so the caller can render parse errors as UI failures while still
     * permitting "no manifest at all" (the [ManifestDialect.DIRNAME_ONLY] case) as a
     * successful result.
     */
    fun parse(pluginRoot: File): Result<PluginManifest> {
        require(pluginRoot.isDirectory) { "Plugin root must be a directory: $pluginRoot" }
        val microsoft = File(pluginRoot, GITHUB_PATH)
        val claude = File(pluginRoot, CLAUDE_PATH)
        val (file, dialect) = when {
            microsoft.isFile -> {
                if (claude.isFile) log.debug("Dual-published plugin manifest at $pluginRoot; using $GITHUB_PATH")
                microsoft to ManifestDialect.GITHUB
            }
            claude.isFile -> claude to ManifestDialect.CLAUDE
            else -> return Result.success(dirnameOnly(pluginRoot))
        }
        return runCatching { parseFile(file, pluginRoot, dialect) }
    }

    internal fun parseFile(file: File, pluginRoot: File, dialect: ManifestDialect): PluginManifest {
        if (file.length() > MAX_JSON_BYTES) {
            throw IllegalArgumentException(
                "${file.relativeTo(pluginRoot)} exceeds $MAX_JSON_BYTES bytes"
            )
        }
        val root = mapper.readTree(file)
        val name = root.path("name").asText("").ifBlank {
            throw IllegalArgumentException("${file.relativeTo(pluginRoot)} missing required `name`")
        }
        return PluginManifest(
            name = name,
            displayName = root.path("displayName").asTextOrNull(),
            version = root.path("version").asTextOrNull(),
            description = root.path("description").asTextOrNull(),
            author = root.path("author").takeIf { it.isObject }?.let { a ->
                PluginAuthor(
                    name = a.path("name").asTextOrNull(),
                    email = a.path("email").asTextOrNull(),
                    url = a.path("url").asTextOrNull()
                )
            },
            homepage = root.path("homepage").asTextOrNull(),
            repository = root.path("repository").asTextOrNull(),
            license = root.path("license").asTextOrNull(),
            keywords = root.path("keywords").asStringList(),
            skills = root.path("skills").asPathList(),
            commands = root.path("commands").asPathList(),
            agents = root.path("agents").asPathList(),
            hooks = root.path("hooks").asPathList(),
            mcpServers = root.path("mcpServers").asPathList(),
            pluginRoot = pluginRoot,
            dialect = dialect
        )
    }

    private fun dirnameOnly(pluginRoot: File): PluginManifest = PluginManifest(
        name = pluginRoot.name,
        displayName = null,
        version = null,
        description = null,
        author = null,
        homepage = null,
        repository = null,
        license = null,
        keywords = emptyList(),
        skills = emptyList(),
        commands = emptyList(),
        agents = emptyList(),
        hooks = emptyList(),
        mcpServers = emptyList(),
        pluginRoot = pluginRoot,
        dialect = ManifestDialect.DIRNAME_ONLY
    )

    private fun JsonNode.asTextOrNull(): String? = if (isTextual) asText().takeIf { it.isNotEmpty() } else null

    private fun JsonNode.asStringList(): List<String> = when {
        isMissingNode || isNull -> emptyList()
        isTextual -> listOf(asText())
        isArray -> mapNotNull { if (it.isTextual) it.asText().takeIf { s -> s.isNotEmpty() } else null }
        else -> emptyList()
    }

    /**
     * Path-list fields are documented to accept either a string or an array. We normalise.
     * `null` / missing returns empty; any non-string element in an array is silently dropped.
     */
    private fun JsonNode.asPathList(): List<String> = asStringList()

    companion object {
        const val GITHUB_PATH = ".github/plugin.json"
        const val CLAUDE_PATH = ".claude-plugin/plugin.json"
        /** Hard cap on a single plugin.json's size. */
        private const val MAX_JSON_BYTES = 512L * 1024
    }
}
