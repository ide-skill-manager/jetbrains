package dev.agentry.jetbrains.install.installers

import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.splitFrontmatter
import dev.agentry.jetbrains.install.writeTextEnsuringParent
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.registry.FrontmatterReader
import dev.agentry.jetbrains.util.InputValidation
import java.io.File
import java.nio.file.Files

/**
 * Installs a [PluginComponent.Agent] as a Copilot **custom chat agent**, dual-writing to
 * the paths each tool in the ecosystem reads:
 *
 *   Project:
 *     - `<project>/.github/agents/<name>.agent.md`  — Copilot for JetBrains, VS Code Copilot,
 *       Copilot CLI, Copilot cloud agent. Source:
 *       [VS Code custom-agents docs](https://code.visualstudio.com/docs/copilot/customization/custom-agents).
 *     - `<project>/.claude/agents/<name>.agent.md`  — Claude Code; also a documented
 *       pickup path for Copilot for JetBrains per the
 *       [Copilot for JetBrains wiki](https://github.com/microsoft/copilot-intellij-feedback/wiki/Agent-Configuration-and-Extensibility).
 *
 *   Global:
 *     - `~/.copilot/agents/<plugin>__<name>.agent.md`
 *     - `~/.claude/agents/<plugin>__<name>.agent.md`
 *     Both filenames are namespaced by plugin id — the shared cross-IDE directories
 *     would otherwise collide if two tools ship a same-named agent.
 *
 * Frontmatter rules the loader enforces:
 *   - `description` is **required**. We backfill if missing (first paragraph of the body,
 *     falling back to "Custom agent: <name>") so the install never produces a file the
 *     loader silently drops.
 *   - `name` is the display label; backfilled from the component name when absent.
 *
 * Always writes `.agent.md` even when the source was `.md` (legacy) or `.chatmode.md`
 * (renamed-from form) — the canonical filename is what new Copilot versions key on.
 *
 * Security boundary: the component name is validated through [InputValidation.isValidComponentName]
 * before any path resolution; the source file is refused if it's a symlink (could otherwise
 * point at `~/.ssh/id_rsa` and have those contents wrapped in agent frontmatter and written
 * into the cross-IDE agents dir); every resolved destination is canonical-path-checked to
 * stay inside its install root.
 */
internal class AgentInstaller : ComponentInstaller<PluginComponent.Agent> {

    private val log = logger<AgentInstaller>()

    override fun install(
        component: PluginComponent.Agent,
        plugin: PluginManifest,
        scope: InstallScope
    ): File {
        require(InputValidation.isValidComponentName(component.name)) {
            "Invalid agent name: '${component.name}'"
        }
        require(InputValidation.isValidComponentName(plugin.name)) {
            "Invalid plugin name: '${plugin.name}'"
        }
        if (Files.isSymbolicLink(component.sourceFile.toPath())) {
            throw SecurityException("Refusing to read symlinked agent source: ${component.sourceFile}")
        }
        val destinations = InstallPaths.destinationsFor(component, plugin, scope)
        // Path-escape check per destination — defence-in-depth against a future name
        // regex relaxation. Every dest must stay under its install root.
        destinations.forEach { dest ->
            val installRoot = dest.parentFile
                ?: error("Agent dest has no parent: $dest")
            require(InputValidation.isInsideDir(dest, installRoot)) {
                "Resolved agent dest escapes install root: $dest"
            }
        }
        val rewritten = backfillFrontmatter(component.sourceFile.readText(), component)
        destinations.forEach { dest ->
            if (dest.exists()) log.info("Overwriting existing agent file at ${dest.absolutePath}")
            dest.writeTextEnsuringParent(rewritten)
        }
        log.info("Installed agent '${component.name}' to ${destinations.size} location(s)")
        // Return the primary (first) destination — the report cites one canonical path.
        return destinations.first()
    }

    /**
     * Rewrite the source's frontmatter so it satisfies the Copilot loader. Always returns
     * a *normalised* file (LF line endings, fresh `name` + `description` keys at the top).
     *
     * Other frontmatter fields — `model`, `tools`, `target`, `argument-hint`, `mcp-servers`,
     * `handoffs`, `hooks`, custom metadata — pass through. The loader ignores unknowns so
     * forwarding them is safe.
     */
    private fun backfillFrontmatter(source: String, component: PluginComponent.Agent): String {
        val existing = FrontmatterReader.read(source)
        val (fmBlock, body) = splitFrontmatter(source)
        val name = existing.name?.takeIf { it.isNotBlank() } ?: component.name
        val description = existing.description?.takeIf { it.isNotBlank() }
            ?: component.description?.takeIf { it.isNotBlank() }
            ?: deriveDescriptionFromBody(body)
            ?: "Custom agent: ${component.name}"
        // Strip name/description from the existing block (we re-emit them). Only matches
        // top-level keys (no leading whitespace) so nested mappings like
        // `metadata:\n  name: x` survive unchanged.
        val kept = fmBlock
            ?.lineSequence()
            ?.filterNot { line ->
                val firstChar = line.firstOrNull()
                val isTopLevel = firstChar != null && firstChar != ' ' && firstChar != '\t'
                isTopLevel && line.substringBefore(':').trim() in setOf("name", "description")
            }
            ?.joinToString("\n")
            ?.trim()
            .orEmpty()
        val keptBlock = if (kept.isNotEmpty()) "$kept\n" else ""
        return "---\nname: ${yamlScalar(name)}\ndescription: ${yamlScalar(description)}\n${keptBlock}---\n$body"
    }

    /**
     * First non-empty paragraph of the body, collapsing internal whitespace and trimming
     * to 120 chars. Returns null when the body has no non-blank content. A "paragraph"
     * is a run of consecutive non-blank lines separated from the next run by one or more
     * blank lines.
     *
     * Capped on input via `take(MAX_BODY_BYTES)` so a 100 MB single-line body can't blow
     * up memory on a malicious skill.
     */
    private fun deriveDescriptionFromBody(body: String): String? {
        val capped = body.take(MAX_BODY_BYTES)
        val para = capped.split(Regex("\\n\\s*\\n"))
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return null
        return if (para.length > 120) para.take(117) + "…" else para
    }

    /**
     * Quote a scalar so it survives YAML 1.2 parsing without ambiguity. Always single-quote
     * (per the awesome-copilot style guide) so every problematic case is handled — YAML
     * reserved literals (`null`, `true`, `1.0`, `yes`), values starting with special
     * indicators (`-`, `[`, `*`, etc.), and any whitespace edges. Multiline values fall
     * back to double-quoted with `\n` escaping (single-quoted YAML scalars can't contain
     * control characters).
     */
    private fun yamlScalar(value: String): String {
        if (value.contains('\n') || value.contains('\r')) {
            return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }
        return "'${value.replace("'", "''")}'"
    }

    companion object {
        /** Cap body read for description derivation to keep memory bounded. */
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}
