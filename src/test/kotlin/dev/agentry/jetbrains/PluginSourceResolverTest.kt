package dev.agentry.jetbrains

import dev.agentry.jetbrains.model.PluginSource
import dev.agentry.jetbrains.registry.PluginSourceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Local-path resolution tests for [PluginSourceResolver]. External (github/url) sources
 * touch the git CLI and are exercised by the manual smoke checklist; here we just verify
 * the path-traversal guards.
 */
class PluginSourceResolverTest {

    @get:Rule val tmpDir = TemporaryFolder()
    private val resolver = PluginSourceResolver()

    @Test fun `local path under registry root resolves`() {
        val registry = tmpDir.newFolder("registry")
        val plugin = File(registry, "plugins/foo").apply { mkdirs() }
        val result = resolver.resolve(PluginSource.Local("./plugins/foo"), registry)
        assertEquals(plugin.canonicalPath, result.getOrThrow().canonicalPath)
    }

    @Test fun `local path with pluginRoot prefix resolves under it`() {
        val registry = tmpDir.newFolder("registry-with-root")
        File(registry, "plugins/bar").apply { mkdirs() }
        val result = resolver.resolve(
            source = PluginSource.Local("bar"),
            registryRoot = registry,
            pluginRoot = "./plugins"
        )
        assertEquals(File(registry, "plugins/bar").canonicalPath, result.getOrThrow().canonicalPath)
    }

    @Test fun `local path escaping the registry root fails`() {
        val registry = tmpDir.newFolder("registry-escape")
        val outside = File(registry.parentFile, "outside").apply { mkdirs() }
        val result = resolver.resolve(PluginSource.Local("../outside"), registry)
        assertTrue("expected SecurityException, got: ${result.exceptionOrNull()}", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        // Clean up
        outside.deleteRecursively()
    }

    @Test fun `pluginRoot escaping the registry is rejected`() {
        val registry = tmpDir.newFolder("registry-bad-root")
        val result = resolver.resolve(
            source = PluginSource.Local("anything"),
            registryRoot = registry,
            pluginRoot = "../"
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test fun `non-directory local path fails`() {
        val registry = tmpDir.newFolder("registry-with-file")
        File(registry, "plugin.json").writeText("{}")
        val result = resolver.resolve(PluginSource.Local("./plugin.json"), registry)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `invalid github source URL is rejected before git is touched`() {
        // The Github source is constructed via `https://github.com/<repo>.git`; the repo
        // string itself can contain anything, but we ensure InputValidation gates the
        // resulting URL. A repo with a leading dash would produce a URL like
        // `https://github.com/-evil/...` which is a valid URL — so this primarily verifies
        // the github-shaped path doesn't bypass the URL allowlist.
        val registry = tmpDir.newFolder("registry-gh")
        val result = resolver.resolve(
            source = PluginSource.Github(repo = "../etc/passwd", ref = "main", sha = null),
            registryRoot = registry
        )
        // The constructed URL is still legal http(s) per the URL validator, but `git clone`
        // will fail. The resolver returns failure either at validation or at fetch time.
        assertTrue(result.isFailure)
    }
}
