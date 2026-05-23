package dev.agentry.jetbrains.model

/**
 * Describes where a skill should be installed on disk.
 */
enum class InstallTarget(val displayName: String, val description: String) {
    CLAUDE_PROJECT(".claude/skills/ (project)", "Claude Code project-level skills"),
    CLAUDE_USER("~/.claude/skills/ (user)", "Claude Code user-level skills"),
    JUNIE_PROJECT(".junie/skills/ (project)", "Junie project-level skills"),
    AGENTRY_CACHE("~/.agentry/ (cache)", "Neutral cache for all agents");

    fun resolvePath(projectBasePath: String?, skillName: String): java.io.File {
        val home = System.getProperty("user.home")
        return when (this) {
            CLAUDE_PROJECT -> java.io.File("$projectBasePath/.claude/skills/$skillName")
            CLAUDE_USER -> java.io.File("$home/.claude/skills/$skillName")
            JUNIE_PROJECT -> java.io.File("$projectBasePath/.junie/skills/$skillName")
            AGENTRY_CACHE -> java.io.File("$home/.agentry/skills/$skillName")
        }
    }
}
