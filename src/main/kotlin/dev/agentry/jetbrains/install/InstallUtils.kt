package dev.agentry.jetbrains.install

import dev.agentry.jetbrains.util.InputValidation
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Recursively copy [source] → [dest], refusing to follow or copy symlinks. Same hardening
 * the merged UI PR added to `SkillInstaller` — symlinks at any level (the source root,
 * subdirectories, individual files) cause a [SecurityException]. Every destination is
 * canonical-path-checked to stay inside [dest].
 */
internal fun copySafe(source: File, dest: File) {
    if (Files.isSymbolicLink(source.toPath())) {
        throw SecurityException("Refusing to copy from symlinked source: $source")
    }
    Files.createDirectories(dest.toPath())
    if (source.isFile) {
        copyFile(source, dest)
        return
    }
    val sourceRoot = source.toPath().toRealPath()
    val destRoot = dest.toPath()
    Files.walk(sourceRoot).use { stream ->
        stream.forEach { entry ->
            if (Files.isSymbolicLink(entry)) {
                throw SecurityException("Refusing to copy symlink: $entry")
            }
            val rel = sourceRoot.relativize(entry)
            val target = destRoot.resolve(rel)
            require(InputValidation.isInsideDir(target.toFile(), dest)) {
                "Resolved target escapes destination: $target"
            }
            when {
                Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ->
                    Files.createDirectories(target)
                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) ->
                    Files.copy(
                        entry, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS
                    )
                // Skip fifos / devices / sockets.
            }
        }
    }
}

private fun copyFile(source: File, dest: File) {
    val target = if (dest.isDirectory) File(dest, source.name) else dest
    Files.createDirectories((target.parentFile ?: dest).toPath())
    Files.copy(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        LinkOption.NOFOLLOW_LINKS
    )
}

