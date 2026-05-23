package dev.agentry.jetbrains

import dev.agentry.jetbrains.registry.BranchListService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure ls-remote output parser. The subprocess behaviour itself isn't
 * unit-tested (git isn't guaranteed available in every test runner); end-to-end coverage
 * lives in the manual smoke checklist.
 */
class BranchListServiceTest {

    private val service = BranchListService()

    @Test
    fun `parses branches and tags from typical output`() {
        val raw = """
            ref: refs/heads/main	HEAD
            abc123	HEAD
            abc123	refs/heads/main
            def456	refs/heads/feature/wip-skill
            beef00	refs/heads/release-2024
            tag111	refs/tags/v1.0.0
            tag222	refs/tags/v0.9.0
            deef00	refs/tags/v1.0.0^{}
        """.trimIndent()

        val refs = service.parse(raw)
        assertEquals(listOf("feature/wip-skill", "main", "release-2024"), refs.branches)
        assertEquals(listOf("v0.9.0", "v1.0.0"), refs.tags)
        assertEquals("main", refs.defaultRef)
    }

    @Test
    fun `default ref is null when no symref present`() {
        val raw = """
            abc123	refs/heads/main
            def456	refs/heads/dev
        """.trimIndent()
        val refs = service.parse(raw)
        assertNull(refs.defaultRef)
        assertEquals(listOf("dev", "main"), refs.branches)
    }

    @Test
    fun `parses output with empty lines and trailing whitespace`() {
        val raw = "ref: refs/heads/main\tHEAD\n\nabc123\trefs/heads/main   \n  \n"
        val refs = service.parse(raw)
        assertEquals("main", refs.defaultRef)
        assertEquals(listOf("main"), refs.branches)
    }

    @Test
    fun `empty output yields empty refs`() {
        val refs = service.parse("")
        assertTrue(refs.isEmpty)
        assertNull(refs.defaultRef)
    }

    @Test
    fun `default ref strips refs prefix`() {
        val raw = "ref: refs/heads/develop\tHEAD\nabc\trefs/heads/develop\n"
        assertEquals("develop", service.parse(raw).defaultRef)
    }

    @Test
    fun `invalid registry URL fails fast without launching git`() {
        val result = service.fetch("ext::sh -c id")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
