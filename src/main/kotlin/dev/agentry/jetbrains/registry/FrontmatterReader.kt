package dev.agentry.jetbrains.registry

import dev.agentry.jetbrains.model.Frontmatter

/**
 * Tiny YAML frontmatter reader. Pulls only the scalar fields Agentry uses (`name`,
 * `description`, `argument-hint`); any other keys are silently dropped.
 *
 * We don't pull in a full YAML dependency just for this — the frontmatter block is always
 * `--- … ---` at the top of a markdown file and the keys we care about are simple `key: value`
 * lines. If a file ever ships a complex nested-frontmatter shape we can swap to a real
 * parser; until then this hand-rolled splitter avoids ~3MB of jackson-yaml on the
 * critical path.
 */
object FrontmatterReader {

    /**
     * Read [body] and return the parsed frontmatter, or an empty [Frontmatter] if the file
     * doesn't begin with a `---` fence.
     */
    fun read(body: String): Frontmatter {
        val lines = body.lineSequence().iterator()
        if (!lines.hasNext()) return EMPTY
        val first = lines.next().trim()
        if (first != "---") return EMPTY

        val fields = mutableMapOf<String, String>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.trim() == "---") break
            // Strip inline comments only when preceded by whitespace; YAML rule.
            val cleaned = stripInlineComment(line).trimEnd()
            if (cleaned.isBlank()) continue
            val colonIdx = cleaned.indexOf(':')
            if (colonIdx <= 0) continue
            val key = cleaned.substring(0, colonIdx).trim()
            val rawValue = cleaned.substring(colonIdx + 1).trim()
            if (rawValue.isNotEmpty()) {
                fields[key] = unquote(rawValue)
            }
        }
        return Frontmatter(
            name = fields["name"],
            description = fields["description"],
            argumentHint = fields["argument-hint"] ?: fields["argumentHint"]
        )
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    private fun stripInlineComment(line: String): String {
        // YAML treats `#` as a comment only when preceded by whitespace.
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace()) -> {
                    return line.substring(0, i)
                }
            }
        }
        return line
    }

    private val EMPTY = Frontmatter(null, null, null)
}
