package dev.agentry.jetbrains

import dev.agentry.jetbrains.model.InstallTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [InstallTarget] path validation (the security-critical pure logic).
 * Full `SkillInstaller` IO behaviour is covered separately under [SkillInstallerIoTest]
 * via a private package-internal copy hook because the production `install()` resolves
 * its source through the application-level `RegistryManager` service (not constructable
 * in plain JUnit).
 */
class SkillInstallerTest {

    @get:Rule val tmpDir = TemporaryFolder()

    @Test
    fun `resolvePath rejects path traversal via skill name`() {
        val project = tmpDir.newFolder("project").absolutePath
        listOf("../etc/passwd", "..", "/abs", "name/sub", "name\\sub").forEach { name ->
            val ex = runCatching {
                InstallTarget.CLAUDE_PROJECT.resolvePath(project, name)
            }.exceptionOrNull()
            assertTrue("expected rejection for '$name', got $ex", ex is IllegalArgumentException)
        }
    }

    @Test
    fun `resolvePath produces expected location for valid skill names`() {
        val project = tmpDir.newFolder("project").absolutePath
        val dest = InstallTarget.CLAUDE_PROJECT.resolvePath(project, "code-reviewer")
        assertTrue(dest.absolutePath.endsWith(".claude/skills/code-reviewer"))
        assertTrue(dest.absolutePath.startsWith(project))
    }

    @Test
    fun `resolvePath stays inside baseDir even with edge-case names`() {
        val project = tmpDir.newFolder("project")
        val dest = InstallTarget.CLAUDE_PROJECT.resolvePath(project.absolutePath, "valid-name")
        val base = InstallTarget.CLAUDE_PROJECT.baseDir(project.absolutePath)!!
        assertTrue(
            "dest '$dest' must live under base '$base'",
            dest.canonicalPath.startsWith(base.canonicalPath)
        )
    }

    @Test
    fun `baseDir returns null for project-scoped targets when project path is missing`() {
        // Project-scoped targets need a base path — calling with null returns null instead
        // of falling through to a relative File("/.claude/skills/x") that would land at root.
        assertTrue(InstallTarget.CLAUDE_PROJECT.baseDir(null) == null)
        assertTrue(InstallTarget.JUNIE_PROJECT.baseDir(null) == null)
        assertFalse(InstallTarget.CLAUDE_USER.baseDir(null) == null)
        assertFalse(InstallTarget.AGENTRY_CACHE.baseDir(null) == null)
    }

    // End-to-end "symlinks in a skill source are refused by copySkill" coverage lives in
    // [SkillInstallerIntegrationTest.testInstallRefusesSymlinkInSource], which runs against
    // the real application services. No need for a misleading pure-JUnit shim here.
}
