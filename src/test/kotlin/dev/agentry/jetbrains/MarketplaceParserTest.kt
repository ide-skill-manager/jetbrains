package dev.agentry.jetbrains

import dev.agentry.jetbrains.model.PluginSource
import dev.agentry.jetbrains.registry.MarketplaceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the marketplace.json parser. Coverage matrix the spec calls for:
 *   - each dialect parses standalone (Microsoft vs Claude path)
 *   - dual-publish prefers the Microsoft path
 *   - object-form `source` (github / url) round-trips through
 *   - unknown top-level fields tolerated
 *   - missing `name` is a parse failure (surfaces in UI as red registry)
 */
class MarketplaceParserTest {

    @get:Rule val tmpDir = TemporaryFolder()
    private val parser = MarketplaceParser()

    @Test fun `parses Microsoft canonical path`() {
        val registry = tmpDir.newFolder("registry")
        File(registry, ".github/plugin").mkdirs()
        File(registry, ".github/plugin/marketplace.json").writeText(SAMPLE_CATALOG)

        val result = parser.parse(registry)
        assertNotNull(result)
        val manifest = result!!.getOrThrow()
        assertEquals("my-team-plugins", manifest.name)
        assertEquals(2, manifest.plugins.size)
    }

    @Test fun `parses Claude dialect when Microsoft path is absent`() {
        val registry = tmpDir.newFolder("registry-claude")
        File(registry, ".claude-plugin").mkdirs()
        File(registry, ".claude-plugin/marketplace.json").writeText(SAMPLE_CATALOG)

        val result = parser.parse(registry)
        assertNotNull(result)
        assertEquals("my-team-plugins", result!!.getOrThrow().name)
    }

    @Test fun `dual-publish prefers Microsoft path`() {
        val registry = tmpDir.newFolder("registry-dual")
        File(registry, ".github/plugin").mkdirs()
        File(registry, ".github/plugin/marketplace.json").writeText(SAMPLE_CATALOG.replace("my-team-plugins", "microsoft-wins"))
        File(registry, ".claude-plugin").mkdirs()
        File(registry, ".claude-plugin/marketplace.json").writeText(SAMPLE_CATALOG.replace("my-team-plugins", "claude-wins"))

        val result = parser.parse(registry)
        assertEquals("microsoft-wins", result!!.getOrThrow().name)
    }

    @Test fun `returns null when neither dialect file exists`() {
        val registry = tmpDir.newFolder("empty")
        assertNull(parser.parse(registry))
    }

    @Test fun `parses local source string`() {
        val manifest = parseString(SAMPLE_CATALOG)
        val devToolkit = manifest.plugins.first { it.name == "dev-toolkit" }
        assertTrue("expected local source", devToolkit.source is PluginSource.Local)
        assertEquals("./plugins/dev-toolkit", (devToolkit.source as PluginSource.Local).relativePath)
    }

    @Test fun `parses github source object`() {
        val manifest = parseString(SAMPLE_CATALOG)
        val external = manifest.plugins.first { it.name == "external-via-github" }
        val src = external.source as PluginSource.Github
        assertEquals("acme/external-plugin", src.repo)
        assertEquals("v2.0", src.ref)
    }

    @Test fun `tolerates unknown top-level fields`() {
        val manifest = parseString(
            """
            {
              "${'$'}schema": "https://example.com/marketplace.schema.json",
              "name": "tolerant",
              "future_field": {"anything": "goes"},
              "plugins": []
            }
            """.trimIndent()
        )
        assertEquals("tolerant", manifest.name)
        assertTrue(manifest.plugins.isEmpty())
    }

    @Test fun `missing name is a parse failure`() {
        val registry = tmpDir.newFolder("nameless")
        File(registry, ".github/plugin").mkdirs()
        File(registry, ".github/plugin/marketplace.json").writeText("""{"plugins": []}""")
        val result = parser.parse(registry)
        assertNotNull(result)
        assertTrue(result!!.isFailure)
    }

    @Test fun `rejects plugin entry with neither string nor object source`() {
        val raw = """{"name": "x", "plugins": [{"name": "bad", "source": 42}]}"""
        val ex = runCatching { parseString(raw) }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            "exception should mention `source`",
            ex!!.message?.contains("source") == true
        )
    }

    @Test fun `rejects unknown source kind`() {
        val raw = """{"name": "x", "plugins": [{"name": "bad", "source": {"source": "ftp", "url": "ftp://"}}]}"""
        val ex = runCatching { parseString(raw) }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message?.contains("github") == true || ex.message?.contains("url") == true)
    }

    private fun parseString(json: String): dev.agentry.jetbrains.model.MarketplaceManifest {
        val file = tmpDir.newFile("inline.json").apply { writeText(json) }
        return parser.parseFile(file)
    }

    companion object {
        private val SAMPLE_CATALOG = """
            {
              "name": "my-team-plugins",
              "owner": {"name": "My Team", "email": "team@example.com"},
              "metadata": {"description": "Internal team plugin registry", "version": "1.0.0", "pluginRoot": "./plugins"},
              "plugins": [
                {
                  "name": "dev-toolkit",
                  "description": "Full-stack development tools",
                  "version": "1.0.0",
                  "source": "./plugins/dev-toolkit"
                },
                {
                  "name": "external-via-github",
                  "source": {"source": "github", "repo": "acme/external-plugin", "ref": "v2.0"}
                }
              ]
            }
        """.trimIndent()
    }
}
