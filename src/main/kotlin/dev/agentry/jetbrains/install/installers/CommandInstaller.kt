package dev.agentry.jetbrains.install.installers

import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.splitFrontmatter
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File
import java.nio.file.Files

/**
 * Translates a Claude / Copilot slash command (a `.md` under `commands/`) into the
 * JetBrains "Prompt File" shape (`.prompt.md` under `.github/prompts/`).
 *
 * Lossy translation:
 *  - Keeps `description` from frontmatter.
 *  - Drops `argument-hint` — JetBrains Prompt Files don't have a 1:1 equivalent. We log
 *    a warning so the user sees it, rather than failing silently.
 *  - The body is copied verbatim. Positional `$1`/`$2` markers are left untouched; the
 *    Prompt File runtime will treat them as literal text. (Lossy by design — captured
 *    in the spec's "Open questions".)
 */
internal class CommandInstaller : ComponentInstaller<PluginComponent.Command> {

    private val log = logger<CommandInstaller>()

    override fun install(
        component: PluginComponent.Command,
        plugin: PluginManifest,
        scope: InstallScope
    ): File {
        val source = component.sourceFile.readText()
        val translated = translate(source, component)
        val dest = InstallPaths.promptFile(component.name, scope)
        Files.createDirectories(dest.parentFile.toPath())
        Files.writeString(dest.toPath(), translated)
        if (component.argumentHint != null) {
            log.info(
                "Command '${component.name}': dropped argument-hint='${component.argumentHint}' " +
                    "(JetBrains Prompt Files don't support it). The body is unchanged."
            )
        }
        return dest
    }

    /**
     * Rewrite the frontmatter so the file is loadable by JetBrains as a `.prompt.md`.
     * Frontmatter rules JetBrains' Prompt Files care about (as of the Mar 2026 changelog):
     * `description` is shown in the picker; other fields are ignored. We keep the body
     * untouched.
     */
    private fun translate(source: String, component: PluginComponent.Command): String {
        val (fmBlock, body) = splitFrontmatter(source)
        val rewritten = if (fmBlock != null) {
            val keptLines = fmBlock.lineSequence()
                .filter { line ->
                    val k = line.substringBefore(':').trim()
                    k != "argument-hint" && k != "argumentHint"
                }
                .joinToString("\n")
            "---\n$keptLines\n---\n$body"
        } else if (component.description != null) {
            "---\ndescription: ${component.description}\n---\n$source"
        } else {
            source
        }
        return rewritten
    }

}
