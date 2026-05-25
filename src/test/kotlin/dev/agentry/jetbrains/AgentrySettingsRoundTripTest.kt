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

    fun testDefaultInstallTargetGetterFallsBackForUnknownState() {
        // Covers the getter's `?: CLAUDE_USER` Elvis-fallback path specifically.
        // We bypass loadState (which coerces unknown strings) by writing directly to the
        // raw state field via getState(), so only the getter's fallback is exercised —
        // not the loadState coercion already covered by testLoadStateCoercesUnknownTargetToClaudeUser.
        val settings = AgentrySettings.getInstance()
        val before = settings.state
        try {
            settings.getState().defaultInstallTarget = "WAS_REMOVED_IN_NEXT_VERSION"
            // Unknown enum value must not throw — fall back to CLAUDE_USER.
            assertEquals(InstallTarget.CLAUDE_USER, settings.defaultInstallTarget)
        } finally {
            settings.loadState(before)
        }
    }

    fun testDefaultInstallTargetIsClaudeUser() {
        val freshState = AgentrySettings.State()
        assertEquals("CLAUDE_USER", freshState.defaultInstallTarget)
    }

    fun testLoadStateCoercesUnknownTargetToClaudeUser() {
        // Covers the persistence-boundary coercion in loadState — verifies that the
        // raw state field itself is normalised (not just the typed accessor).
        val settings = AgentrySettings.getInstance()
        val before = settings.state
        try {
            val stale = AgentrySettings.State().apply { defaultInstallTarget = "AGENTRY_CACHE" }
            settings.loadState(stale)
            assertEquals("CLAUDE_USER", settings.state.defaultInstallTarget)
            //                          ^^^^^^^^^^^^^^^ raw field, not typed accessor
        } finally {
            settings.loadState(before)
        }
    }

    fun testLoadStateKeepsKnownTarget() {
        val settings = AgentrySettings.getInstance()
        val before = settings.state
        try {
            val state = AgentrySettings.State().apply {
                defaultInstallTarget = InstallTarget.CLAUDE_PROJECT.name
            }
            settings.loadState(state)
            assertEquals(InstallTarget.CLAUDE_PROJECT.name, settings.state.defaultInstallTarget)
        } finally {
            settings.loadState(before)
        }
    }
}
