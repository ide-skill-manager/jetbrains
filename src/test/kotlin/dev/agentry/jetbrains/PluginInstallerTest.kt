package dev.agentry.jetbrains

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.actions.INSTALL_TARGET_DATA_KEY
import dev.agentry.jetbrains.actions.resolveInstallScope
import dev.agentry.jetbrains.actions.uninstallComponents
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.PluginInstallState
import dev.agentry.jetbrains.install.PluginInstaller
import dev.agentry.jetbrains.settings.AgentrySettings
import dev.agentry.jetbrains.model.ComponentKind
import dev.agentry.jetbrains.model.InstallTarget
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

    fun testSkillInstallGlobalScopeDualWritesToCopilotAndClaude() {
        val (root, _) = newPluginAndProject("dualwrite-skill")
        File(root, "skills/probe").mkdirs()
        File(root, "skills/probe/SKILL.md").writeText("---\nname: probe\n---\nBody")
        val manifest = manifest(root, "dualwrite-skill")
        val components = listOf(
            PluginComponent.Skill(
                name = "probe",
                sourceDir = File(root, "skills/probe"),
                skillFile = File(root, "skills/probe/SKILL.md"),
                supportFiles = emptyList()
            )
        )
        val report = PluginInstaller().installPlugin(manifest, components, InstallScope.Global)
        assertTrue("global install succeeded: ${report.failed}", report.isFullSuccess)
        val home = myFixture.tempDirFixture.tempDirPath  // user.home is overridden in setUp()
        assertTrue(
            "~/.copilot/skills/probe/SKILL.md exists",
            File(home, ".copilot/skills/probe/SKILL.md").exists()
        )
        assertTrue(
            "~/.claude/skills/probe/SKILL.md exists",
            File(home, ".claude/skills/probe/SKILL.md").exists()
        )
        // Both copies should have identical content.
        val copilotText = File(home, ".copilot/skills/probe/SKILL.md").readText()
        val claudeText = File(home, ".claude/skills/probe/SKILL.md").readText()
        assertEquals("dual-write content matches", copilotText, claudeText)
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

    fun testLocationsOfEmptyWhenNothingInstalled() {
        val (root, projectDir) = newPluginAndProject("loc-empty")
        val manifest = manifest(root, "loc-empty")
        val component = PluginComponent.Skill(
            name = "untouched",
            sourceDir = File(root, "skills/untouched"),
            skillFile = File(root, "skills/untouched/SKILL.md"),
            supportFiles = emptyList()
        )
        val locs = PluginInstallState.locationsOf(component, manifest, projectDir.absolutePath)
        assertTrue("expected empty, got $locs", locs.isEmpty())
    }

    fun testLocationsOfReportsProjectAfterProjectInstall() {
        val (root, projectDir) = newPluginAndProject("loc-proj")
        File(root, "skills/here").mkdirs()
        File(root, "skills/here/SKILL.md").writeText("---\nname: here\n---\n")
        val manifest = manifest(root, "loc-proj")
        val components = listOf(
            PluginComponent.Skill("here", File(root, "skills/here"),
                File(root, "skills/here/SKILL.md"), emptyList())
        )
        PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        val locs = PluginInstallState.locationsOf(components.first(), manifest, projectDir.absolutePath)
        assertEquals(setOf<InstallScope>(InstallScope.Project(File(projectDir.absolutePath))), locs)
    }

    fun testLocationsOfReportsGlobalAfterGlobalInstall() {
        // user.home is overridden to the fixture temp dir in setUp() (same pattern as
        // testAgentInstallGlobalScopeNamespacesByPluginAndDualWrites).
        val (root, _) = newPluginAndProject("loc-global")
        File(root, "skills/g").mkdirs()
        File(root, "skills/g/SKILL.md").writeText("---\nname: g\n---\n")
        val manifest = manifest(root, "loc-global")
        val components = listOf(
            PluginComponent.Skill("g", File(root, "skills/g"),
                File(root, "skills/g/SKILL.md"), emptyList())
        )
        PluginInstaller().installPlugin(manifest, components, InstallScope.Global)
        // projectBasePath = null confirms the null-project case doesn't accidentally swallow Global.
        val locs = PluginInstallState.locationsOf(components.first(), manifest, projectBasePath = null)
        assertEquals(setOf<InstallScope>(InstallScope.Global), locs)
    }

    // -------------------------------------------------------------------------
    // resolveInstallScope unit tests (Task 6 — core bug fix)
    // -------------------------------------------------------------------------

    fun testResolveInstallScopeReturnsGlobalWhenPickerSetsClaudeUser() {
        val ctx = DataContext { id ->
            if (id == INSTALL_TARGET_DATA_KEY.name) InstallTarget.CLAUDE_USER else null
        }
        val resolved = resolveInstallScope(ctx, "/tmp/proj")
        assertEquals(InstallScope.Global, resolved)
    }

    fun testResolveInstallScopeReturnsProjectWhenPickerSetsClaudeProject() {
        val ctx = DataContext { id ->
            if (id == INSTALL_TARGET_DATA_KEY.name) InstallTarget.CLAUDE_PROJECT else null
        }
        val resolved = resolveInstallScope(ctx, "/tmp/proj")
        assertEquals(InstallScope.Project(File("/tmp/proj")), resolved)
    }

    fun testResolveInstallScopeFallsBackToSettingsWhenNoPicker() {
        val settings = AgentrySettings.getInstance()
        val originalDefault = settings.defaultInstallTarget
        try {
            settings.defaultInstallTarget = InstallTarget.CLAUDE_USER
            val ctx = DataContext { _ -> null }
            val resolved = resolveInstallScope(ctx, "/tmp/proj")
            // Settings default forced to CLAUDE_USER above → Global
            assertEquals(InstallScope.Global, resolved)
        } finally {
            settings.defaultInstallTarget = originalDefault
        }
    }

    fun testResolveInstallScopeFallsBackToGlobalWhenClaudeProjectButNoBasePath() {
        val ctx = DataContext { id ->
            if (id == INSTALL_TARGET_DATA_KEY.name) InstallTarget.CLAUDE_PROJECT else null
        }
        val resolved = resolveInstallScope(ctx, null)
        assertEquals(InstallScope.Global, resolved)
    }

    fun testInstallAtUserScopeWhenAlreadyAtProjectEndsUpAtBoth() {
        val (root, projectDir) = newPluginAndProject("both-scope-agent")
        File(root, "agents").mkdirs()
        File(root, "agents/dual.agent.md").writeText("---\nname: dual\ndescription: 'x'\n---\n")
        val components = listOf(
            PluginComponent.Agent("dual", File(root, "agents/dual.agent.md"), description = "x")
        )
        val manifest = manifest(root, "both-scope-agent")

        // First install: Project scope.
        val first = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
        assertTrue("project install OK", first.isFullSuccess)

        // Second install: Global scope. Should NOT remove project files.
        val second = PluginInstaller().installPlugin(manifest, components, InstallScope.Global)
        assertTrue("global install OK", second.isFullSuccess)

        val home = myFixture.tempDirFixture.tempDirPath  // user.home is overridden in setUp()
        assertTrue("project dest still exists",
            File(projectDir, ".github/agents/dual.agent.md").exists())
        assertTrue("project .claude dest still exists",
            File(projectDir, ".claude/agents/dual.agent.md").exists())
        assertTrue("global .copilot dest now exists",
            File(home, ".copilot/agents/both-scope-agent__dual.agent.md").exists())
        assertTrue("global .claude dest now exists",
            File(home, ".claude/agents/both-scope-agent__dual.agent.md").exists())

        // PluginInstallState.locationsOf reports both.
        val locs = PluginInstallState.locationsOf(components.first(), manifest, projectDir.absolutePath)
        assertEquals(setOf<InstallScope>(InstallScope.Project(File(projectDir.absolutePath)), InstallScope.Global), locs)
    }

    // -------------------------------------------------------------------------
    // Regression: uninstall must actually delete — not silently succeed on partial failure
    // -------------------------------------------------------------------------

    fun testUninstallActuallyDeletesPrimaryDestination() {
        // Use a distinct skill name to avoid colliding with the global "probe" installation
        // created by testSkillInstallGlobalScopeDualWritesToCopilotAndClaude (user.home is
        // shared across all tests in the class, so leftovers from other tests persist).
        val (root, projectDir) = newPluginAndProject("delete-check")
        val skillName = "uninstall-regression-skill"
        File(root, "skills/$skillName").mkdirs()
        File(root, "skills/$skillName/SKILL.md").writeText("---\nname: $skillName\n---\n")
        val component = PluginComponent.Skill(
            name = skillName,
            sourceDir = File(root, "skills/$skillName"),
            skillFile = File(root, "skills/$skillName/SKILL.md"),
            supportFiles = emptyList()
        )
        val manifest = manifest(root, "delete-check")

        val installReport = PluginInstaller().installPlugin(manifest, listOf(component), InstallScope.Project(projectDir))
        assertTrue("install OK: ${installReport.failed}", installReport.isFullSuccess)
        val destDir = File(projectDir, ".claude/skills/$skillName")
        assertTrue("dest exists after install", destDir.exists())

        // Exercise the actual uninstallComponents code path — it's now `internal` for testability.
        val uninstallReport = uninstallComponents(manifest, listOf(component), InstallScope.Project(projectDir))
        assertTrue("uninstall OK: ${uninstallReport.failed}", uninstallReport.isFullSuccess)

        // The primary destination (and its entire directory tree) must be gone.
        val primaryDest = File(projectDir, ".claude/skills/$skillName")
        assertFalse("primary dest gone after uninstall", primaryDest.exists())

        // PluginInstallState.locationsOf must also report nothing — no badge should linger.
        val locs = PluginInstallState.locationsOf(component, manifest, projectDir.absolutePath)
        assertTrue("locationsOf reports nothing after uninstall, got: $locs", locs.isEmpty())
    }

    fun testUninstallRefusesSymlinkedIntermediateDir() {
        // Create a synthetic install at <project>/.claude/skills/foo where <project>/.claude
        // is itself a symlink pointing OUTSIDE the project. The deletion path must refuse.
        // Use real-FS temp directories so java.nio symlink APIs work (the IntelliJ VFS
        // temp fixture path is not a real FS path and cannot host symlinks).
        val base = java.nio.file.Files.createTempDirectory("agentry-test-symlink-intermediate").toFile()
        try {
            val projectDir = File(base, "project").apply { mkdirs() }
            val root = File(base, "plugin").apply { mkdirs() }
            val realPlace = File(base, "somewhere-else").apply { mkdirs() }
            // Make <project>/.claude a symlink pointing outside the project.
            val projectClaude = File(projectDir, ".claude")
            try {
                java.nio.file.Files.createSymbolicLink(projectClaude.toPath(), realPlace.toPath())
            } catch (_: Throwable) {
                return // FS doesn't allow symlinks; skip
            }
            // Plant a file at the install location through the symlink.
            val planted = File(realPlace, "skills/foo")
            planted.mkdirs()
            File(planted, "SKILL.md").writeText("planted")

            val component = PluginComponent.Skill("foo", planted, File(planted, "SKILL.md"), emptyList())
            val manifest = manifest(root, "symlink-intermediate")

            // Call the uninstall helper directly — it's `internal`.
            val report = uninstallComponents(manifest, listOf(component), InstallScope.Project(projectDir))
            assertTrue("refused: ${report.failed.firstOrNull()?.reason}", report.isFullFailure)
            // And the planted file must still exist (we refused to touch it).
            assertTrue("planted file still exists", File(planted, "SKILL.md").exists())
        } finally {
            base.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Regression: uninstall must not traverse symlinks planted INSIDE install dir
    // (Fixes 3 + 4 — shared deleteRecursivelySymlinkSafe helper)
    // -------------------------------------------------------------------------

    fun testUninstallDoesNotFollowSymlinkInsideInstallDir() {
        // Real-FS temp directories so java.nio symlink APIs work.
        val base = java.nio.file.Files.createTempDirectory("symlink-traverse-test").toFile()
        try {
            val projectDir = File(base, "proj").apply { mkdirs() }
            val root = File(base, "plugin").apply { mkdirs() }
            val outside = File(base, "outside-data").apply { mkdirs() }
            val outsideFile = File(outside, "important.txt").apply { writeText("DO NOT DELETE") }

            // Build the install dir and plant a real skill file.
            val installDir = File(projectDir, ".claude/skills/dangerous").apply { mkdirs() }
            File(installDir, "SKILL.md").writeText("real install file")

            // Plant a symlink INSIDE the install dir pointing to the outside dir.
            val planted = File(installDir, "exfil")
            try {
                java.nio.file.Files.createSymbolicLink(planted.toPath(), outside.toPath())
            } catch (_: Throwable) {
                return // FS doesn't allow symlinks; skip
            }

            val component = PluginComponent.Skill(
                name = "dangerous",
                sourceDir = installDir,
                skillFile = File(installDir, "SKILL.md"),
                supportFiles = emptyList()
            )
            val pluginManifest = manifest(root, "danger-test")

            // Uninstall through the ComponentActions pipeline — exercises the
            // deleteRecursivelySymlinkSafe helper introduced by Fix 3.
            val report = uninstallComponents(
                pluginManifest,
                listOf(component),
                InstallScope.Project(projectDir)
            )

            // EITHER the uninstall failed (refused to traverse symlink) OR it succeeded
            // but left the file OUTSIDE the install root untouched. The key invariant is
            // that the file outside the install dir survives.
            assertTrue("outside file must survive uninstall", outsideFile.exists())
            assertEquals("DO NOT DELETE", outsideFile.readText())
        } finally {
            base.deleteRecursively() // best-effort cleanup of test artifacts
        }
    }

    // -------------------------------------------------------------------------
    // Fix 2: canonical-root containment check on the INSTALL path
    // -------------------------------------------------------------------------

    fun testInstallRefusesSymlinkedIntermediateDir() {
        // Real-FS temp dir — the IntelliJ VFS fixture doesn't support real symlinks.
        val base = java.nio.file.Files.createTempDirectory("install-symlink-test").toFile()
        try {
            val projectDir = File(base, "proj").apply { mkdirs() }
            val root = File(base, "plugin").apply { mkdirs() }
            val realPlace = File(base, "elsewhere").apply { mkdirs() }
            val projectClaude = File(projectDir, ".claude")
            try {
                java.nio.file.Files.createSymbolicLink(projectClaude.toPath(), realPlace.toPath())
            } catch (_: Throwable) {
                return // FS doesn't support symlinks; skip
            }

            File(root, "skills/foo").mkdirs()
            File(root, "skills/foo/SKILL.md").writeText("---\nname: foo\n---\n")
            val component = PluginComponent.Skill(
                name = "foo",
                sourceDir = File(root, "skills/foo"),
                skillFile = File(root, "skills/foo/SKILL.md"),
                supportFiles = emptyList()
            )
            val pluginManifest = manifest(root, "symlink-install-test")

            // PluginInstaller.dispatch wraps with runCatching; SecurityException surfaces as
            // a failed component in the report.
            val report = PluginInstaller().installPlugin(
                pluginManifest,
                listOf(component),
                InstallScope.Project(projectDir)
            )
            assertTrue(
                "expected install to be refused for symlinked intermediate dir, got: ${report.failed}",
                report.isFullFailure
            )
            val failReason = report.failed.firstOrNull()?.reason ?: ""
            assertTrue(
                "expected 'escapes install root' in error, got: $failReason",
                failReason.contains("escapes install root")
            )
            // Nothing should have been written into realPlace.
            assertTrue("nothing written to symlink target", realPlace.listFiles().isNullOrEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Fix 3: rollback on partial dual-write failure
    // -------------------------------------------------------------------------

    fun testInstallRollsBackPartialDualWriteOnFailure() {
        // Override user.home to a temp dir so we control the Global install roots.
        // Then make ~/.claude/skills read-only so the second leg of a Global install fails.
        // On macOS, setReadOnly() does not always prevent the owner from writing; if the
        // read-only sentinel itself can still be written, we skip this test.
        val home = java.nio.file.Files.createTempDirectory("rollback-test").toFile()
        val originalUserHome = System.getProperty("user.home")
        val claudeSkillsDir = File(home, ".claude/skills").apply { mkdirs() }
        val copilotSkillsDir = File(home, ".copilot/skills")
        try {
            // Verify that setReadOnly actually prevents writes on this filesystem;
            // if not, skip the test (macOS owner bypass).
            claudeSkillsDir.setReadOnly()
            val probe = File(claudeSkillsDir, "probe.txt")
            val blocked = try { probe.createNewFile(); probe.delete(); false } catch (_: Throwable) { true }
            if (!blocked) {
                // setReadOnly doesn't block writes for the current user (common on macOS).
                // Rollback behaviour is verified by code inspection; skip the runtime test.
                return
            }

            System.setProperty("user.home", home.absolutePath)

            val root = File(home, "plugin-root").apply { mkdirs() }
            File(root, "skills/rollback-skill").mkdirs()
            File(root, "skills/rollback-skill/SKILL.md").writeText("---\nname: rollback-skill\n---\n")
            val component = PluginComponent.Skill(
                name = "rollback-skill",
                sourceDir = File(root, "skills/rollback-skill"),
                skillFile = File(root, "skills/rollback-skill/SKILL.md"),
                supportFiles = emptyList()
            )
            val pluginManifest = manifest(root, "rollback-plugin")

            val report = PluginInstaller().installPlugin(pluginManifest, listOf(component), InstallScope.Global)
            // The install must have failed (second destination couldn't be written).
            assertTrue("expected failure when second destination unwritable: ${report.installed}", report.isFullFailure)
            // The first destination (.copilot/skills/rollback-skill) should have been
            // rolled back and therefore must not exist.
            assertFalse(
                "copilot side must be rolled back after failure",
                File(copilotSkillsDir, "rollback-skill").exists()
            )
        } finally {
            claudeSkillsDir.setWritable(true) // allow cleanup
            home.deleteRecursively()
            originalUserHome?.let { System.setProperty("user.home", it) }
                ?: System.clearProperty("user.home")
        }
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
