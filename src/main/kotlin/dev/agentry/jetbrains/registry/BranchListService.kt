package dev.agentry.jetbrains.registry

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.util.InputValidation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs `git ls-remote` to enumerate the refs a registry URL exposes, so the **Add Registry**
 * dialog can offer a real branch / tag dropdown instead of a free-text field.
 *
 * Results are cached for [CACHE_TTL_MS] keyed by the *redacted* URL, so re-opening the
 * dialog or typing a URL twice doesn't hit the network each time. The cache key is
 * redacted to avoid two RegistrySource entries that differ only in embedded credentials
 * occupying distinct cache slots, and to keep the in-memory map free of secrets.
 *
 * Same env hardening as [RegistryManager.runGit]: restricted transports, no terminal
 * credential prompts, output drained safely, child process reaped on timeout/cancel.
 */
@Service(Service.Level.APP)
class BranchListService {

    private val log = logger<BranchListService>()
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Fetch refs for [url]. Returns a [Result] so the dialog can surface a typed failure
     * instead of a thrown exception. Validation rejection short-circuits before any
     * subprocess is launched.
     */
    fun fetch(url: String): Result<RemoteRefs> {
        if (!InputValidation.isValidRegistryUrl(url)) {
            return Result.failure(IllegalArgumentException("Invalid registry URL"))
        }
        val key = InputValidation.redactCredentials(url)
        cache[key]?.takeIf { it.notExpired() }?.let { return it.result }
        val fresh = runCatching { runLsRemote(url) }
        cache[key] = CacheEntry(fresh, System.currentTimeMillis())
        return fresh
    }

    /** Drop the cached entry for [url]; next [fetch] re-runs `ls-remote`. */
    fun invalidate(url: String) {
        cache.remove(InputValidation.redactCredentials(url))
    }

    private fun runLsRemote(url: String): RemoteRefs {
        val cmd = listOf("git", "ls-remote", "--symref", "--heads", "--tags", "--quiet", "--", url)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["GIT_ALLOW_PROTOCOL"] = "https:http:ssh:git"
        pb.environment()["GIT_PROTOCOL_FROM_USER"] = "1"
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"

        val proc = pb.start()
        val output = StringBuilder()
        try {
            proc.inputStream.bufferedReader().use { reader ->
                val deadline = System.currentTimeMillis() + LS_REMOTE_TIMEOUT_MS
                while (proc.isAlive) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw RuntimeException("git ls-remote timed out after ${LS_REMOTE_TIMEOUT_MS}ms")
                    }
                    if (proc.waitFor(200, TimeUnit.MILLISECONDS)) break
                    while (reader.ready()) output.appendLine(reader.readLine() ?: break)
                }
                val tail = reader.readText()
                if (tail.isNotEmpty()) output.append(tail)
            }
        } finally {
            if (proc.isAlive) {
                proc.destroyForcibly()
                proc.waitFor()
            }
        }
        if (proc.exitValue() != 0) {
            val safe = InputValidation.redactCredentials(output.toString())
            throw RuntimeException("git ls-remote exited ${proc.exitValue()}: $safe")
        }
        return parse(output.toString())
    }

    /**
     * Parse the output of `git ls-remote --symref --heads --tags`. Lines look like:
     *
     *   `ref: refs/heads/main\tHEAD`              (symref line for HEAD)
     *   `<sha>\tHEAD`                              (HEAD's resolved SHA)
     *   `<sha>\trefs/heads/<branch>`
     *   `<sha>\trefs/tags/<tag>`
     *   `<sha>\trefs/tags/<tag>^{}`                (annotated-tag dereference; skip)
     */
    internal fun parse(raw: String): RemoteRefs {
        val branches = mutableListOf<String>()
        val tags = mutableListOf<String>()
        var defaultRef: String? = null
        for (line in raw.lineSequence().map { it.trim() }) {
            if (line.isEmpty()) continue
            when {
                line.startsWith("ref:") -> {
                    // `ref: refs/heads/main\tHEAD`
                    val ref = line.removePrefix("ref:").trim().substringBefore('\t').trim()
                    defaultRef = ref.removePrefix("refs/heads/").removePrefix("refs/tags/")
                }
                else -> {
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) continue
                    val refName = parts[1]
                    when {
                        refName == "HEAD" -> { /* resolved HEAD SHA — already covered by symref */ }
                        refName.endsWith("^{}") -> { /* tag dereference; skip */ }
                        refName.startsWith("refs/heads/") ->
                            branches += refName.removePrefix("refs/heads/")
                        refName.startsWith("refs/tags/") ->
                            tags += refName.removePrefix("refs/tags/")
                    }
                }
            }
        }
        // Sort for stable presentation (default ref pinned to top by the dialog, not here).
        return RemoteRefs(branches.sorted(), tags.sorted(), defaultRef)
    }

    companion object {
        private const val CACHE_TTL_MS = 60_000L
        private const val LS_REMOTE_TIMEOUT_MS = 15_000L

        fun getInstance(): BranchListService =
            ApplicationManager.getApplication().getService(BranchListService::class.java)
    }

    private data class CacheEntry(val result: Result<RemoteRefs>, val timestamp: Long) {
        fun notExpired(): Boolean = System.currentTimeMillis() - timestamp < CACHE_TTL_MS
    }
}
