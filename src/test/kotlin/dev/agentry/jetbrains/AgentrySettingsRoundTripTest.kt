package dev.agentry.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.settings.AgentrySettings

/**
 * Round-trips the `PersistentStateComponent` state through `getState()` / `loadState()` to
 * catch silent breakage when fields are renamed or removed. Runs inside a headless IDE so
 * the real service container is exercised, not a mock.
 */
class AgentrySettingsRoundTripTest : BasePlatformTestCase() {

    fun testRoundTripPreservesRegistrySources() {
        val settings = AgentrySettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(
                AgentrySettings.State(
                    registrySources = mutableListOf(
                        AgentrySettings.RegistrySourceState(
                            url = "https://github.com/example/skills.git",
                            ref = "feature/wip",
                            enabled = true,
                            displayName = "example"
                        ),
                        AgentrySettings.RegistrySourceState(
                            url = "https://github.com/other/skills.git",
                            ref = "main",
                            enabled = false,
                            displayName = "other"
                        )
                    ),
                    defaultInstallTarget = InstallTarget.CLAUDE_USER.name,
                    autoSyncOnOpen = true,
                    trustedProjects = mutableSetOf("/tmp/project-a", "/tmp/project-b")
                )
            )

            // Read back via getState (what the platform calls when persisting).
            val persisted = settings.state
            assertEquals(2, persisted.registrySources.size)
            assertEquals("feature/wip", persisted.registrySources[0].ref)
            assertEquals(false, persisted.registrySources[1].enabled)
            assertEquals(InstallTarget.CLAUDE_USER.name, persisted.defaultInstallTarget)
            assertTrue(persisted.autoSyncOnOpen)
            assertTrue("/tmp/project-a" in persisted.trustedProjects)

            // And the typed accessors work.
            assertEquals(InstallTarget.CLAUDE_USER, settings.defaultInstallTarget)
            assertTrue(settings.isTrusted("/tmp/project-a"))
            assertFalse(settings.isTrusted("/tmp/never-trusted"))
        } finally {
            settings.loadState(before)
        }
    }

    fun testTrustProjectAddsToSet() {
        val settings = AgentrySettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(AgentrySettings.State())
            assertFalse(settings.isTrusted("/tmp/x"))
            settings.trustProject("/tmp/x")
            assertTrue(settings.isTrusted("/tmp/x"))
        } finally {
            settings.loadState(before)
        }
    }

    fun testDefaultInstallTargetFallsBackOnUnknownString() {
        val settings = AgentrySettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(
                AgentrySettings.State(defaultInstallTarget = "WAS_REMOVED_IN_NEXT_VERSION")
            )
            // Unknown enum value must not throw — fall back to CLAUDE_PROJECT.
            assertEquals(InstallTarget.CLAUDE_PROJECT, settings.defaultInstallTarget)
        } finally {
            settings.loadState(before)
        }
    }
}
