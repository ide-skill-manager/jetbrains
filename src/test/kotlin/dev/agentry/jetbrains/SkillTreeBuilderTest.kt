package dev.agentry.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.PluginInstallState
import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.ManifestDialect
import dev.agentry.jetbrains.model.PluginAuthor
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
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
