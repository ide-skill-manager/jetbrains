package dev.agentry.jetbrains.model

import dev.agentry.jetbrains.util.AgentryPaths
import dev.agentry.jetbrains.util.InputValidation
import java.io.File

/**
 * Where a skill is written on disk for a given coding agent.
 */
enum class InstallTarget(val displayName: String) {
    CLAUDE_PROJECT(".claude/skills/ (project)"),
    CLAUDE_USER("~/.claude/skills/ (user)"),
    JUNIE_PROJECT(".junie/skills/ (project)"),
    AGENTRY_CACHE("~/.agentry/skills/ (neutral)");

    /** Root directory containing all skills installed at this target. */
    fun baseDir(projectBasePath: String?): File? = when (this) {
        CLAUDE_PROJECT -> projectBasePath?.let { File(it, ".claude/skills") }
        CLAUDE_USER -> File(System.getProperty("user.home"), ".claude/skills")
        JUNIE_PROJECT -> projectBasePath?.let { File(it, ".junie/skills") }
        AGENTRY_CACHE -> AgentryPaths.agentrySkillsRoot
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
