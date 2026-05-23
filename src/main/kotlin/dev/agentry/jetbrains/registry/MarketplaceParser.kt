package dev.agentry.jetbrains.registry

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.model.MarketplaceManifest
import dev.agentry.jetbrains.model.PluginAuthor
import dev.agentry.jetbrains.model.PluginEntry
import dev.agentry.jetbrains.model.PluginSource
import java.io.File

/**
 * Parses a `marketplace.json` catalog. Supports both manifest dialects:
 *
 *   - `.github/plugin/marketplace.json` (Microsoft canonical; preferred)
 *   - `.claude-plugin/marketplace.json` (Claude Code; fallback)
 *
 * When both exist (dual-publish), the Microsoft path wins. The Claude path is loaded only
 * when the Microsoft one is missing. Top-level fields the parser doesn't recognise are
 * tolerated and discarded — Claude Code does the same.
 *
 * Parse failures surface as a `Result.failure` so the UI can render them inline (red
 * registry node + tooltip) rather than silently dropping the marketplace, matching the
 * "fail loudly where the ecosystem fails silently" rule from the spec.
 */
class MarketplaceParser {

    private val log = logger<MarketplaceParser>()
    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerKotlinModule()

    /**
     * Find and parse the marketplace catalog at [registryRoot]. Returns `null` if neither
     * dialect's file is present — caller treats that as "this registry isn't a marketplace,
     * fall back to direct plugin / skill scan".
     */
    fun parse(registryRoot: File): Result<MarketplaceManifest>? {
        val microsoft = File(registryRoot, GITHUB_PATH)
        val claude = File(registryRoot, CLAUDE_PATH)
        val file = when {
            microsoft.isFile -> {
                if (claude.isFile) log.debug("Dual-published marketplace at $registryRoot; using $GITHUB_PATH")
                microsoft
            }
            claude.isFile -> claude
            else -> return null
        }
        return runCatching { parseFile(file) }
    }

    /** Visible-for-testing parse-from-disk entry point. */
    internal fun parseFile(file: File): MarketplaceManifest {
        if (file.length() > MAX_JSON_BYTES) {
            throw IllegalArgumentException(
                "marketplace.json exceeds $MAX_JSON_BYTES bytes (got ${file.length()})"
            )
        }
        val root = mapper.readTree(file)
        val name = root.path("name").asText("").ifBlank {
            throw IllegalArgumentException("marketplace.json missing required `name`")
        }
        return MarketplaceManifest(
            name = name,
            description = root.path("description").asTextOrNull(),
            owner = root.path("owner").takeIf { it.isObject }?.let { ownerNode ->
                MarketplaceManifest.Owner(
                    name = ownerNode.path("name").asTextOrNull(),
                    email = ownerNode.path("email").asTextOrNull(),
                    url = ownerNode.path("url").asTextOrNull()
                )
            },
            metadata = root.path("metadata").takeIf { it.isObject }?.let { md ->
                MarketplaceManifest.Metadata(
                    pluginRoot = md.path("pluginRoot").asTextOrNull(),
                    version = md.path("version").asTextOrNull()
                )
            },
            plugins = root.path("plugins").takeIf { it.isArray }?.map(::parsePluginEntry).orEmpty()
        )
    }

    private fun parsePluginEntry(node: JsonNode): PluginEntry {
        val pluginName = node.path("name").asText("").ifBlank {
            throw IllegalArgumentException("plugins[*].name is required")
        }
        return PluginEntry(
            name = pluginName,
            source = parseSource(node.path("source"), pluginName),
            displayName = node.path("displayName").asTextOrNull(),
            description = node.path("description").asTextOrNull(),
            version = node.path("version").asTextOrNull(),
            author = node.path("author").takeIf { it.isObject }?.let { a ->
                PluginAuthor(
                    name = a.path("name").asTextOrNull(),
                    email = a.path("email").asTextOrNull(),
                    url = a.path("url").asTextOrNull()
                )
            },
            category = node.path("category").asTextOrNull(),
            tags = node.path("tags").takeIf { it.isArray }?.map { it.asText() }.orEmpty()
        )
    }

    /**
     * `source` accepts two shapes:
     *   - **string** (a relative path inside the registry → [PluginSource.Local])
     *   - **object** `{ "source": "github"|"url", ... }` ([PluginSource.Github] / [PluginSource.Url])
     *
     * Anything else is a parse error. The plugin name is included in the message so the
     * UI can surface which entry is malformed without expanding the whole catalog.
     */
    private fun parseSource(node: JsonNode, pluginName: String): PluginSource {
        if (node.isMissingNode || node.isNull) {
            throw IllegalArgumentException("plugins[$pluginName].source is required")
        }
        if (node.isTextual) return PluginSource.Local(node.asText())
        if (node.isObject) {
            return when (val kind = node.path("source").asText("")) {
                "github" -> {
                    val repo = node.path("repo").asText("").ifBlank {
                        throw IllegalArgumentException("plugins[$pluginName].source.repo is required for github sources")
                    }
                    if (!dev.agentry.jetbrains.util.InputValidation.isValidGithubRepo(repo)) {
                        throw IllegalArgumentException(
                            "plugins[$pluginName].source.repo must match owner/name (alphanumerics, dots, hyphens, underscores)"
                        )
                    }
                    PluginSource.Github(
                        repo = repo,
                        ref = node.path("ref").asTextOrNull(),
                        sha = node.path("sha").asTextOrNull()
                    )
                }
                "url" -> PluginSource.Url(
                    url = node.path("url").asText("").ifBlank {
                        throw IllegalArgumentException("plugins[$pluginName].source.url is required for url sources")
                    },
                    ref = node.path("ref").asTextOrNull()
                )
                else -> throw IllegalArgumentException(
                    "plugins[$pluginName].source.source must be 'github' or 'url'; got '$kind'"
                )
            }
        }
        throw IllegalArgumentException("plugins[$pluginName].source must be a string path or an object")
    }

    private fun JsonNode.asTextOrNull(): String? = if (isTextual) asText().takeIf { it.isNotEmpty() } else null

    companion object {
        const val GITHUB_PATH = ".github/plugin/marketplace.json"
        const val CLAUDE_PATH = ".claude-plugin/marketplace.json"
        /** Hard cap on a marketplace.json's size — a 2 MiB catalog is already huge. */
        private const val MAX_JSON_BYTES = 2L * 1024 * 1024
    }
}
