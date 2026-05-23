package dev.agentry.jetbrains

import dev.agentry.jetbrains.registry.FrontmatterReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrontmatterReaderTest {

    @Test fun `reads simple frontmatter`() {
        val fm = FrontmatterReader.read(
            """
            ---
            name: csv-analysis
            description: Analyse CSV files
            ---

            Body goes here.
            """.trimIndent()
        )
        assertEquals("csv-analysis", fm.name)
        assertEquals("Analyse CSV files", fm.description)
        assertNull(fm.argumentHint)
    }

    @Test fun `accepts quoted values`() {
        val fm = FrontmatterReader.read(
            """
            ---
            name: "with spaces"
            description: 'single quotes work too'
            ---
            """.trimIndent()
        )
        assertEquals("with spaces", fm.name)
        assertEquals("single quotes work too", fm.description)
    }

    @Test fun `argument-hint is read by both spellings`() {
        val a = FrontmatterReader.read("---\nargument-hint: foo\n---\n").argumentHint
        val b = FrontmatterReader.read("---\nargumentHint: bar\n---\n").argumentHint
        assertEquals("foo", a)
        assertEquals("bar", b)
    }

    @Test fun `returns empty when there is no frontmatter fence`() {
        val fm = FrontmatterReader.read("# Just a heading\n\nBody.")
        assertNull(fm.name)
        assertNull(fm.description)
    }

    @Test fun `ignores comments after whitespace`() {
        val fm = FrontmatterReader.read(
            """
            ---
            name: code-reviewer  # the comment is dropped
            description: it works
            ---
            """.trimIndent()
        )
        assertEquals("code-reviewer", fm.name)
        assertEquals("it works", fm.description)
    }

    @Test fun `does not eat hash inside quoted value`() {
        val fm = FrontmatterReader.read(
            """
            ---
            description: "contains a # inside quotes"
            ---
            """.trimIndent()
        )
        assertEquals("contains a # inside quotes", fm.description)
    }

    @Test fun `blank lines inside frontmatter are skipped`() {
        val fm = FrontmatterReader.read(
            """
            ---

            name: a

            description: b
            ---
            """.trimIndent()
        )
        assertEquals("a", fm.name)
        assertEquals("b", fm.description)
    }

    @Test fun `unknown keys are dropped`() {
        val fm = FrontmatterReader.read(
            """
            ---
            name: x
            license: MIT
            tools: [a, b]
            future-field: anything
            ---
            """.trimIndent()
        )
        assertEquals("x", fm.name)
        // `license`, `tools`, `future-field` are silently dropped; only check what we expose.
    }
}
