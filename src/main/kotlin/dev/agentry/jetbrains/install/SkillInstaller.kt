package dev.agentry.jetbrains.install

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.InstalledSkill
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.ManifestParser
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.util.InputValidation
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/**
 * Installs / uninstalls / inspects skills on disk.
 *
 * Hardening: source dirs are resolved by `(sourceRegistry, name)` to prevent one registry
 * silently shadowing another; the copy refuses symlinks; the destination is validated to
 * stay inside the install root.
 */
@Service(Service.Level.APP)
class SkillInstaller {

    private val log = logger<SkillInstaller>()
    private val parser = ManifestParser()

    /**
     * Install a skill. Returns the install directory on success.
     *
     * The replace sequence is:
     *   1. copy source → sibling staging dir (so a failed copy leaves the old install intact)
     *   2. rename existing dest → backup (if dest already exists)
     *   3. rename staging → dest
     *   4. delete backup
     *
     * If step 3 fails after the backup move, we restore the backup. The user always sees
     * either the previous install or the new one — never a partial state, never nothing.
     */
    fun install(
        manifest: SkillManifest,
        target: InstallTarget,
        projectBasePath: String? = null
    ): Result<File> = runCatching {
        require(InputValidation.isValidSkillName(manifest.name)) {
            "Invalid skill name in manifest: '${manifest.name}'"
        }
        val dest = target.resolvePath(projectBasePath, manifest.name)
        val sourceDir = resolveSourceDir(manifest)
            ?: error("Cannot locate source directory for skill '${manifest.name}' from '${manifest.sourceRegistry}'")

        val parent = dest.parentFile ?: error("Install target has no parent: $dest")
        Files.createDirectories(parent.toPath())
        val staging = File(parent, ".${dest.name}.installing-${System.nanoTime()}")
        val backup = File(parent, ".${dest.name}.backup-${System.nanoTime()}")

        try {
            copySkill(sourceDir, staging)
            // Old install (if any) → backup, so we can roll back.
            if (dest.exists() && !dest.renameTo(backup)) {
                throw IOException("Could not move existing install aside: ${dest.absolutePath}")
            }
            try {
                if (!staging.renameTo(dest)) {
                    // Cross-filesystem fallback: copy then delete the staging tree.
                    staging.copyRecursively(dest, overwrite = false)
                    staging.deleteRecursively()
                }
            } catch (e: Throwable) {
                // Roll back: nuke any half-written dest, restore backup if we had one.
                dest.deleteRecursively()
                if (backup.exists()) backup.renameTo(dest)
                throw e
            }
            // Success: clean up backup.
            if (backup.exists()) backup.deleteRecursively()
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        refreshVfs(dest)
        log.info("Installed skill '${manifest.name}' to ${dest.absolutePath}")
        dest
    }.onFailure { e ->
        log.warn("Failed to install skill '${manifest.name}': ${e.message}")
    }

    /** Uninstall a skill. No-op if not installed. */
    fun uninstall(
        skillName: String,
        target: InstallTarget,
        projectBasePath: String? = null
    ): Result<Unit> = runCatching {
        val dest = target.resolvePath(projectBasePath, skillName)
        if (dest.exists()) {
            dest.deleteRecursively()
            refreshVfs(dest.parentFile ?: dest)
            log.info("Uninstalled skill '$skillName' from ${dest.absolutePath}")
        }
    }

    fun isInstalled(
        skillName: String,
        target: InstallTarget,
        projectBasePath: String? = null
    ): Boolean = runCatching {
        target.resolvePath(projectBasePath, skillName).exists()
    }.getOrDefault(false)

    /** List skills installed at the given target. */
    fun listInstalled(
        target: InstallTarget,
        projectBasePath: String? = null
    ): List<InstalledSkill> {
        val base = target.baseDir(projectBasePath) ?: return emptyList()
        if (!base.exists()) return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory && InputValidation.isValidSkillName(it.name) }
            ?.mapNotNull { dir ->
                parser.scanDirectory(dir).firstOrNull()?.let { manifest ->
                    InstalledSkill(manifest, dir, target)
                }
            }
            .orEmpty()
    }

    /**
     * Look up the source directory for [manifest], scoped to its origin registry *at the
     * exact ref it was fetched from*. Both fields are needed because the cache directory
     * key includes both — the same URL registered at two refs has two cache dirs.
     *
     * Rejects symlinks at both the registry-cache root and the per-skill candidate. A
     * malicious local filesystem (or a future bug) could otherwise replace one of those
     * directories with a symlink to `~/.ssh/`, bypassing the per-entry symlink check that
     * `copySkill` performs during the walk.
     */
    private fun resolveSourceDir(manifest: SkillManifest): File? {
        if (manifest.sourceRegistry.isBlank()) return null
        val source = RegistrySource(url = manifest.sourceRegistry, ref = manifest.sourceRef)
        val registryDir = RegistryManager.getInstance().localDirFor(source)
        if (!registryDir.isDirectory) return null
        if (Files.isSymbolicLink(registryDir.toPath())) {
            log.warn("Refusing symlinked registry dir: ${registryDir.absolutePath}")
            return null
        }
        val candidate = File(registryDir, manifest.name)
        if (candidate.isDirectory && !Files.isSymbolicLink(candidate.toPath())) return candidate
        if (parser.scanDirectory(registryDir).any { it.name == manifest.name }) return registryDir
        return null
    }

    /**
     * Copy [source] → [dest]. Refuses to follow or copy symlinks (a malicious registry
     * could otherwise point them at `~/.ssh/id_rsa`). Verifies every destination stays
     * inside [dest].
     */
    private fun copySkill(source: File, dest: File) {
        require(source.isDirectory) { "Source is not a directory: $source" }
        // Reject a symlinked source root before resolving — toRealPath would otherwise
        // follow the link out of the registry cache.
        if (Files.isSymbolicLink(source.toPath())) {
            throw SecurityException("Refusing to copy from symlinked skill source: $source")
        }
        val sourcePath = source.toPath().toRealPath()
        val destPath = dest.toPath()
        Files.createDirectories(destPath)

        Files.walk(sourcePath).use { stream ->
            stream.forEach { entry: Path ->
                if (entry.isSymbolicLink()) {
                    throw SecurityException("Refusing to copy symlink in skill source: $entry")
                }
                val rel = sourcePath.relativize(entry)
                val target = destPath.resolve(rel)
                require(InputValidation.isInsideDir(target.toFile(), dest)) {
                    "Resolved target escapes destination: $target"
                }
                when {
                    entry.isDirectory(LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(target)
                    entry.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> Files.copy(
                        entry, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS
                    )
                    // Skip fifos, devices, sockets — neither follow nor copy.
                }
            }
        }
    }

    /**
     * Tell IntelliJ's VFS about new/removed files so the Project view, code-insight, and
     * any agent that reads through the IDE sees the change without a restart.
     */
    private fun refreshVfs(path: File) {
        // VFS refresh must be scheduled, not run inline on the EDT during a write action.
        ApplicationManager.getApplication().invokeLater {
            VfsUtil.markDirtyAndRefresh(true, true, true, path)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path)
        }
    }

    companion object {
        fun getInstance(): SkillInstaller =
            ApplicationManager.getApplication().getService(SkillInstaller::class.java)
    }
}
