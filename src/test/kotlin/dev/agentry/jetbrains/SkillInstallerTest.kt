package dev.agentry.jetbrains

import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.RegistryManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillInstallerTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun `install copies skill files to destination`() {
        // Set up a fake cached skill directory
        val cacheRoot = tmpDir.newFolder("cache")
        val skillSrc = File(cacheRoot, "my-skill").apply { mkdirs() }
        File(skillSrc, "skill.json").writeText("""{"name":"my-skill","version":"1.0.0"}""")
        File(skillSrc, "prompt.md").writeText("# My Skill\nDoes things.")

        // The manifest points to the source
        val manifest = SkillManifest(
            name = "my-skill",
            version = "1.0.0",
            displayName = "My Skill",
            installedPath = skillSrc.absolutePath
        )

        val projectDir = tmpDir.newFolder("project")
        val installer = SkillInstaller()
        val result = installer.install(manifest, InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)

        assertTrue(result.isSuccess)
        val dest = File(projectDir, ".claude/skills/my-skill")
        assertTrue(dest.exists())
        assertTrue(File(dest, "skill.json").exists())
        assertTrue(File(dest, "prompt.md").exists())
    }

    @Test
    fun `uninstall removes skill directory`() {
        val projectDir = tmpDir.newFolder("project")
        val skillDir = File(projectDir, ".claude/skills/my-skill").apply { mkdirs() }
        File(skillDir, "skill.json").writeText("""{"name":"my-skill"}""")
        assertTrue(skillDir.exists())

        val installer = SkillInstaller()
        val result = installer.uninstall("my-skill", InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)
        assertTrue(result.isSuccess)
        assertFalse(skillDir.exists())
    }

    @Test
    fun `isInstalled returns true when directory exists`() {
        val projectDir = tmpDir.newFolder("project")
        File(projectDir, ".claude/skills/existing-skill").mkdirs()

        val installer = SkillInstaller()
        assertTrue(installer.isInstalled("existing-skill", InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath))
        assertFalse(installer.isInstalled("missing-skill", InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath))
    }

    @Test
    fun `listInstalled returns manifests from installed skills`() {
        val projectDir = tmpDir.newFolder("project")
        val skillsDir = File(projectDir, ".claude/skills")
        val skill1 = File(skillsDir, "skill-a").apply { mkdirs() }
        val skill2 = File(skillsDir, "skill-b").apply { mkdirs() }
        File(skill1, "skill.json").writeText("""{"name":"skill-a","version":"1.0.0"}""")
        File(skill2, "package.json").writeText("""{"name":"skill-b","version":"2.0.0"}""")

        val installer = SkillInstaller()
        val installed = installer.listInstalled(InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)
        assertEquals(2, installed.size)
        val names = installed.map { it.name }.toSet()
        assertTrue(names.contains("skill-a"))
        assertTrue(names.contains("skill-b"))
    }

    @Test
    fun `install fails gracefully when source does not exist`() {
        val manifest = SkillManifest(
            name = "ghost-skill",
            installedPath = "/nonexistent/path/ghost-skill"
        )
        val projectDir = tmpDir.newFolder("project")
        val installer = SkillInstaller()
        val result = installer.install(manifest, InstallTarget.CLAUDE_PROJECT, projectDir.absolutePath)
        assertTrue(result.isFailure)
    }
}
