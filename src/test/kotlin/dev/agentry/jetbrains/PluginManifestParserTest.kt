package dev.agentry.jetbrains

import dev.agentry.jetbrains.model.ManifestDialect
import dev.agentry.jetbrains.registry.PluginManifestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the per-plugin manifest parser.
 */
class PluginManifestParserTest {

    @get:Rule val tmpDir = TemporaryFolder()
    private val parser = PluginManifestParser()

    @Test fun `parses Microsoft dialect`() {
        val plugin = tmpDir.newFolder("my-plugin")
        File(plugin, ".github").mkdirs()
        File(plugin, ".github/plugin.json").writeText(SAMPLE_MANIFEST)
        val m = parser.parse(plugin).getOrThrow()
        assertEquals("document-tools", m.name)
        assertEquals(ManifestDialect.GITHUB, m.dialect)
        assertEquals(listOf("./skills/csv-analysis/"), m.skills)
        assertEquals(listOf("./agents/data-analyst.agent.md"), m.agents)
    }

    @Test fun `parses Claude dialect`() {
        val plugin = tmpDir.newFolder("my-claude-plugin")
        File(plugin, ".claude-plugin").mkdirs()
        File(plugin, ".claude-plugin/plugin.json").writeText(SAMPLE_MANIFEST)
        val m = parser.parse(plugin).getOrThrow()
        assertEquals(ManifestDialect.CLAUDE, m.dialect)
    }

    @Test fun `dual-publish prefers Microsoft`() {
        val plugin = tmpDir.newFolder("dual")
        File(plugin, ".github").mkdirs()
        File(plugin, ".github/plugin.json").writeText(SAMPLE_MANIFEST.replace("document-tools", "ms"))
        File(plugin, ".claude-plugin").mkdirs()
        File(plugin, ".claude-plugin/plugin.json").writeText(SAMPLE_MANIFEST.replace("document-tools", "cc"))
        val m = parser.parse(plugin).getOrThrow()
        assertEquals("ms", m.name)
        assertEquals(ManifestDialect.GITHUB, m.dialect)
    }

    @Test fun `falls back to directory basename when neither manifest exists`() {
        val plugin = tmpDir.newFolder("orphan-plugin-dir")
        val m = parser.parse(plugin).getOrThrow()
        assertEquals("orphan-plugin-dir", m.name)
        assertEquals(ManifestDialect.DIRNAME_ONLY, m.dialect)
        assertTrue(m.skills.isEmpty() && m.commands.isEmpty() && m.agents.isEmpty())
    }

    @Test fun `accepts single string and array path fields`() {
        val pluginA = tmpDir.newFolder("string-form")
        File(pluginA, ".github").mkdirs()
        File(pluginA, ".github/plugin.json").writeText("""{"name": "s", "skills": "./skills/x"}""")
        assertEquals(listOf("./skills/x"), parser.parse(pluginA).getOrThrow().skills)

        val pluginB = tmpDir.newFolder("array-form")
        File(pluginB, ".github").mkdirs()
        File(pluginB, ".github/plugin.json").writeText("""{"name": "a", "skills": ["./a", "./b"]}""")
        assertEquals(listOf("./a", "./b"), parser.parse(pluginB).getOrThrow().skills)
    }

    @Test fun `tolerates unknown top-level fields`() {
        val plugin = tmpDir.newFolder("tolerant")
        File(plugin, ".github").mkdirs()
        File(plugin, ".github/plugin.json").writeText("""
            {
              "${'$'}schema": "https://example.com/plugin.schema.json",
              "name": "t",
              "future_field": ["whatever"]
            }
        """.trimIndent())
        val m = parser.parse(plugin).getOrThrow()
        assertEquals("t", m.name)
    }

    @Test fun `missing name in manifest is a failure`() {
        val plugin = tmpDir.newFolder("nameless")
        File(plugin, ".github").mkdirs()
        File(plugin, ".github/plugin.json").writeText("""{"description": "no name"}""")
        val result = parser.parse(plugin)
        assertTrue(result.isFailure)
    }

    @Test fun `null fields parse as null and stay out of lists`() {
        val plugin = tmpDir.newFolder("nullish")
        File(plugin, ".github").mkdirs()
        File(plugin, ".github/plugin.json").writeText("""
            {"name": "n", "displayName": null, "skills": null, "keywords": null}
        """.trimIndent())
        val m = parser.parse(plugin).getOrThrow()
        assertNull(m.displayName)
        assertTrue(m.skills.isEmpty())
        assertTrue(m.keywords.isEmpty())
    }

    companion object {
        private val SAMPLE_MANIFEST = """
            {
              "name": "document-tools",
              "displayName": "Document Tools",
              "description": "Document processing toolkit",
              "version": "1.0.0",
              "author": {"name": "Chris Ayers", "email": "noreply@chris-ayers.com"},
              "license": "MIT",
              "keywords": ["data", "csv", "analysis"],
              "agents": ["./agents/data-analyst.agent.md"],
              "skills": ["./skills/csv-analysis/"]
            }
        """.trimIndent()
    }
}
