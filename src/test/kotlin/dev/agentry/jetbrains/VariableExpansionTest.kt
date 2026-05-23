package dev.agentry.jetbrains

import dev.agentry.jetbrains.install.ExpansionEnv
import dev.agentry.jetbrains.install.Literal
import dev.agentry.jetbrains.install.Substitute
import dev.agentry.jetbrains.install.VariableExpansion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the [VariableExpansion] policy table from the spec:
 *
 *   - `${CLAUDE_PLUGIN_ROOT}`  → substituted with the install bundle path
 *   - `${CLAUDE_PLUGIN_DATA}`  → substituted with `~/.agentry/plugin-data/<id>/`
 *   - `${CLAUDE_PROJECT_DIR}`  → left as a literal for JetBrains' runtime to expand
 */
class VariableExpansionTest {

    private val env = ExpansionEnv(
        pluginRoot = Substitute("/Users/andy/.agentry/installed/my-plugin"),
        pluginData = Substitute("/Users/andy/.agentry/plugin-data/my-plugin"),
        projectDir = Literal
    )

    @Test fun `substitutes plugin root variable`() {
        val out = VariableExpansion.expand(
            """{"command": "${'$'}{CLAUDE_PLUGIN_ROOT}/scripts/run.sh"}""",
            env
        )
        assertEquals(
            """{"command": "/Users/andy/.agentry/installed/my-plugin/scripts/run.sh"}""",
            out
        )
    }

    @Test fun `substitutes plugin data variable`() {
        val out = VariableExpansion.expand("data=${'$'}{CLAUDE_PLUGIN_DATA}/cache.db", env)
        assertEquals("data=/Users/andy/.agentry/plugin-data/my-plugin/cache.db", out)
    }

    @Test fun `leaves project dir as literal under default policy`() {
        val raw = "cwd=${'$'}{CLAUDE_PROJECT_DIR}/sub"
        assertEquals(raw, VariableExpansion.expand(raw, env))
    }

    @Test fun `quoted variable in JSON round-trips through expansion`() {
        // Hook commands often quote the variable so the shell respects spaces.
        val raw = """"${'$'}{CLAUDE_PLUGIN_ROOT}"/scripts/x.sh"""
        val expected = "\"/Users/andy/.agentry/installed/my-plugin\"/scripts/x.sh"
        assertEquals(expected, VariableExpansion.expand(raw, env))
    }

    @Test fun `multiple occurrences all substituted`() {
        val raw = "${'$'}{CLAUDE_PLUGIN_ROOT}/a:${'$'}{CLAUDE_PLUGIN_ROOT}/b"
        val out = VariableExpansion.expand(raw, env)
        assertEquals(
            "/Users/andy/.agentry/installed/my-plugin/a:/Users/andy/.agentry/installed/my-plugin/b",
            out
        )
    }

    @Test fun `every value can be flipped to Literal`() {
        val allLiteral = ExpansionEnv(Literal, Literal, Literal)
        val raw = "${'$'}{CLAUDE_PLUGIN_ROOT}/${'$'}{CLAUDE_PLUGIN_DATA}/${'$'}{CLAUDE_PROJECT_DIR}"
        assertEquals(raw, VariableExpansion.expand(raw, allLiteral))
    }

    @Test fun `unrelated content is preserved untouched`() {
        val raw = "echo 'hello world' && exit 0"
        assertEquals(raw, VariableExpansion.expand(raw, env))
    }
}
