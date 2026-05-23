package dev.agentry.jetbrains.registry

/**
 * Branches, tags, and default ref discovered by `git ls-remote` for a registry URL.
 */
data class RemoteRefs(
    val branches: List<String>,
    val tags: List<String>,
    /** The remote's HEAD ref (typically `main` or `master`). Null if `ls-remote` didn't return a symref. */
    val defaultRef: String?
) {
    val isEmpty: Boolean get() = branches.isEmpty() && tags.isEmpty()
}
