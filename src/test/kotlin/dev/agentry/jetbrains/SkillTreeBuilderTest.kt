package dev.agentry.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.PluginInstallState
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.InstalledSkill
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.ManifestDialect
import dev.agentry.jetbrains.model.PluginAuthor
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.ui.toolwindow.AgentryNode
import dev.agentry.jetbrains.ui.toolwindow.SkillTreeBuilder
import java.io.File

class SkillTreeBuilderTest : BasePlatformTestCase() {

    private var originalUserHome: String? = null

    override fun setUp() {
        super.setUp()
        // Override user.home so .copilot / .claude global writes land in the test fixture dir.
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

    fun testLocationsOfReturnsBothScopesWhenInstalledAtBoth() {
        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "proj").apply { mkdirs() }
        val pluginRoot = File(myFixture.tempDirFixture.tempDirPath, "plugin").apply { mkdirs() }
        val manifest = manifest(pluginRoot, "p")
        val component = PluginComponent.Skill(
            name = "double",
            sourceDir = File(pluginRoot, "skills/double"),
            skillFile = File(pluginRoot, "skills/double/SKILL.md"),
            supportFiles = emptyList()
        )

        // Write the primary destinations for both scopes directly so PluginInstallState sees them.
        // destFor(Skill) returns a directory — mkdirs() is the correct presence signal.
        InstallPaths.destFor(component, manifest, InstallScope.Project(projectDir)).mkdirs()
        InstallPaths.destFor(component, manifest, InstallScope.Global).mkdirs()

        val locs = PluginInstallState.locationsOf(component, manifest, projectDir.absolutePath)
        assertEquals(
            setOf<InstallScope>(InstallScope.Project(File(projectDir.absolutePath)), InstallScope.Global),
            locs
        )
    }

