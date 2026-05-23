package dev.agentry.jetbrains.model

/**
 * A git URL Agentry treats as a skill registry.
 *
 * The [ref] field is what makes branch-based skill development work: pin to `main` for
 * stable, or to `feature/wip-foo` to iterate on a skill in place — Agentry will pull the
 * latest commit of that ref on every Refresh.
 */
data class RegistrySource(
    val url: String,
    val ref: String = "HEAD",
    val enabled: Boolean = true,
    val displayName: String = url
)
