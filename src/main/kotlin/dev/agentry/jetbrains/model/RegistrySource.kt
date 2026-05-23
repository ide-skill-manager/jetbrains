package dev.agentry.jetbrains.model

/**
 * A registry source: a git URL from which skills/manifests are fetched.
 */
data class RegistrySource(
    val url: String,
    /** Optional branch, tag, or commit SHA to pin. Defaults to HEAD. */
    val ref: String = "HEAD",
    val enabled: Boolean = true,
    val displayName: String = url
)
