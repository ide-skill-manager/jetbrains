package dev.agentry.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.PluginInstaller
import dev.agentry.jetbrains.model.ComponentKind
import dev.agentry.jetbrains.model.ManifestDialect
import dev.agentry.jetbrains.model.PluginAuthor
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File

/**
 * End-to-end install tests. Each builds a small in-memory plugin tree and runs
 * [PluginInstaller.installPlugin] against a temp "project" directory, then asserts the
 * expected files landed at the expected paths.
 *
 * `user.home` is overridden to the fixture temp dir so the installer's
 * `~/.copilot/skills/...` / `~/.agentry/plugin-data/...` writes never leak into the
 * developer's real home directory. Same isolation pattern as `SkillInstallerIntegrationTest`.
 */
class PluginInstallerTest : BasePlatformTestCase() {

    private var originalUserHome: String? = null

    override fun setUp() {
        super.setUp()
        originalUserHome = System.getProperty("user.home")
        System.setProperty("user.home", myFixture.tempDirFixture.tempDirPath)
    }

    override fun tearDown() {
        try {
            originalUserHome?.let { System.setProperty("user.home", it) }
                ?: System.clearProperty("user.home")
        } finally {
            super.tearDown()
        }
    }

    fun testSkillInstallCopiesFilesAndBackfillsName() {
        val (root, projectDir) = newPluginAndProject("skill-only")
        File(root, "skills/my-skill").mkdirs()
        // SKILL.md without a `name` frontmatter — installer should backfill.
        File(root, "skills/my-skill/SKILL.md").writeText("---\ndescription: x\n---\nbody")
        File(root, "skills/my-skill/asset.txt").writeText("asset data")

        val manifest = manifest(root, "skill-only")
        val components = listOf(
            PluginComponent.Skill(
                name = "my-skill",
                sourceDir = File(root, "skills/my-skill"),
                skillFile = File(root, "skills/my-skill/SKILL.md"),
                supportFiles = listOf(File(root, "skills/my-skill/asset.txt"))
            )
        )
        val report = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        assertTrue("expected full success, got: ${report.failed}", report.isFullSuccess)
        val dest = File(projectDir, ".claude/skills/my-skill")
        assertTrue(File(dest, "SKILL.md").exists())
        assertTrue(File(dest, "asset.txt").exists())
        val written = File(dest, "SKILL.md").readText()
        assertTrue("expected `name: my-skill` backfilled, got: $written", written.contains("name: my-skill"))
    }

    fun testCommandInstallTranslatesArgumentHint() {
        val (root, projectDir) = newPluginAndProject("cmd-only")
        File(root, "commands").mkdirs()
        File(root, "commands/summarize.md").writeText(
            "---\nname: summarize\ndescription: summarize a PR\nargument-hint: <ref>\n---\nbody"
        )
        val manifest = manifest(root, "cmd-only")
        val components = listOf(
            PluginComponent.Command(
                name = "summarize",
                sourceFile = File(root, "commands/summarize.md"),
                description = "summarize a PR",
                argumentHint = "<ref>"
            )
        )
        val report = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        assertTrue(report.isFullSuccess)
        val dest = File(projectDir, ".github/prompts/summarize.prompt.md")
        assertTrue(dest.exists())
        val text = dest.readText()
        assertTrue("kept description", text.contains("description: summarize a PR"))
        assertFalse("dropped argument-hint", text.contains("argument-hint"))
    }

    fun testHookInstallExpandsPluginRootVariable() {
        val (root, projectDir) = newPluginAndProject("hook-plugin")
        File(root, "hooks").mkdirs()
        File(root, "hooks/hooks.json").writeText(
            """{"hooks":{"PreToolUse":[{"command":"${'$'}{CLAUDE_PLUGIN_ROOT}/scripts/check.sh"}]}}"""
        )
        File(root, "scripts").mkdirs()
        File(root, "scripts/check.sh").writeText("#!/bin/sh\necho ok")
        val manifest = manifest(root, "hook-plugin")
        val components = listOf(
            PluginComponent.Hook(
                name = "hooks",
                configFile = File(root, "hooks/hooks.json"),
                scripts = listOf(File(root, "scripts/check.sh"))
            )
        )
        val report = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        assertTrue(report.isFullSuccess)
        val destDir = File(projectDir, ".github/hooks/hook-plugin")
        assertTrue("hook config landed", File(destDir, "hooks.json").exists())
        assertTrue("script travelled with hook", File(destDir, "scripts/check.sh").exists())
        val cfg = File(destDir, "hooks.json").readText()
        assertFalse("variable expanded", cfg.contains("\${CLAUDE_PLUGIN_ROOT}"))
        assertTrue(cfg.contains(destDir.canonicalPath))
    }

    fun testAgentInstallSurfacesAsRecoverableFailure() {
        val (root, projectDir) = newPluginAndProject("agent-plugin")
        File(root, "agents").mkdirs()
        File(root, "agents/foo.agent.md").writeText("---\nname: foo\n---\nbody")
        val manifest = manifest(root, "agent-plugin")
        val components = listOf(
            PluginComponent.Agent(
                name = "foo",
                sourceFile = File(root, "agents/foo.agent.md"),
                description = null
            )
        )
        val report = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        assertTrue(report.isFullFailure)
        val err = report.failed.single()
        assertEquals(ComponentKind.AGENT, err.kind)
        assertTrue("agent install errors are recoverable", err.recoverable)
    }

    fun testMixedSuccessAndFailureProducesPartialReport() {
        val (root, projectDir) = newPluginAndProject("mixed")
        File(root, "skills/ok").mkdirs()
        File(root, "skills/ok/SKILL.md").writeText("---\nname: ok\n---\n")
        File(root, "agents").mkdirs()
        File(root, "agents/bad.agent.md").writeText("---\nname: bad\n---\n")
        val manifest = manifest(root, "mixed")
        val components = listOf(
            PluginComponent.Skill("ok", File(root, "skills/ok"), File(root, "skills/ok/SKILL.md"), emptyList()),
            PluginComponent.Agent("bad", File(root, "agents/bad.agent.md"), null)
        )
        val report = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        assertTrue(report.isPartial)
        assertEquals(1, report.installed.size)
        assertEquals(ComponentKind.SKILL, report.installed.single().kind)
        assertEquals(1, report.failed.size)
        assertEquals(ComponentKind.AGENT, report.failed.single().kind)
    }

    private fun newPluginAndProject(name: String): Pair<File, File> {
        val temp = File(myFixture.tempDirFixture.tempDirPath, "test-${System.nanoTime()}").apply { mkdirs() }
        val root = File(temp, name).apply { mkdirs() }
        val project = File(temp, "project").apply { mkdirs() }
        return root to project
    }

    private fun manifest(root: File, name: String): PluginManifest = PluginManifest(
        name = name,
        displayName = null,
        version = null,
        description = null,
        author = null as PluginAuthor?,
        homepage = null,
        repository = null,
        license = null,
        keywords = emptyList(),
        skills = emptyList(),
        commands = emptyList(),
        agents = emptyList(),
        hooks = emptyList(),
        mcpServers = emptyList(),
        pluginRoot = root,
        dialect = ManifestDialect.GITHUB
    )
}