    fun testLocationsOfIgnoresSymlinkedDestination() {
        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "proj2").apply { mkdirs() }
        val pluginRoot = File(myFixture.tempDirFixture.tempDirPath, "plugin2").apply { mkdirs() }
        val manifest = manifest(pluginRoot, "p")
        val component = PluginComponent.Skill(
            name = "linkbomb",
            sourceDir = File(pluginRoot, "skills/linkbomb"),
            skillFile = File(pluginRoot, "skills/linkbomb/SKILL.md"),
            supportFiles = emptyList()
        )
        val target = InstallPaths.destFor(component, manifest, InstallScope.Project(projectDir))
        target.parentFile.mkdirs()
        val elsewhere = File(myFixture.tempDirFixture.tempDirPath, "elsewhere").apply { mkdirs() }
        try {
            java.nio.file.Files.createSymbolicLink(target.toPath(), elsewhere.toPath())
        } catch (_: Throwable) {
            return // FS doesn't allow symlinks; skip
        }
        val locs = PluginInstallState.locationsOf(component, manifest, projectDir.absolutePath)
        assertTrue("symlink must not count: $locs", locs.isEmpty())
    }

    // --- Regression: Issue 1 — pickedScope() null-basePath safety ---

    /**
     * When the picker is CLAUDE_PROJECT but the project has no base path, the resolution
     * must fall back to Global rather than constructing InstallScope.Project(File("")).
     * The [AgentryToolWindowPanel.pickedScope] logic is extracted here for unit testing.
     */
    fun testPickedScopeFallsBackToGlobalWhenBasePathNullAndPickerIsProject() {
        val basePath: String? = null
        val target = InstallTarget.CLAUDE_PROJECT
        val resolved = resolvePickedScope(target, basePath)
        assertEquals(
            "CLAUDE_PROJECT + null basePath must resolve to Global, not Project(File(\"\"))",
            InstallScope.Global,
            resolved
        )
    }

    fun testPickedScopeFallsBackToGlobalWhenBasePathBlankAndPickerIsProject() {
        val basePath = "   "
        val target = InstallTarget.CLAUDE_PROJECT
        val resolved = resolvePickedScope(target, basePath)
        assertEquals(
            "CLAUDE_PROJECT + blank basePath must resolve to Global",
            InstallScope.Global,
            resolved
        )
    }

    fun testPickedScopeReturnsProjectWhenBasePathPresentAndPickerIsProject() {
        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "proj-scope").apply { mkdirs() }
        val basePath = projectDir.absolutePath
        val resolved = resolvePickedScope(InstallTarget.CLAUDE_PROJECT, basePath)
        assertEquals(
            "CLAUDE_PROJECT + valid basePath must resolve to Project",
            InstallScope.Project(File(basePath)),
            resolved
        )
    }

    fun testPickedScopeAlwaysReturnsGlobalForClaudeUser() {
        assertEquals(InstallScope.Global, resolvePickedScope(InstallTarget.CLAUDE_USER, null))
        assertEquals(InstallScope.Global, resolvePickedScope(InstallTarget.CLAUDE_USER, "/some/path"))
    }

    // --- Regression: Issue 2+3 — legacy multi-scope tracking ---

    /**
     * Install a legacy skill at BOTH CLAUDE_USER and CLAUDE_PROJECT, then verify that the
     * cross-scope enumeration (the logic inside SkillTreeBuilder.legacyScopesInstalled)
     * returns a set containing BOTH InstallScopes for that skill.
     *
     * legacyScopesInstalled is private, so we replicate its query logic here and test
     * through the public SkillInstaller.listInstalled API — this is the higher-value
     * coverage because it exercises the actual data the builder depends on.
     */
    fun testLegacyScopesInstalledReturnsBothScopesAfterDualInstall() {
        val registryUrl = "https://example.com/dual-scope-registry.git"
        val skillName = "dual-scope-skill"
        val cacheDir = RegistryManager.getInstance().localDirFor(RegistrySource(url = registryUrl))
        val skillSrc = File(cacheDir, skillName).apply { mkdirs() }
        File(skillSrc, "skill.json").writeText(
            """{"name":"$skillName","version":"1.0.0","displayName":"Dual Scope"}"""
        )
        File(skillSrc, "prompt.md").writeText("# Dual Scope\nbody")

        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "proj-dual").apply { mkdirs() }
        val projectBasePath = projectDir.absolutePath
        val manifest = SkillManifest(
            name = skillName,
            version = "1.0.0",
            displayName = "Dual Scope",
            sourceRegistry = registryUrl
        )
        val installer = SkillInstaller.getInstance()

        try {
            // Install at both scopes.
            val r1 = installer.install(manifest, InstallTarget.CLAUDE_USER, projectBasePath)
            assertTrue("install at CLAUDE_USER failed: ${r1.exceptionOrNull()?.message}", r1.isSuccess)
            val r2 = installer.install(manifest, InstallTarget.CLAUDE_PROJECT, projectBasePath)
            assertTrue("install at CLAUDE_PROJECT failed: ${r2.exceptionOrNull()?.message}", r2.isSuccess)

            // Replicate legacyScopesInstalled() logic inline.
            val byName = mutableMapOf<String, MutableSet<InstallScope>>()
            installer.listInstalled(InstallTarget.CLAUDE_USER, projectBasePath).forEach {
                byName.getOrPut(it.manifest.name) { mutableSetOf() }.add(InstallScope.Global)
            }
            installer.listInstalled(InstallTarget.CLAUDE_PROJECT, projectBasePath).forEach {
                byName.getOrPut(it.manifest.name) { mutableSetOf() }
                    .add(InstallScope.Project(File(projectBasePath)))
            }

            val scopes = byName[skillName]
            assertNotNull("skill must appear in cross-scope map", scopes)
            assertTrue(
                "expected Global in scopes, got $scopes",
                InstallScope.Global in scopes!!
            )
            assertTrue(
                "expected Project in scopes, got $scopes",
                InstallScope.Project(File(projectBasePath)) in scopes
            )
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    /**
     * When a skill is only installed at CLAUDE_USER, the cross-scope enumeration must NOT
     * include a Project scope entry.
     */
    fun testLegacyScopesInstalledReturnsOnlyGlobalWhenOnlyInstalledAtUser() {
        val registryUrl = "https://example.com/user-only-registry.git"
        val skillName = "user-only-skill"
        val cacheDir = RegistryManager.getInstance().localDirFor(RegistrySource(url = registryUrl))
        val skillSrc = File(cacheDir, skillName).apply { mkdirs() }
        File(skillSrc, "skill.json").writeText(
            """{"name":"$skillName","version":"1.0.0","displayName":"User Only"}"""
        )
        File(skillSrc, "prompt.md").writeText("# User Only\nbody")

        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "proj-user-only").apply { mkdirs() }
        val projectBasePath = projectDir.absolutePath
        val manifest = SkillManifest(
            name = skillName,
            version = "1.0.0",
            displayName = "User Only",
            sourceRegistry = registryUrl
        )
        val installer = SkillInstaller.getInstance()

        try {
            val r = installer.install(manifest, InstallTarget.CLAUDE_USER, projectBasePath)
            assertTrue("install failed: ${r.exceptionOrNull()?.message}", r.isSuccess)

            val byName = mutableMapOf<String, MutableSet<InstallScope>>()
            installer.listInstalled(InstallTarget.CLAUDE_USER, projectBasePath).forEach {
                byName.getOrPut(it.manifest.name) { mutableSetOf() }.add(InstallScope.Global)
            }
            installer.listInstalled(InstallTarget.CLAUDE_PROJECT, projectBasePath).forEach {
                byName.getOrPut(it.manifest.name) { mutableSetOf() }
                    .add(InstallScope.Project(File(projectBasePath)))
            }

            val scopes = byName[skillName]
            assertNotNull("skill must appear in map after user install", scopes)
            assertTrue("Global must be present", InstallScope.Global in scopes!!)
            assertFalse(
                "Project must NOT be present when only installed at user scope",
                InstallScope.Project(File(projectBasePath)) in scopes
            )
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    // --- Regression: Fix 1 — orphan scope from target field, not path-prefix ---

    /**
     * A CLAUDE_USER install whose on-disk location happens to sit *inside* the project root
     * (e.g. the user opened their home dir as a project, so ~/.claude/skills/X starts with
     * projectBasePath) must still be classified as Global, not Project.
     *
     * The old path-prefix heuristic got this wrong. The fix uses orphan.target directly.
     */
    fun testOrphanScopeDerivedFromTargetNotPathPrefix() {
        // Make project root == the temp dir so that the installed skill's location
        // is guaranteed to start with projectBasePath — the worst case for the old heuristic.
        val projectDir = File(myFixture.tempDirFixture.tempDirPath)
        val skillManifest = SkillManifest(name = "ambig", version = "1.0.0", displayName = "Ambig")
        // Location inside projectDir but the target says CLAUDE_USER (Global).
        val orphanLocation = File(projectDir, ".claude/skills/ambig")
        val installed = InstalledSkill(
            manifest = skillManifest,
            location = orphanLocation,
            target = InstallTarget.CLAUDE_USER
        )

        val root = AgentryNode.Root()
        SkillTreeBuilder.addOrphans(root, listOf(installed), emptyList(), projectDir.absolutePath)

        // Extract the single orphan node from the OrphanGroup.
        val orphanGroup = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filterIsInstance<AgentryNode.OrphanGroup>()
            .firstOrNull()
        assertNotNull("OrphanGroup must be added to root", orphanGroup)

        val orphanNode = (0 until orphanGroup!!.childCount)
            .map { orphanGroup.getChildAt(it) }
            .filterIsInstance<AgentryNode.Orphan>()
            .firstOrNull { it.installed.manifest.name == "ambig" }
        assertNotNull("Orphan node for 'ambig' must exist", orphanNode)

        assertEquals(
            "CLAUDE_USER orphan inside project dir must be classified as Global, not Project",
            setOf<InstallScope>(InstallScope.Global),
            orphanNode!!.installedScopes
        )
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

/**
 * Mirrors the pickedScope() logic in AgentryToolWindowPanel for unit testing.
 * This is a top-level function (not inside the test class) so it can also be used
 * as standalone documentation of the contract.
 */
private fun resolvePickedScope(target: InstallTarget, basePath: String?): InstallScope {
    val nonBlankBase = basePath?.takeIf { it.isNotBlank() }
    return when (target) {
        InstallTarget.CLAUDE_USER -> InstallScope.Global
        InstallTarget.CLAUDE_PROJECT ->
            if (nonBlankBase != null) InstallScope.Project(File(nonBlankBase))
            else InstallScope.Global
    }
}
