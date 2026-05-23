package dev.agentry.jetbrains.install

import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.ManifestParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Handles installing, updating, and removing skills to/from the correct
 * directories based on the target agent.
 */
class SkillInstaller {

    private val log = logger<SkillInstaller>()

    /**
     * Install a skill from a registry source to the specified target.
     * @param manifest the skill to install
     * @param target where to install (Claude project, Claude user, Junie, cache)
     * @param projectBasePath absolute path of the open project (required for project-level targets)
     */
    fun install(
        manifest: SkillManifest,
        target: InstallTarget,
        projectBasePath: String? = null
    ): Result<File> {
        val dest = target.resolvePath(projectBasePath, manifest.name)
        return runCatching {
            val sourceDir = resolveSourceDir(manifest)
                ?: throw IllegalStateException("Cannot locate source directory for skill '${manifest.name}'")
            copySkill(sourceDir, dest)
            log.info("Installed skill '${manifest.name}' to ${dest.absolutePath}")
            dest
        }.onFailure { e ->
            log.warn("Failed to install skill '${manifest.name}': ${e.message}")
        }
    }

    /**
     * Uninstall a skill from the given target.
     */
    fun uninstall(
        skillName: String,
        target: InstallTarget,
        projectBasePath: String? = null
    ): Result<Unit> {
        val dest = target.resolvePath(projectBasePath, skillName)
        return runCatching {
            if (dest.exists()) {
                dest.deleteRecursively()
                log.info("Uninstalled skill '$skillName' from ${dest.absolutePath}")
            }
        }.onFailure { e ->
            log.warn("Failed to uninstall skill '$skillName': ${e.message}")
        }
    }

    /**
     * Check if a skill is installed at the given target.
     */
    fun isInstalled(
        skillName: String,
        target: InstallTarget,
        projectBasePath: String? = null
    ): Boolean {
        return target.resolvePath(projectBasePath, skillName).exists()
    }

    /**
     * List all installed skills at the given target.
     */
    fun listInstalled(
        target: InstallTarget,
        projectBasePath: String? = null
    ): List<SkillManifest> {
        val parser = ManifestParser()
        val home = System.getProperty("user.home")
        val baseDir = when (target) {
            InstallTarget.CLAUDE_PROJECT -> projectBasePath?.let { File("$it/.claude/skills") }
            InstallTarget.CLAUDE_USER -> File("$home/.claude/skills")
            InstallTarget.JUNIE_PROJECT -> projectBasePath?.let { File("$it/.junie/skills") }
            InstallTarget.AGENTRY_CACHE -> File("$home/.agentry/skills")
        } ?: return emptyList()

        if (!baseDir.exists()) return emptyList()
        return baseDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { skillDir ->
                parser.scanDirectory(skillDir).firstOrNull()
                    ?.copy(installedPath = skillDir.absolutePath)
            } ?: emptyList()
    }

    private fun resolveSourceDir(manifest: SkillManifest): File? {
        if (manifest.installedPath != null) return File(manifest.installedPath)
        // Try to find it in the registry cache
        val cacheRoot = File(System.getProperty("user.home"), ".agentry/cache")
        if (!cacheRoot.exists()) return null
        return cacheRoot.walk()
            .filter { it.isDirectory && it.name == manifest.name }
            .firstOrNull()
    }

    private fun copySkill(source: File, dest: File) {
        Files.createDirectories(dest.toPath())
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val target = File(dest, relative.path)
            if (file.isDirectory) {
                Files.createDirectories(target.toPath())
            } else {
                Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
