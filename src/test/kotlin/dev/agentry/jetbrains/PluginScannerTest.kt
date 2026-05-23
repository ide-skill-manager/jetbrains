package dev.agentry.jetbrains

import dev.agentry.jetbrains.model.ManifestDialect
import dev.agentry.jetbrains.model.PluginAuthor
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.registry.PluginScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises [PluginScanner] against synthetic plugin directories matching the four
 * example-skills fixtures the spec describes. Component counts + names per fixture must
 * match exactly — no extras, no misses.
 */
class PluginScannerTest {

    @get:Rule val tmpDir = TemporaryFolder()
    private val scanner = PluginScanner()

    @Test fun `code-reviewer fixture has one skill and no other components`() {
        val root = tmpDir.newFolder("code-reviewer")
        File(root, "skills/code-review").mkdirs()
        File(root, "skills/code-review/SKILL.md").writeText(
            """
            ---
            name: code-review
            description: Reviews diffs
            ---
            body
            """.trimIndent()
        )
        val components = scanner.scan(manifest(root, "code-reviewer"))
        assertEquals(1, components.size)
        val skill = components.single() as PluginComponent.Skill
        assertEquals("code-review", skill.name)
    }

    @Test fun `pr-summarizer fixture has two commands`() {
        val root = tmpDir.newFolder("pr-summarizer")
        File(root, "commands").mkdirs()
        File(root, "commands/summarize.md").writeText(
            "---\nname: summarize\ndescription: Summarize a PR\nargument-hint: <ref>\n---\nbody"
        )
        File(root, "commands/title.md").writeText("body without frontmatter")
        val components = scanner.scan(manifest(root, "pr-summarizer"))
        val commands = components.filterIsInstance<PluginComponent.Command>()
        assertEquals(2, commands.size)
        val summarize = commands.single { it.name == "summarize" }
        assertEquals("Summarize a PR", summarize.description)
        assertEquals("<ref>", summarize.argumentHint)
        // Filename stem fallback when frontmatter is missing.
        assertTrue(commands.any { it.name == "title" })
    }

    @Test fun `commit-message-writer fixture is a root SKILL plus a hook with scripts`() {
        val root = tmpDir.newFolder("commit-message-writer")
        File(root, "SKILL.md").writeText(
            "---\nname: commit-message-writer\ndescription: Writes commits\n---\nbody"
        )
        File(root, "hooks").mkdirs()
        File(root, "hooks/hooks.json").writeText(
            """
            {
              "hooks": {
                "PreToolUse": [
                  { "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/scripts/check.sh" }
                ],
                "PostToolUse": [
                  { "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/scripts/format.sh" }
                ]
              }
            }
            """.trimIndent()
        )
        File(root, "scripts").mkdirs()
        File(root, "scripts/check.sh").writeText("#!/bin/sh")
        File(root, "scripts/format.sh").writeText("#!/bin/sh")

        val components = scanner.scan(manifest(root, "commit-message-writer"))
        val skills = components.filterIsInstance<PluginComponent.Skill>()
        val hooks = components.filterIsInstance<PluginComponent.Hook>()
        assertEquals(1, skills.size)
        assertEquals("commit-message-writer", skills.single().name)
        assertEquals(1, hooks.size)
        val scripts = hooks.single().scripts.map { it.name }.toSet()
        assertEquals(setOf("check.sh", "format.sh"), scripts)
    }

    @Test fun `repo-toolkit fixture has one agent and one MCP server`() {
        val root = tmpDir.newFolder("repo-toolkit")
        File(root, "agents").mkdirs()
        File(root, "agents/repo-helper.agent.md").writeText(
            "---\nname: repo-helper\ndescription: Repo expert\n---\nbody"
        )
        File(root, ".mcp.json").writeText(
            """
            {
              "mcpServers": {
                "repo-tools": {
                  "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/servers/repo-tools.sh"
                }
              }
            }
            """.trimIndent()
        )
        File(root, "servers").mkdirs()
        File(root, "servers/repo-tools.sh").writeText("#!/bin/sh")

        val components = scanner.scan(manifest(root, "repo-toolkit"))
        val agents = components.filterIsInstance<PluginComponent.Agent>()
        val mcp = components.filterIsInstance<PluginComponent.McpServer>()
        assertEquals(1, agents.size)
        assertEquals("repo-helper", agents.single().name)
        assertEquals(1, mcp.size)
        assertEquals("repo-tools.sh", mcp.single().bundledFiles.single().name)
    }

    @Test fun `agent without agent md extension still picked up via legacy fallback`() {
        val root = tmpDir.newFolder("legacy-agent")
        File(root, "agents").mkdirs()
        File(root, "agents/old-style.md").writeText("---\nname: old-style\n---\nbody")
        val agents = scanner.scan(manifest(root, "legacy-agent")).filterIsInstance<PluginComponent.Agent>()
        assertEquals(1, agents.size)
        assertEquals("old-style", agents.single().name)
    }

    @Test fun `manifest skills entry refusing to escape plugin root is dropped`() {
        val root = tmpDir.newFolder("evil")
        File(root.parentFile, "../etc/passwd").runCatching { writeText("oops") }
        val m = manifest(root, "evil").copy(skills = listOf("../../etc/passwd"))
        val components = scanner.scan(m)
        // Should not produce any skill component for the out-of-tree path.
        assertTrue(components.filterIsInstance<PluginComponent.Skill>().isEmpty())
    }

    @Test fun `extra manifest skill path adds to discovery without duplicating defaults`() {
        val root = tmpDir.newFolder("with-extras")
        File(root, "skills/a").mkdirs()
        File(root, "skills/a/SKILL.md").writeText("---\nname: a\n---\n")
        // Same skill via an explicit list entry; should not appear twice.
        val m = manifest(root, "with-extras").copy(skills = listOf("skills/a"))
        val skills = scanner.scan(m).filterIsInstance<PluginComponent.Skill>()
        assertEquals(1, skills.size)
    }

    private fun manifest(root: File, name: String): PluginManifest = PluginManifest(
        name = name,
        displayName = null,
        version = null,
        description = null,
        author = null as PluginAuthor?,
        homepage = null,
        repository = null,
        license = null,
        keywords = emptyList(),
        skills = emptyList(),
        commands = emptyList(),
        agents = emptyList(),
        hooks = emptyList(),
        mcpServers = emptyList(),
        pluginRoot = root,
        dialect = ManifestDialect.DIRNAME_ONLY
    )
}
