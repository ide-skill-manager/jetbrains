package dev.agentry.jetbrains.cli

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.registry.BranchListService
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings
import dev.agentry.jetbrains.util.InputValidation
import kotlin.system.exitProcess

/**
 * Headless entry point: `idea agentry <subcommand> [args]`.
 *
 * Lets agents and CI scripts drive Agentry without opening a GUI:
 *
 *   idea agentry list                          # list skills in all enabled registries
 *   idea agentry installed                     # list installed skills (default target)
 *   idea agentry install <name>                # install by name to default target
 *   idea agentry install <name> --target CLAUDE_USER
 *   idea agentry remove  <name>                # remove from default target
 *   idea agentry refresh                       # fetch all registries
 *
 * Exit codes: 0 success, 1 usage error, 2 operation failed.
 */
class AgentryStarter : ApplicationStarter {

    override fun main(args: List<String>) {
        // args[0] is the command name itself.
        val sub = args.getOrNull(1)
        val rest = args.drop(2)
        val code = try {
            when (sub) {
                "list" -> cmdList(rest)
                "installed" -> cmdInstalled(parseTarget(rest))
                "install" -> cmdInstall(rest)
                "remove" -> cmdRemove(rest)
                "refresh" -> cmdRefresh()
                "refs" -> cmdRefs(rest)
                "registries" -> cmdRegistries()
                null, "help", "--help", "-h" -> { printUsage(); 0 }
                else -> { printUsage(); 1 }
            }
        } catch (e: Throwable) {
            System.err.println("agentry: ${e.message}")
            2
        }
        ApplicationManager.getApplication().exit(true, true, false)
        exitProcess(code)
    }

    private fun cmdList(rest: List<String>): Int {
        val registryFilter = optionValue(rest, "--registry")
        val searchFilter = optionValue(rest, "--search")?.lowercase()
        val sources = enabledSources()
        val manifests = RegistryManager.getInstance().fetchAll(sources).values.flatten()
        manifests
            .filter { m ->
                val source = InputValidation.redactCredentials(m.sourceRegistry)
                (registryFilter == null || source.contains(registryFilter) ||
                    sources.firstOrNull { it.url == m.sourceRegistry }?.displayName?.contains(registryFilter) == true) &&
                (searchFilter == null || m.name.lowercase().contains(searchFilter)
                    || m.description.lowercase().contains(searchFilter))
            }
            .forEach {
                println("${it.name}\t${it.version}\t${InputValidation.redactCredentials(it.sourceRegistry)}")
            }
        return 0
    }

    private fun cmdRefs(rest: List<String>): Int {
        val url = rest.firstOrNull() ?: return usageErr("refs requires a registry URL")
        val refs = BranchListService.getInstance().fetch(url).getOrElse {
            return failed("ls-remote failed: ${it.message}")
        }
        refs.defaultRef?.let { println("default\t$it") }
        refs.branches.forEach { println("branch\t$it") }
        refs.tags.forEach { println("tag\t$it") }
        return 0
    }

    private fun cmdRegistries(): Int {
        AgentrySettings.getInstance().registrySources.forEach { src ->
            val safeUrl = InputValidation.redactCredentials(src.url)
            val state = if (src.enabled) "enabled" else "disabled"
            val displayName = src.displayName.ifBlank { safeUrl }
            println("$displayName\t$safeUrl\t${src.ref}\t$state")
        }
        return 0
    }

    private fun optionValue(args: List<String>, flag: String): String? {
        val idx = args.indexOf(flag)
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
    }

    private fun cmdInstalled(target: InstallTarget): Int {
        SkillInstaller.getInstance().listInstalled(target).forEach {
            println("${it.manifest.name}\t${it.manifest.version}\t${it.location.absolutePath}")
        }
        return 0
    }

    private fun cmdInstall(rest: List<String>): Int {
        val names = rest.takeWhile { !it.startsWith("--") }
        if (names.isEmpty()) return usageErr("install requires at least one skill name")
        val target = parseTarget(rest)
        val manifestsByName = RegistryManager.getInstance()
            .fetchAll(enabledSources()).values.flatten()
            .associateBy { it.name }
        var failures = 0
        names.forEach { name ->
            val manifest = manifestsByName[name]
            if (manifest == null) {
                System.err.println("agentry: '$name' not found in any enabled registry")
                failures++
                return@forEach
            }
            val result = SkillInstaller.getInstance().install(manifest, target, projectBasePathOrNull())
            if (result.isSuccess) {
                println("installed: ${result.getOrThrow().absolutePath}")
            } else {
                System.err.println("agentry: install '$name' failed: ${result.exceptionOrNull()?.message}")
                failures++
            }
        }
        return if (failures == 0) 0 else 2
    }

    private fun cmdRemove(rest: List<String>): Int {
        val names = rest.takeWhile { !it.startsWith("--") }
        if (names.isEmpty()) return usageErr("remove requires at least one skill name")
        val target = parseTarget(rest)
        var failures = 0
        names.forEach { name ->
            val result = SkillInstaller.getInstance().uninstall(name, target, projectBasePathOrNull())
            if (result.isFailure) {
                System.err.println("agentry: remove '$name' failed: ${result.exceptionOrNull()?.message}")
                failures++
            }
        }
        return if (failures == 0) 0 else 2
    }

    private fun cmdRefresh(): Int {
        RegistryManager.getInstance().fetchAll(enabledSources())
        return 0
    }

    private fun enabledSources(): List<RegistrySource> =
        AgentrySettings.getInstance().registrySources.map {
            RegistrySource(it.url, it.ref, it.enabled, it.displayName)
        }

    private fun parseTarget(args: List<String>): InstallTarget {
        val idx = args.indexOf("--target")
        if (idx < 0) return AgentrySettings.getInstance().defaultInstallTarget
        // `--target` is present — require a value. Silently defaulting hides typos and
        // can install to an unintended location.
        val value = args.getOrNull(idx + 1)
            ?: error("--target requires a value (one of: ${enumValues<InstallTarget>().joinToString { it.name }})")
        return enumValues<InstallTarget>().firstOrNull { it.name == value }
            ?: error("Unknown --target: $value")
    }

    /** Project-scoped targets aren't meaningful in CLI mode unless a project path is set later. */
    private fun projectBasePathOrNull(): String? = System.getProperty("agentry.project.basePath")

    private fun usageErr(msg: String): Int {
        System.err.println("agentry: $msg")
        printUsage()
        return 1
    }

    private fun failed(msg: String): Int {
        System.err.println("agentry: $msg")
        return 2
    }

    private fun printUsage() {
        println("""
            Usage: idea agentry <command> [args]

            Commands:
              list [--registry R] [--search S]              list available skills
              installed [--target T]                        list installed skills at target T
              install <name>… [--target T]                  install one or more skills
              remove  <name>… [--target T]                  uninstall one or more skills
              refresh                                       fetch all enabled registries
              refs <url>                                    list branches/tags exposed by a registry URL
              registries                                    list configured registries with status

            Targets: ${enumValues<InstallTarget>().joinToString { it.name }}
        """.trimIndent())
    }
}
