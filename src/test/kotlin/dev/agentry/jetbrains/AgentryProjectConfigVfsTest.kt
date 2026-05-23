package dev.agentry.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.config.AgentryProjectConfig
import dev.agentry.jetbrains.model.InstallTarget
import java.io.File

/**
 * Exercises `.agentry/config.yaml` parsing against a real on-disk file inside the test
 * fixture's temp directory — same code path the project-open activity hits.
 */
class AgentryProjectConfigVfsTest : BasePlatformTestCase() {

    fun testParsesConfigWithSourcesAndSkills() {
        val projectDir = createTempProjectDir()
        File(projectDir, ".agentry").mkdirs()
        File(projectDir, ".agentry/config.yaml").writeText(
            """
            version: "1"
            defaultTarget: CLAUDE_USER
            sources:
              - name: company-skills
                url: https://github.com/acme/agent-skills.git
                ref: main
              - name: my-wip
                url: https://github.com/me/skills.git
                ref: feature/wip
            skills:
              - name: code-reviewer
                target: CLAUDE_PROJECT
              - name: pr-summarizer
                target: CLAUDE_USER
            """.trimIndent()
        )

        val config = AgentryProjectConfig.loadFrom(projectDir.absolutePath)
        assertNotNull(config)
        config!!
        assertEquals(2, config.sources.size)
        assertEquals("feature/wip", config.sources[1].ref)
        assertEquals(2, config.skills.size)
        assertEquals(InstallTarget.CLAUDE_USER.name, config.defaultTarget)

        val sources = config.toRegistrySources()
        assertEquals(2, sources.size)
        // Branch names with slashes survive validation (WIP workflow).
        assertEquals("feature/wip", sources[1].ref)
    }

    fun testRejectsConfigSourcesWithEvilUrl() {
        val projectDir = createTempProjectDir()
        File(projectDir, ".agentry").mkdirs()
        File(projectDir, ".agentry/config.yaml").writeText(
            """
            version: "1"
            sources:
              - name: evil
                url: "ext::sh -c id"
                ref: main
              - name: good
                url: https://github.com/legit/repo.git
                ref: main
            """.trimIndent()
        )
        val config = AgentryProjectConfig.loadFrom(projectDir.absolutePath)
        assertNotNull(config)
        val sources = config!!.toRegistrySources()
        // Only the good source survives validation.
        assertEquals(1, sources.size)
        assertEquals("https://github.com/legit/repo.git", sources[0].url)
    }

    fun testReturnsNullWhenFileMissing() {
        val projectDir = createTempProjectDir()
        assertNull(AgentryProjectConfig.loadFrom(projectDir.absolutePath))
    }

    fun testReturnsNullOnMalformedYaml() {
        val projectDir = createTempProjectDir()
        File(projectDir, ".agentry").mkdirs()
        File(projectDir, ".agentry/config.yaml").writeText("this: is: : not valid")
        assertNull(AgentryProjectConfig.loadFrom(projectDir.absolutePath))
    }

    private fun createTempProjectDir(): File {
        val dir = File(myFixture.tempDirFixture.tempDirPath, "project-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
