package dev.agentry.jetbrains.sidecar

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.InputStream

/**
 * Manages the agentry CLI sidecar process.
 * The sidecar is a bundled binary that handles git operations and manifest resolution.
 * Communication is via line-delimited JSON over stdio.
 */
class SidecarManager(
    private val customPath: String = ""
) {

    private val log = logger<SidecarManager>()
    private var process: Process? = null

    /**
     * Locate the sidecar binary — first checks the custom path from settings,
     * then looks for a bundled binary in the plugin's resources/bin/ directory,
     * then falls back to the system PATH.
     */
    fun resolveBinaryPath(): String? {
        if (customPath.isNotBlank()) {
            val f = File(customPath)
            if (f.exists() && f.canExecute()) return customPath
        }

        // Try bundled binary
        val bundled = bundledBinaryPath()
        if (bundled != null) return bundled

        // Fall back to system PATH
        val name = binaryName()
        val onPath = ProcessBuilder(if (SystemInfo.isWindows) listOf("where", name) else listOf("which", name))
            .start()
            .inputStream.bufferedReader().readLine()?.trim()
        return onPath?.takeIf { it.isNotBlank() }
    }

    /**
     * Send a command to the sidecar and return the response.
     * Commands are line-delimited JSON objects with a "method" field.
     */
    fun send(method: String, params: Map<String, Any> = emptyMap()): String? {
        return runCatching {
            val proc = getOrStartProcess() ?: return null
            val request = buildJsonObject(method, params)
            proc.outputStream.write((request + "\n").toByteArray())
            proc.outputStream.flush()
            proc.inputStream.bufferedReader().readLine()
        }.onFailure { e ->
            log.warn("Sidecar communication error: ${e.message}")
            process = null // force restart on next call
        }.getOrNull()
    }

    /** Stop the sidecar process. */
    fun stop() {
        process?.destroyForcibly()
        process = null
    }

    private fun getOrStartProcess(): Process? {
        if (process?.isAlive == true) return process
        val path = resolveBinaryPath() ?: run {
            log.info("Sidecar binary not found; operating in direct mode")
            return null
        }
        return runCatching {
            ProcessBuilder(path)
                .redirectErrorStream(false)
                .start()
                .also { process = it }
        }.onFailure { e ->
            log.warn("Failed to start sidecar: ${e.message}")
        }.getOrNull()
    }

    private fun bundledBinaryPath(): String? {
        val resourceName = "/bin/${binaryName()}"
        val stream: InputStream = javaClass.getResourceAsStream(resourceName) ?: return null
        val tmpFile = File.createTempFile("agentry-sidecar", if (SystemInfo.isWindows) ".exe" else "")
        tmpFile.deleteOnExit()
        stream.use { input -> tmpFile.outputStream().use { out -> input.copyTo(out) } }
        tmpFile.setExecutable(true)
        return tmpFile.absolutePath
    }

    private fun binaryName(): String = when {
        SystemInfo.isWindows -> "agentry.exe"
        SystemInfo.isMac && System.getProperty("os.arch").contains("aarch64") -> "agentry-darwin-arm64"
        SystemInfo.isMac -> "agentry-darwin-x64"
        System.getProperty("os.arch").contains("aarch64") -> "agentry-linux-arm64"
        else -> "agentry-linux-x64"
    }

    private fun buildJsonObject(method: String, params: Map<String, Any>): String {
        val sb = StringBuilder("{\"method\":\"$method\"")
        if (params.isNotEmpty()) {
            sb.append(",\"params\":{")
            sb.append(params.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" })
            sb.append("}")
        }
        sb.append("}")
        return sb.toString()
    }
}
