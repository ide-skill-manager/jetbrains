package dev.agentry.jetbrains.util

import java.io.File

/**
 * Validates inputs that flow from untrusted sources (`.agentry/config.yaml`, remote manifests,
 * settings UI) into the filesystem or the `git` CLI. Centralised here so a single fix lands
 * everywhere these strings are consumed.
 */
object InputValidation {

    private val SKILL_NAME = Regex("^[a-z0-9][a-z0-9._\\-]{0,63}$", RegexOption.IGNORE_CASE)
    private val GIT_REF = Regex("^[A-Za-z0-9][A-Za-z0-9._\\-/]{0,254}$")
    private val ALLOWED_URL_SCHEMES = setOf("https", "http", "git", "ssh")
    private val SSH_SCP_FORM = Regex("^[A-Za-z0-9_.\\-]+@[A-Za-z0-9_.\\-]+:[A-Za-z0-9_./\\-]+$")
    /** `ext::…`, `transport-helper::…` — anchored to the *start* of the URL only. */
    private val TRANSPORT_HELPER_PREFIX = Regex("^[A-Za-z][A-Za-z0-9_+.\\-]*::")
    /** Matches `scheme://userinfo@…` so we can substitute `***` for the userinfo. */
    private val USERINFO_PATTERN = Regex("^(\\w+://)[^/@\\s]+@")

    /**
     * Skill names become directory names. Reject anything that could escape the install root
     * or trip downstream tools (slashes, `..`, leading dots, control chars, leading `-`).
     */
    fun isValidSkillName(name: String?): Boolean =
        !name.isNullOrBlank() && SKILL_NAME.matches(name) && name != "." && name != ".."

    /**
     * Git refs are passed positionally to git CLI. Reject anything starting with `-` (flag
     * injection) or containing characters git rejects. We're stricter than git's actual
     * refname rules — fine because legitimate refs always satisfy this.
     */
    fun isValidGitRef(ref: String?): Boolean =
        !ref.isNullOrBlank() && GIT_REF.matches(ref) && !ref.startsWith(".") && !ref.contains("..")

    /**
     * Registry URLs are passed to `git clone`. Reject:
     *  - anything starting with `-` (flag injection)
     *  - `scheme::path` transport syntax (`ext::`, `transport-helper::…`) that can execute
     *    arbitrary shell. Detected as `<word>::` at the start of the URL — this leaves
     *    legitimate IPv6 host URLs like `https://[2001:db8::1]/repo.git` alone, since
     *    those start with a scheme followed by `://`, not `::`.
     *  - `file://` (local filesystem disclosure)
     *  - bare paths
     * Accept https / http / ssh / git protocols, plus the common SCP-form `user@host:path`.
     */
    fun isValidRegistryUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || url.startsWith("-")) return false
        if (url.startsWith("file:")) return false
        if (TRANSPORT_HELPER_PREFIX.containsMatchIn(url)) return false
        if (SSH_SCP_FORM.matches(url)) return true
        val scheme = url.substringBefore("://", "").lowercase()
        return scheme in ALLOWED_URL_SCHEMES
    }

    /**
     * Strip userinfo from a URL before it lands in logs, on stdout, or in any
     * in-memory model that's surfaced to humans/agents. Catches the common
     * `https://token@host/repo.git` and `https://user:pass@host/repo.git` forms.
     * Idempotent — calling it on an already-redacted URL is a no-op.
     */
    fun redactCredentials(url: String): String =
        url.replace(USERINFO_PATTERN, "$1***@")

    /**
     * After resolving a destination, ensure it stays inside [base]. Guards against
     * `name = "../../etc/passwd"` slipping past the regex.
     */
    fun isInsideDir(dest: File, base: File): Boolean {
        val destPath = dest.canonicalFile.toPath()
        val basePath = base.canonicalFile.toPath()
        return destPath.startsWith(basePath)
    }
}
