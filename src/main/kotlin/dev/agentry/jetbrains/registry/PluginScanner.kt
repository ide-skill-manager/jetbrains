package dev.agentry.jetbrains.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File

/**
 * Walks a plugin's on-disk tree and produces the list of [PluginComponent]s the installer
 * (Phase 3) will route to per-type handlers.
 *
 * Discovery rules (per the Microsoft agent-plugins / Claude Code spec):
 *
 *   - **Skills**: every `skills/<name>/SKILL.md` plus any extra paths declared in
 *     `manifest.skills`. A root-level `SKILL.md` makes the whole plugin a single-skill
 *     bundle. Skill name comes from the frontmatter `name`, falling back to the directory
 *     basename.
 *   - **Commands**: every `.md` under `commands/` plus any paths in `manifest.commands`.
 *     Command name is the file stem.
 *   - **Agents**: every `.agent.md` (preferred) or `.md` (legacy) under `agents/`,
 *     plus paths in `manifest.agents`. Agent name comes from frontmatter, falling back to
 *     the file stem with `.agent` stripped.
 *   - **Hooks**: `hooks/hooks.json`. Referenced scripts are captured via the
 *     `${CLAUDE_PLUGIN_ROOT}` token (resolved by [pluginRoot]) so they travel with the hook.
 *   - **MCP servers**: `.mcp.json` (or `plugin.json#mcpServers` — covered in Phase 3 when
 *     we read the inline form). Bundled binaries referenced by `command:` paths come along.
 *
 * Manifest-declared paths *add to* the directory-default discovery; they don't replace it.
 * That matches the spec's "the directory layout is the convention, the manifest is the
 * explicit list". If a user wants opt-out behaviour they can simply put nothing in the
 * default directory.
 *
 * Component-path resolution: a single `String` may point at either a file or a directory.
 *   - For skills: a directory is treated as a skill folder, a file is treated as a
 *     `SKILL.md` and its parent dir is the skill's source.
 *   - For commands/agents: files only; directories are walked one level for matching files.
 *   - For hooks/mcp: files only.
 */
class PluginScanner {

    private val log = logger<PluginScanner>()
    private val mapper = ObjectMapper().registerKotlinModule()

    fun scan(manifest: PluginManifest): List<PluginComponent> {
        val root = manifest.pluginRoot
        val out = mutableListOf<PluginComponent>()

        // Root-level SKILL.md → the whole plugin IS a single skill.
        val rootSkill = File(root, "SKILL.md")
        if (rootSkill.isFile) {
            buildSkill(rootSkill.parentFile, rootSkill, defaultName = manifest.name)?.let { out += it }
        }

        out += discoverSkills(root, manifest.skills)
        out += discoverCommands(root, manifest.commands)
        out += discoverAgents(root, manifest.agents)
        out += discoverHooks(root, manifest.hooks)
        out += discoverMcpServers(root, manifest.mcpServers)

        return dedupByName(out)
    }

    // --- Skills -----------------------------------------------------------------------

    private fun discoverSkills(root: File, extra: List<String>): List<PluginComponent.Skill> {
        val out = mutableListOf<PluginComponent.Skill>()
        val seen = mutableSetOf<String>() // by directory canonical path

        // Default `skills/<name>/` layout.
        File(root, "skills").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val skill = buildSkill(dir, File(dir, "SKILL.md"), defaultName = dir.name) ?: return@forEach
            if (seen.add(dir.canonicalPath)) out += skill
        }

