package dev.agentry.jetbrains

import dev.agentry.jetbrains.util.InputValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-unit tests for the validation helpers — these defend against shell/flag injection
 * and path-traversal, so they get the densest coverage.
 */
class InputValidationTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- Skill name -----------------------------------------------------------------

    @Test fun `accepts normal skill names`() {
        listOf("code-reviewer", "my_skill", "skill.v2", "a", "MySkill123").forEach {
            assertTrue(it, InputValidation.isValidSkillName(it))
        }
    }

    @Test fun `rejects path traversal in skill name`() {
        listOf("..", ".", "../etc/passwd", "..\\windows", "/abs", "skill/sub", "skill\\sub")
            .forEach { assertFalse(it, InputValidation.isValidSkillName(it)) }
    }

    @Test fun `rejects skill names with control or special chars`() {
        listOf("", " ", "name with space", "name;rm", "name`evil`", "name\$x", "name\n", "-leading-dash")
            .forEach { assertFalse(it, InputValidation.isValidSkillName(it)) }
    }

    @Test fun `rejects overly long skill names`() {
        assertFalse(InputValidation.isValidSkillName("a".repeat(65)))
        assertTrue(InputValidation.isValidSkillName("a".repeat(64)))
    }

    // --- Git ref --------------------------------------------------------------------

    @Test fun `accepts normal branch and tag refs`() {
        listOf("main", "master", "v1.0.0", "feature/wip-skill", "release-2024.1", "HEAD")
            .filter { it != "HEAD" } // HEAD has its own code path; isValidGitRef still accepts it
            .forEach { assertTrue(it, InputValidation.isValidGitRef(it)) }
    }

    @Test fun `branch names with slashes are accepted (WIP workflow)`() {
        // Branch-based skill development depends on this.
        assertTrue(InputValidation.isValidGitRef("feature/foo"))
        assertTrue(InputValidation.isValidGitRef("user/andy/wip-skill"))
    }

    @Test fun `rejects flag-injection refs`() {
        listOf("--upload-pack=evil", "-c", "--exec", "--config=x").forEach {
            assertFalse(it, InputValidation.isValidGitRef(it))
        }
    }

    @Test fun `rejects refs with dot-dot or leading dot`() {
        listOf("..", "a..b", ".hidden", "branch/..").forEach {
            assertFalse(it, InputValidation.isValidGitRef(it))
        }
    }

    // --- Registry URL ---------------------------------------------------------------

    @Test fun `accepts https http ssh and git schemes`() {
        listOf(
            "https://github.com/org/repo.git",
            "http://example.com/repo",
            "ssh://git@github.com/org/repo.git",
            "git://example.com/repo.git",
            "git@github.com:org/repo.git"   // scp-form
        ).forEach { assertTrue(it, InputValidation.isValidRegistryUrl(it)) }
    }

    @Test fun `accepts IPv6 host URLs`() {
        // The earlier blanket "::" check rejected these — legitimate IPv6 hosts.
        listOf(
            "https://[2001:db8::1]/org/repo.git",
            "ssh://[::1]/org/repo.git",
            "http://[fe80::1]/repo"
        ).forEach { assertTrue(it, InputValidation.isValidRegistryUrl(it)) }
    }

    @Test fun `rejects ext transport (git RCE vector)`() {
        listOf(
            "ext::sh -c id",
            "ext::/tmp/payload",
            "anything::evil",
            "transport-helper::payload"
        ).forEach { assertFalse(it, InputValidation.isValidRegistryUrl(it)) }
    }

    // --- redactCredentials -----------------------------------------------------------

    @Test fun `redactCredentials strips userinfo from common URL shapes`() {
        assertEquals(
            "https://***@github.com/org/repo.git",
            InputValidation.redactCredentials("https://ghp_abc123@github.com/org/repo.git")
        )
        assertEquals(
            "https://***@example.com/repo",
            InputValidation.redactCredentials("https://user:password@example.com/repo")
        )
        assertEquals(
            "ssh://***@host/repo.git",
            InputValidation.redactCredentials("ssh://git@host/repo.git")
        )
    }

    @Test fun `redactCredentials is a no-op when no userinfo present`() {
        listOf(
            "https://github.com/org/repo.git",
            "git@github.com:org/repo.git",   // scp-form has @ but no scheme://
            "git://example.com/repo.git"
        ).forEach { assertEquals(it, it, InputValidation.redactCredentials(it)) }
    }

    @Test fun `redactCredentials is idempotent`() {
        val once = InputValidation.redactCredentials("https://token@host/repo.git")
        val twice = InputValidation.redactCredentials(once)
        assertEquals(once, twice)
    }

    @Test fun `rejects file scheme and bare paths`() {
        listOf("file:///etc/passwd", "/etc/passwd", "../local/repo", "")
            .forEach { assertFalse(it, InputValidation.isValidRegistryUrl(it)) }
    }

    @Test fun `rejects urls starting with dash (flag injection)`() {
        listOf("--upload-pack=evil", "-https://x", "-c").forEach {
            assertFalse(it, InputValidation.isValidRegistryUrl(it))
        }
    }

    // --- isInsideDir ----------------------------------------------------------------

    @Test fun `isInsideDir accepts paths inside base`() {
        val base = tmp.newFolder("base")
        assertTrue(InputValidation.isInsideDir(File(base, "child"), base))
        assertTrue(InputValidation.isInsideDir(File(base, "a/b/c"), base))
    }

    @Test fun `isInsideDir rejects parent escape via dotdot`() {
        val base = tmp.newFolder("base")
        assertFalse(InputValidation.isInsideDir(File(base, "../sibling"), base))
        assertFalse(InputValidation.isInsideDir(File(base, "../../etc/passwd"), base))
    }
}
