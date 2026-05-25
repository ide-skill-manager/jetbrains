package dev.agentry.jetbrains.install.installers

import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.copySafe
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.util.InputValidation
import java.io.File
import java.nio.file.Files

/**
 * Installs a [PluginComponent.Skill] by recursively copying its source directory to the
 * target install path(s), with symlink-rejection on every entry (per the security review
 * from the merged UI PR).
 *
 * At Global scope, dual-writes to both `~/.copilot/skills/<name>/` and
 * `~/.claude/skills/<name>/` so Claude Code can find the skill — mirroring the agent
 * dual-write introduced in 3d259c6. At Project scope, a single `.claude/skills/<name>/`
 * copy is sufficient because every tool reads it from there.
 *
 * If the skill's `SKILL.md` is missing a `name` field, write one in based on the source
 * directory's basename so downstream agents that key on frontmatter work.
 */
internal class SkillBundleInstaller : ComponentInstaller<PluginComponent.Skill> {

    private val log = logger<SkillBundleInstaller>()

    override fun install(
        component: PluginComponent.Skill,
        plugin: PluginManifest,
        scope: InstallScope
    ): File {
        require(InputValidation.isValidSkillName(component.name)) {
            "Invalid skill name: '${component.name}'"
        }
        val destinations = InstallPaths.destinationsFor(component, plugin, scope)
        val roots = InstallPaths.skillInstallRoots(scope)
        check(roots.size == destinations.size) {
            "skill destinations/roots size mismatch: ${destinations.size} vs ${roots.size}"
        }
        // Defence-in-depth: each destination must be inside its corresponding root so a
        // traversal in the skill name can't escape to an arbitrary directory.
        roots.zip(destinations).forEach { (root, dest) ->
            require(InputValidation.isInsideDir(dest, root)) {
                "Resolved skill dest escapes install root: $dest (root=$root)"
            }
        }
        destinations.forEach { dest ->
            copySafe(component.sourceDir, dest)
            backfillNameInSkillMd(dest, component.name)
        }
        log.info("Installed skill '${component.name}' to ${destinations.size} location(s)")
        // Return the primary (first) destination — the report cites one canonical path.
        return destinations.first()
    }

    /**
     * If the installed `SKILL.md` has no `name:` frontmatter line, prepend one so the
     * agent that consumes the skill can identify it. Idempotent — if a name is already
     * present we leave the file alone.
     */
    private fun backfillNameInSkillMd(installRoot: File, skillName: String) {
        val skillMd = File(installRoot, "SKILL.md")
        if (!skillMd.isFile) return
        val original = skillMd.readText()
        if (original.contains(Regex("^name\\s*:", RegexOption.MULTILINE))) return
        val withName = if (original.trimStart().startsWith("---")) {
            // Insert the name line after the opening fence.
            original.replaceFirst("---", "---\nname: $skillName")
        } else {
            "---\nname: $skillName\n---\n$original"
        }
        Files.writeString(skillMd.toPath(), withName)
    }
}