        // Extra explicit paths.
        extra.forEach { path ->
            val resolved = resolveInside(root, path) ?: return@forEach
            val (dir, file) = when {
                resolved.isDirectory -> resolved to File(resolved, "SKILL.md")
                resolved.isFile && resolved.name == "SKILL.md" -> resolved.parentFile to resolved
                else -> return@forEach
            }
            val skill = buildSkill(dir, file, defaultName = dir.name) ?: return@forEach
            if (seen.add(dir.canonicalPath)) out += skill
        }
        return out
    }

    private fun buildSkill(dir: File, skillFile: File, defaultName: String): PluginComponent.Skill? {
        if (!skillFile.isFile) return null
        val name = FrontmatterReader.read(skillFile.readText()).name?.takeIf { it.isNotBlank() } ?: defaultName
        val support = dir.walkTopDown()
            .onEnter { it == dir || !it.name.startsWith(".") }
            .filter { it.isFile && it != skillFile }
            .toList()
        return PluginComponent.Skill(name, dir, skillFile, support)
    }

    // --- Commands ---------------------------------------------------------------------

    private fun discoverCommands(root: File, extra: List<String>): List<PluginComponent.Command> {
        val out = mutableListOf<PluginComponent.Command>()
        val seen = mutableSetOf<String>()
        File(root, "commands").listFiles()?.filter { it.isMdFile() }?.forEach { f ->
            if (seen.add(f.canonicalPath)) out += buildCommand(f)
        }
        extra.forEach { path ->
            val r = resolveInside(root, path) ?: return@forEach
            when {
                r.isFile && r.isMdFile() -> if (seen.add(r.canonicalPath)) out += buildCommand(r)
                r.isDirectory -> r.listFiles()?.filter { it.isMdFile() }?.forEach { f ->
                    if (seen.add(f.canonicalPath)) out += buildCommand(f)
                }
            }
        }
        return out
    }

    private fun buildCommand(f: File): PluginComponent.Command {
        val fm = FrontmatterReader.read(f.readText())
        val name = fm.name ?: f.nameWithoutExtension
        return PluginComponent.Command(name, f, fm.description, fm.argumentHint)
    }

    // --- Agents -----------------------------------------------------------------------

    private fun discoverAgents(root: File, extra: List<String>): List<PluginComponent.Agent> {
        val out = mutableListOf<PluginComponent.Agent>()
        val seen = mutableSetOf<String>()
        File(root, "agents").listFiles()?.filter { it.isAgentFile() }?.forEach { f ->
            if (seen.add(f.canonicalPath)) out += buildAgent(f)
        }
        extra.forEach { path ->
            val r = resolveInside(root, path) ?: return@forEach
            when {
                r.isFile && r.isAgentFile() -> if (seen.add(r.canonicalPath)) out += buildAgent(r)
                r.isDirectory -> r.listFiles()?.filter { it.isAgentFile() }?.forEach { f ->
                    if (seen.add(f.canonicalPath)) out += buildAgent(f)
                }
            }
        }
        return out
    }

    private fun buildAgent(f: File): PluginComponent.Agent {
        val fm = FrontmatterReader.read(f.readText())
        val stem = f.name.removeSuffix(".md").removeSuffix(".agent")
        return PluginComponent.Agent(fm.name ?: stem, f, fm.description)
    }

    // --- Hooks ------------------------------------------------------------------------

    private fun discoverHooks(root: File, extra: List<String>): List<PluginComponent.Hook> {
        val out = mutableListOf<PluginComponent.Hook>()
        val seen = mutableSetOf<String>()
        val defaultHooks = File(root, "hooks/hooks.json")
        if (defaultHooks.isFile) {
            if (seen.add(defaultHooks.canonicalPath)) out += buildHook(root, defaultHooks, "hooks")
        }
        extra.forEach { path ->
            val r = resolveInside(root, path) ?: return@forEach
            val configFile = if (r.isDirectory) File(r, "hooks.json") else r
            if (configFile.isFile && seen.add(configFile.canonicalPath)) {
                val name = configFile.parentFile.name.takeIf { it.isNotBlank() } ?: configFile.nameWithoutExtension
                out += buildHook(root, configFile, name)
            }
        }
        return out
    }

    /**
     * Hook scripts are referenced from `hooks.json` via `${CLAUDE_PLUGIN_ROOT}/scripts/...`.
     * We collect any file paths that resolve under the plugin root so the installer can
     * copy them alongside the config.
     */
    private fun buildHook(root: File, configFile: File, name: String): PluginComponent.Hook {
        val scripts = mutableListOf<File>()
        runCatching { mapper.readTree(configFile) }.getOrNull()?.let { hookRoot ->
            walkJsonStrings(hookRoot).forEach { token ->
                val resolved = resolvePluginRootToken(root, token) ?: return@forEach
                if (resolved.isFile && resolved != configFile) scripts += resolved
            }
        }
        return PluginComponent.Hook(name, configFile, scripts.distinctBy { it.canonicalPath })
    }

    // --- MCP servers ------------------------------------------------------------------

    private fun discoverMcpServers(root: File, extra: List<String>): List<PluginComponent.McpServer> {
        val out = mutableListOf<PluginComponent.McpServer>()
        val seen = mutableSetOf<String>()
        val defaultMcp = File(root, ".mcp.json")
        if (defaultMcp.isFile) {
            if (seen.add(defaultMcp.canonicalPath)) out += buildMcp(root, defaultMcp, "mcp")
        }
        extra.forEach { path ->
            val r = resolveInside(root, path) ?: return@forEach
            val configFile = if (r.isDirectory) File(r, ".mcp.json") else r
            if (configFile.isFile && seen.add(configFile.canonicalPath)) {
                out += buildMcp(root, configFile, configFile.parentFile.name)
            }
        }
        return out
    }

    private fun buildMcp(root: File, configFile: File, name: String): PluginComponent.McpServer {
        val bundled = mutableListOf<File>()
        runCatching { mapper.readTree(configFile) }.getOrNull()?.let { mcp ->
            walkJsonStrings(mcp).forEach { token ->
                val resolved = resolvePluginRootToken(root, token) ?: return@forEach
                if (resolved.isFile && resolved != configFile) bundled += resolved
            }
        }
        return PluginComponent.McpServer(name, configFile, bundled.distinctBy { it.canonicalPath })
    }

    // --- Shared helpers ---------------------------------------------------------------

    /** Concatenated extension predicate: `.md` only. */
    private fun File.isMdFile(): Boolean = isFile && name.endsWith(".md", ignoreCase = true)

    /** Agent files end in `.agent.md` (canonical) or `.md` (legacy). */
    private fun File.isAgentFile(): Boolean = isFile &&
        (name.endsWith(".agent.md", ignoreCase = true) || name.endsWith(".md", ignoreCase = true))

    /**
     * Resolve [path] relative to [root]. Refuses to return anything outside [root] so a
     * manifest path of `../../etc/passwd` can't pull files from outside the plugin tree.
     */
    private fun resolveInside(root: File, path: String): File? {
        val candidate = File(root, path)
        if (!candidate.exists()) return null
        return if (candidate.canonicalPath.startsWith(root.canonicalPath)) candidate else null
    }

    /**
     * Expand a `${CLAUDE_PLUGIN_ROOT}/...` token to an actual file under [root].
     * Used to collect referenced scripts from hooks.json + .mcp.json so they travel with
     * the component when installed.
     */
    private fun resolvePluginRootToken(root: File, token: String): File? {
        val marker = "\${CLAUDE_PLUGIN_ROOT}"
        if (!token.contains(marker)) return null
        val rel = token.substringAfter(marker).trimStart('/', '\\')
        return resolveInside(root, rel)
    }

    /** Visit every string scalar in a JSON tree. */
    private fun walkJsonStrings(node: JsonNode): Sequence<String> = sequence {
        val stack = ArrayDeque<JsonNode>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            when {
                n.isTextual -> yield(n.asText())
                n.isArray -> n.forEach { stack.addLast(it) }
                n.isObject -> n.fields().forEach { stack.addLast(it.value) }
            }
        }
    }

    /** Two components of the same kind with the same name only appear once. */
    private fun dedupByName(components: List<PluginComponent>): List<PluginComponent> {
        val seen = mutableSetOf<Pair<String, String>>()
        return components.filter { c ->
            seen.add(c::class.simpleName.orEmpty() to c.name)
        }
    }
}
