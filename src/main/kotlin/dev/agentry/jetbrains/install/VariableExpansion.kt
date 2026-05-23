package dev.agentry.jetbrains.install

import java.io.File

/**
 * Install-time expansion of the three `${CLAUDE_PLUGIN_*}` variables the
 * Microsoft / Claude Code agent-plugins spec defines.
 *
 * Spec policy (also documented in `docs/plans/microsoft-agent-plugins-spec.md`):
 *
 * | Variable                 | Behaviour                                                                              |
 * |--------------------------|----------------------------------------------------------------------------------------|
 * | `${CLAUDE_PLUGIN_ROOT}`  | Rewrite at install time to the installed bundle's absolute path.                       |
 * | `${CLAUDE_PLUGIN_DATA}`  | Rewrite at install time to `~/.agentry/plugin-data/<pluginId>/` (created lazily).      |
 * | `${CLAUDE_PROJECT_DIR}`  | Leave as a literal — JetBrains Copilot's runtime expands it. Substitute only when the  |
 * |                          | host runtime is known not to.                                                          |
 *
 * The [ExpansionEnv] captures the three policy decisions as data: each variable is either
 * [Substitute] (substring-replaced now) or [Literal] (left in the output as-is). This keeps
 * the policy data-driven and easy to flip in tests without touching the engine.
 */
object VariableExpansion {

    fun expand(text: String, env: ExpansionEnv): String {
        var out = text
        out = apply(out, "\${CLAUDE_PLUGIN_ROOT}", env.pluginRoot)
        out = apply(out, "\${CLAUDE_PLUGIN_DATA}", env.pluginData)
        out = apply(out, "\${CLAUDE_PROJECT_DIR}", env.projectDir)
        return out
    }

    private fun apply(text: String, marker: String, value: VariableValue): String =
        when (value) {
            Literal -> text
            is Substitute -> text.replace(marker, value.value)
        }
}

/**
 * Per-variable policy. Defaults match the spec's recommended JetBrains behaviour:
 * `pluginRoot` and `pluginData` are substituted at install time; `projectDir` is left
 * literal so the IDE runtime can expand it at run time.
 */
data class ExpansionEnv(
    val pluginRoot: VariableValue,
    val pluginData: VariableValue,
    val projectDir: VariableValue
) {
    companion object {
        /** Convenience factory matching the JetBrains-Copilot policy described above. */
        fun forJetBrainsInstall(pluginRoot: File, pluginData: File): ExpansionEnv =
            ExpansionEnv(
                pluginRoot = Substitute(pluginRoot.canonicalPath),
                pluginData = Substitute(pluginData.canonicalPath),
                projectDir = Literal
            )
    }
}

sealed interface VariableValue
data class Substitute(val value: String) : VariableValue
object Literal : VariableValue
