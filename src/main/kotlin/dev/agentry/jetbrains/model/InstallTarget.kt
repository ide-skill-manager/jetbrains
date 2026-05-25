package dev.agentry.jetbrains.model

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.util.InputValidation
import java.io.File

/**
 * Where a skill / component is written on disk. Two scopes:
 *
 *   - [CLAUDE_USER]    `~/.claude/skills/<name>/`         (default)
 *   - [CLAUDE_PROJECT] `<project>/.claude/skills/<name>/`
 *
 * The newer plugin-component pipeline routes via [toScope], which maps the enum onto the
 * sealed [InstallScope] used by `InstallPaths.destinationsFor`. The legacy single-skill
 * pipeline (`SkillInstaller`) consumes [baseDir] / [resolvePath] directly.
 */
enum class InstallTarget(val displayName: String) {
    CLAUDE_USER("~/.claude/ (user)"),
    CLAUDE_PROJECT("<project>/.claude/ (project)");

    /** Bridge to the sealed [InstallScope] used by the plugin-component install pipeline. */
    fun toScope(projectBasePath: String?): InstallScope = when (this) {
        CLAUDE_USER -> InstallScope.Global
        CLAUDE_PROJECT -> InstallScope.Project(
            File(projectBasePath ?: error("CLAUDE_PROJECT requires a project base path"))
        )
    }

    /** Root directory containing all skills installed at this target. */
    fun baseDir(projectBasePath: String?): File? = when (this) {
        CLAUDE_USER -> File(System.getProperty("user.home"), ".claude/skills")
        CLAUDE_PROJECT -> projectBasePath?.let { File(it, ".claude/skills") }
    }

    /**
     * Resolve the install directory for [skillName]. Validates the name and verifies the
     * resolved path stays inside [baseDir] — guards against `name = "../../etc/passwd"`.
     */
    fun resolvePath(projectBasePath: String?, skillName: String): File {
        require(InputValidation.isValidSkillName(skillName)) {
            "Invalid skill name: '$skillName'"
        }
        val base = baseDir(projectBasePath)
            ?: error("$name requires a project base path")
        val dest = File(base, skillName)
        require(InputValidation.isInsideDir(dest, base)) {
            "Resolved path '${dest.absolutePath}' escapes install root '${base.absolutePath}'"
        }
        return dest
    }
}
