package dev.agentry.jetbrains

import dev.agentry.jetbrains.registry.ManifestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManifestParserTest {

    @get:Rule val tmpDir = TemporaryFolder()
    private val parser = ManifestParser()

    @Test fun `parse valid manifest`() {
        val file = tmpDir.newFile("package.json")
        file.writeText("""
            {
              "name": "my-skill",
              "version": "1.2.3",
              "displayName": "My Skill",
              "description": "Does something useful"
            }
        """.trimIndent())
        val m = parser.parseFile(file, "https://example.com/registry")
        assertNotNull(m)
        assertEquals("my-skill", m!!.name)
        assertEquals("1.2.3", m.version)
        assertEquals("My Skill", m.displayName)
        assertEquals("Does something useful", m.description)
        assertEquals("https://example.com/registry", m.sourceRegistry)
    }

    @Test fun `parse minimal manifest defaults version`() {
        val file = tmpDir.newFile("skill.json")
        file.writeText("""{"name": "minimal-skill"}""")
        val m = parser.parseFile(file)
        assertNotNull(m)
        assertEquals("minimal-skill", m!!.name)
        assertEquals("0.0.1", m.version)
    }

    @Test fun `returns null for manifest with missing name`() {
        val file = tmpDir.newFile("empty.json")
        file.writeText("""{}""")
        assertNull(parser.parseFile(file))
    }

    @Test fun `rejects manifest with path-traversal name`() {
        val file = tmpDir.newFile("evil.json")
        file.writeText("""{"name": "../../etc/passwd"}""")
        assertNull(parser.parseFile(file))
    }

    @Test fun `scan directory finds skills in subdirectories`() {
        val root = tmpDir.newFolder("registry")
        File(root, "skill-one").apply { mkdirs() }
            .let { File(it, "package.json").writeText("""{"name":"skill-one","version":"0.1.0"}""") }
        File(root, "skill-two").apply { mkdirs() }
            .let { File(it, "skill.json").writeText("""{"name":"skill-two","version":"0.2.0"}""") }

        val results = parser.scanDirectory(root, "https://test-registry.example.com")
        assertEquals(2, results.size)
        val names = results.map { it.name }.toSet()
        assertTrue(names.contains("skill-one"))
        assertTrue(names.contains("skill-two"))
        results.forEach { assertEquals("https://test-registry.example.com", it.sourceRegistry) }
    }

    @Test fun `scan empty directory returns empty list`() {
        assertTrue(parser.scanDirectory(tmpDir.newFolder("empty-registry")).isEmpty())
    }
}
