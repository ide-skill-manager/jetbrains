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
/**
 * Split a markdown body into `(frontmatter-without-fences, body)` or `(null, body)` when
 * there's no `---` fence at the top. Used by the installers that need to rewrite a few
 * frontmatter fields while preserving the rest of the file. Hand-rolled — `FrontmatterReader`
 * is read-only.
 */
internal fun splitFrontmatter(text: String): Pair<String?, String> {
    if (!text.trimStart().startsWith("---")) return null to text
    val lines = text.lines()
    var i = 1
    val fm = StringBuilder()
    while (i < lines.size && lines[i].trim() != "---") {
        if (fm.isNotEmpty()) fm.append("\n")
        fm.append(lines[i])
        i++
    }
    if (i >= lines.size) return null to text // unterminated fence; treat as body
    val body = lines.drop(i + 1).joinToString("\n")
    return fm.toString() to body
}

/** Create parent directories then write [text] to [this]. The two-line pattern showed up
 *  in every installer that writes a single text file. */
internal fun File.writeTextEnsuringParent(text: String) {
    Files.createDirectories(parentFile.toPath())
    Files.writeString(toPath(), text)
}

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

