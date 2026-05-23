package dev.agentry.jetbrains

import dev.agentry.jetbrains.registry.ManifestParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ManifestParserTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val parser = ManifestParser()

    @Test
    fun `parse valid package json`() {
        val file = tmpDir.newFile("package.json")
        file.writeText("""
            {
              "name": "my-skill",
              "version": "1.2.3",
              "displayName": "My Skill",
              "description": "Does something useful",
              "publisher": "acme",
              "categories": ["AI", "Tools"],
              "keywords": ["agent", "skill"],
              "license": "MIT"
            }
        """.trimIndent())

        val manifest = parser.parseFile(file, "https://example.com/registry")
        assertNotNull(manifest)
        assertEquals("my-skill", manifest!!.name)
        assertEquals("1.2.3", manifest.version)
        assertEquals("My Skill", manifest.displayName)
        assertEquals("Does something useful", manifest.description)
        assertEquals("acme", manifest.publisher)
        assertEquals(listOf("AI", "Tools"), manifest.categories)
        assertEquals(listOf("agent", "skill"), manifest.tags)
        assertEquals("MIT", manifest.license)
        assertEquals("https://example.com/registry", manifest.sourceRegistry)
    }

    @Test
    fun `parse minimal manifest`() {
        val file = tmpDir.newFile("skill.json")
        file.writeText("""{"name": "minimal-skill"}""")
        val manifest = parser.parseFile(file)
        assertNotNull(manifest)
        assertEquals("minimal-skill", manifest!!.name)
        assertEquals("0.0.1", manifest.version)
    }

    @Test
    fun `returns null for empty manifest`() {
        val file = tmpDir.newFile("empty.json")
        file.writeText("""{}""")
        assertNull(parser.parseFile(file))
    }

    @Test
    fun `scan directory with subdirectories`() {
        val root = tmpDir.newFolder("registry")
        val skill1 = File(root, "skill-one").apply { mkdirs() }
        val skill2 = File(root, "skill-two").apply { mkdirs() }
        File(skill1, "package.json").writeText("""{"name":"skill-one","version":"0.1.0"}""")
        File(skill2, "skill.json").writeText("""{"name":"skill-two","version":"0.2.0"}""")

        val results = parser.scanDirectory(root, "https://test-registry.example.com")
        assertEquals(2, results.size)
        val names = results.map { it.name }.toSet()
        assertTrue(names.contains("skill-one"))
        assertTrue(names.contains("skill-two"))
        results.forEach { assertEquals("https://test-registry.example.com", it.sourceRegistry) }
    }

    @Test
    fun `scan empty directory returns empty list`() {
        val root = tmpDir.newFolder("empty-registry")
        val results = parser.scanDirectory(root)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `parse repository as string`() {
        val file = tmpDir.newFile("repo_str.json")
        file.writeText("""{"name":"s","repository":"https://github.com/example/skill"}""")
        val manifest = parser.parseFile(file)
        assertEquals("https://github.com/example/skill", manifest?.repository)
    }

    @Test
    fun `parse repository as object`() {
        val file = tmpDir.newFile("repo_obj.json")
        file.writeText("""{"name":"s","repository":{"type":"git","url":"https://github.com/example/skill"}}""")
        val manifest = parser.parseFile(file)
        assertEquals("https://github.com/example/skill", manifest?.repository)
    }
}
