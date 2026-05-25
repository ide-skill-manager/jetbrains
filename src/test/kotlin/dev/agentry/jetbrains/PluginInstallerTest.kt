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

    fun testAgentInstallDualWritesGithubAndClaudePaths() {
        val (root, projectDir) = newPluginAndProject("agent-plugin")
        File(root, "agents").mkdirs()
        File(root, "agents/foo.agent.md").writeText(
            "---\nname: foo\ndescription: 'Reviews diffs'\nmodel: claude-3-5-sonnet\n---\nBody"
        )
        val manifest = manifest(root, "agent-plugin")
        val components = listOf(
            PluginComponent.Agent(
                name = "foo",
                sourceFile = File(root, "agents/foo.agent.md"),
                description = "Reviews diffs"
            )
        )
        val report = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        assertTrue("expected full success, got: ${report.failed}", report.isFullSuccess)
        // Dual-write: BOTH paths exist so the agent reaches every tool in the ecosystem.
        val githubDest = File(projectDir, ".github/agents/foo.agent.md")
        val claudeDest = File(projectDir, ".claude/agents/foo.agent.md")
        assertTrue("`.github/agents/` write landed", githubDest.exists())
        assertTrue("`.claude/agents/` write landed", claudeDest.exists())
        // Both files have identical normalised content (we rewrite once, copy twice).
        assertEquals("dual-write content matches", githubDest.readText(), claudeDest.readText())
        val written = githubDest.readText()
        assertTrue("name preserved (quoted)", written.contains("name: 'foo'"))
        assertTrue("description preserved (quoted)", written.contains("description: 'Reviews diffs'"))
        assertTrue("body preserved", written.contains("Body"))
        assertTrue("other frontmatter passed through", written.contains("model: claude-3-5-sonnet"))
    }

    fun testAgentInstallBackfillsMissingDescription() {
        val (root, projectDir) = newPluginAndProject("agent-no-desc")
        File(root, "agents").mkdirs()
        File(root, "agents/quiet.agent.md").writeText("---\nname: quiet\n---\nFirst paragraph of the body.\n\nSecond paragraph.")
        val components = listOf(
            PluginComponent.Agent("quiet", File(root, "agents/quiet.agent.md"), description = null)
        )
        val report = PluginInstaller().installPlugin(manifest(root, "agent-no-desc"), components, InstallScope.Project(projectDir))
        assertTrue(report.isFullSuccess)
        val written = File(projectDir, ".github/agents/quiet.agent.md").readText()
        // First-paragraph only — broken "concat all paragraphs" impl would emit both.
        assertTrue(
            "expected first-paragraph-only description, got: $written",
            written.contains("description: 'First paragraph of the body.'")
        )
        assertFalse(
            "description must not include the second paragraph",
            written.lineSequence().any { it.startsWith("description:") && it.contains("Second paragraph") }
        )
    }

    fun testAgentInstallGlobalScopeNamespacesByPluginAndDualWrites() {
        val (root, _) = newPluginAndProject("ns-plugin")
        File(root, "agents").mkdirs()
        File(root, "agents/shared.agent.md").writeText("---\nname: shared\ndescription: 'x'\n---\n")
        val components = listOf(
            PluginComponent.Agent("shared", File(root, "agents/shared.agent.md"), description = "x")
        )
        val report = PluginInstaller().installPlugin(manifest(root, "ns-plugin"), components, InstallScope.Global)
        assertTrue("global install succeeded: ${report.failed}", report.isFullSuccess)
        // user.home is overridden to the fixture temp dir in setUp(). Both global locations
        // get the <plugin>__<name>.agent.md namespaced filename so two tools / plugins
        // shipping a same-named agent can't overwrite each other.
        val home = myFixture.tempDirFixture.tempDirPath
        assertTrue(
            "expected ~/.copilot/agents/ns-plugin__shared.agent.md",
            File(home, ".copilot/agents/ns-plugin__shared.agent.md").exists()
        )
        assertTrue(
            "expected ~/.claude/agents/ns-plugin__shared.agent.md",
            File(home, ".claude/agents/ns-plugin__shared.agent.md").exists()
        )
    }

    fun testAgentInstallRefusesSymlinkedSource() {
        val (root, projectDir) = newPluginAndProject("sym-agent")
        File(root, "agents").mkdirs()
        val secret = File(myFixture.tempDirFixture.tempDirPath, "secret").apply { writeText("S3CRET") }
        val link = File(root, "agents/leak.agent.md")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (_: Throwable) {
            return // FS doesn't allow symlinks; skip
        }
        val components = listOf(
            PluginComponent.Agent("leak", link, description = "x")
        )
        val report = PluginInstaller().installPlugin(manifest(root, "sym-agent"), components, InstallScope.Project(projectDir))
        assertTrue("symlinked agent source must fail", report.isFullFailure)
        // Neither dual-write destination should be created.
        assertFalse(File(projectDir, ".github/agents/leak.agent.md").exists())
        assertFalse(File(projectDir, ".claude/agents/leak.agent.md").exists())
    }

    fun testMixedSuccessAndFailureProducesPartialReport() {
        val (root, projectDir) = newPluginAndProject("mixed")
        File(root, "skills/ok").mkdirs()
        File(root, "skills/ok/SKILL.md").writeText("---\nname: ok\n---\n")
        // Force a real failure: an MCP component whose configFile doesn't exist on disk.
        // Hooks/MCP installers fail loudly if they can't read their config; skill installs fine.
        File(root, "agents").mkdirs()
        val components = listOf(
            PluginComponent.Skill("ok", File(root, "skills/ok"), File(root, "skills/ok/SKILL.md"), emptyList()),
            PluginComponent.McpServer(
                name = "broken",
                configFile = File(root, "does-not-exist.mcp.json"),
                bundledFiles = emptyList()
            )
        )
        val report = PluginInstaller().installPlugin(manifest(root, "mixed"), components, InstallScope.Project(projectDir))
        assertTrue("expected partial report", report.isPartial)
        assertEquals(1, report.installed.size)
        assertEquals(ComponentKind.SKILL, report.installed.single().kind)
        assertEquals(1, report.failed.size)
        assertEquals(ComponentKind.MCP_SERVER, report.failed.single().kind)
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
