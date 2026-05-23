package dev.agentry.jetbrains.install.installers

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.copySafe
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.util.InputValidation
import java.io.File
import java.nio.file.Files

/**
 * Installs a [PluginComponent.Skill] by recursively copying its source directory to the
 * target install path, with symlink-rejection on every entry (per the security review
 * from the merged UI PR).
 *
 * If the skill's `SKILL.md` is missing a `name` field, write one in based on the source
 * directory's basename so downstream agents that key on frontmatter work.
 */
internal class SkillBundleInstaller : ComponentInstaller<PluginComponent.Skill> {

    override fun install(
        component: PluginComponent.Skill,
        plugin: PluginManifest,
        scope: InstallScope
    ): File {
        require(InputValidation.isValidSkillName(component.name)) {
            "Invalid skill name: '${component.name}'"
        }
        val dest = InstallPaths.skillDir(component.name, scope)
        copySafe(component.sourceDir, dest)
        backfillNameInSkillMd(dest, component.name)
        return dest
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
