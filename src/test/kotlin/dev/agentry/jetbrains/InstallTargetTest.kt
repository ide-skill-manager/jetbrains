package dev.agentry.jetbrains

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.model.InstallTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstallTargetTest {

    @Test fun `enum holds only CLAUDE_USER and CLAUDE_PROJECT`() {
        val names = enumValues<InstallTarget>().map { it.name }.toSet()
        assertEquals(setOf("CLAUDE_USER", "CLAUDE_PROJECT"), names)
    }

    @Test fun `toScope CLAUDE_USER maps to Global regardless of project path`() {
        assertEquals(InstallScope.Global, InstallTarget.CLAUDE_USER.toScope(null))
        assertEquals(InstallScope.Global, InstallTarget.CLAUDE_USER.toScope("/tmp/proj"))
    }

    @Test fun `toScope CLAUDE_PROJECT maps to Project with the given path`() {
        val scope = InstallTarget.CLAUDE_PROJECT.toScope("/tmp/proj")
        assertTrue(scope is InstallScope.Project)
        assertEquals(File("/tmp/proj"), (scope as InstallScope.Project).projectDir)
    }

    @Test(expected = IllegalStateException::class)
    fun `toScope CLAUDE_PROJECT with null path errors`() {
        InstallTarget.CLAUDE_PROJECT.toScope(null)
    }
}
