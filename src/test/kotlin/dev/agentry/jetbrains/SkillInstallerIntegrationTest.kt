package dev.agentry.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.util.AgentryPaths
import java.io.File
import java.nio.file.Files

/**
 * End-to-end test of the install pipeline against the *real* application services
 * (`RegistryManager` + `SkillInstaller`), bypassing only the git step by pre-populating the
 * cache directory by hand. This exercises `localDirFor`, manifest discovery,
 * `resolveSourceDir`, the symlink-safe copy, and the `InstallTarget` path resolver.
 *
 * Pure git-clone behaviour is intentionally not covered here; that path is exercised by
 * the manual smoke checklist in CONTRIBUTING.md.
 */
class SkillInstallerIntegrationTest : BasePlatformTestCase() {

    fun testInstallCopiesFilesFromCachedRegistry() {
        val registryUrl = "https://example.com/test-registry.git"
        val skillName = "example-skill"

        // 1. Pre-populate the registry cache as if RegistryManager had already cloned.
        val cacheDir = RegistryManager.getInstance().localDirFor(RegistrySource(url = registryUrl))
        val skillSrc = File(cacheDir, skillName).apply { mkdirs() }
        File(skillSrc, "skill.json").writeText(
            """{"name":"$skillName","version":"1.2.3","displayName":"Example"}"""
        )
        File(skillSrc, "prompt.md").writeText("# Example\nbody")

        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "project").apply { mkdirs() }

        try {
            // 2. Build a manifest as if it had come back from fetchSource.
            val manifest = SkillManifest(
                name = skillName,
                version = "1.2.3",
                displayName = "Example",
                sourceRegistry = registryUrl
            )

            // 3. Install.
            val result = SkillInstaller.getInstance()
                .install(manifest, InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)

            // 4. Assert files copied to the expected target.
            assertTrue("install failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            val dest = File(projectDir, ".claude/skills/$skillName")
            assertTrue(File(dest, "skill.json").exists())
            assertTrue(File(dest, "prompt.md").exists())

            // 5. listInstalled discovers it.
            val installed = SkillInstaller.getInstance()
                .listInstalled(InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)
            assertTrue(installed.any { it.manifest.name == skillName })

            // 6. Uninstall removes it.
            val rm = SkillInstaller.getInstance()
                .uninstall(skillName, InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)
            assertTrue(rm.isSuccess)
            assertFalse(dest.exists())
        } finally {
            // Don't poison ~/.agentry/cache between test runs.
            cacheDir.deleteRecursively()
        }
    }

    fun testInstallRefusesSymlinkInSource() {
        val registryUrl = "https://example.com/symlinky-registry.git"
        val skillName = "symlinky"
        val cacheDir = RegistryManager.getInstance().localDirFor(RegistrySource(url = registryUrl))
        val skillSrc = File(cacheDir, skillName).apply { mkdirs() }
        File(skillSrc, "skill.json").writeText("""{"name":"$skillName","version":"1.0.0"}""")

        // Drop a symlink into the registry source. Skip if the FS doesn't allow it.
        val outsideTarget = File(myFixture.tempDirFixture.tempDirPath, "secret").apply { writeText("SECRET") }
        val link = File(skillSrc, "leak")
        try {
            Files.createSymbolicLink(link.toPath(), outsideTarget.toPath())
        } catch (e: Throwable) {
            return // not supported on this FS — skip
        }

        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "project-sym").apply { mkdirs() }
        try {
            val manifest = SkillManifest(name = skillName, sourceRegistry = registryUrl)
            val result = SkillInstaller.getInstance()
                .install(manifest, InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)
            assertTrue("install should have failed on symlink", result.isFailure)
            assertTrue(
                "exception should mention symlink",
                result.exceptionOrNull()?.message?.contains("symlink", ignoreCase = true) == true
                    || result.exceptionOrNull()?.cause?.message?.contains("symlink", ignoreCase = true) == true
            )
            // No partial copy: target directory should not contain 'leak'.
            val dest = File(projectDir, ".claude/skills/$skillName/leak")
            assertFalse(dest.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    fun testCachePathIsCollisionResistant() {
        // Two URLs that the old `take(80) + sanitize` scheme could collapse onto the same
        // directory now hash apart cleanly.
        val a = RegistrySource(url = "https://github.com/org/very-long-skill-package-name-that-exceeds-typical-length.git")
        val b = RegistrySource(url = "https://github.com/org/very-long-skill-package-name-that-exceeds-completely.git")
        val da = RegistryManager.getInstance().localDirFor(a)
        val db = RegistryManager.getInstance().localDirFor(b)
        assertFalse("Cache dirs must differ across distinct URLs", da == db)
        // Both must live under the registered cache root.
        assertTrue(da.absolutePath.startsWith(AgentryPaths.registryCacheRoot.absolutePath))
    }
}
