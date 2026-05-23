package dev.agentry.jetbrains.cli

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings
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
@Suppress("OVERRIDE_DEPRECATION", "OVERRIDING_DEPRECATED_MEMBER", "DEPRECATION")
class AgentryStarter : ApplicationStarter {

    override val commandName: String = "agentry"

    override fun main(args: List<String>) {
        // args[0] is the command name itself.
        val sub = args.getOrNull(1)
        val rest = args.drop(2)
        val code = try {
            when (sub) {
                "list" -> cmdList()
                "installed" -> cmdInstalled(parseTarget(rest))
                "install" -> cmdInstall(rest)
                "remove" -> cmdRemove(rest)
                "refresh" -> cmdRefresh()
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

    private fun cmdList(): Int {
        val sources = enabledSources()
        val manifests = RegistryManager.getInstance().fetchAll(sources).values.flatten()
        manifests.forEach { println("${it.name}\t${it.version}\t${it.sourceRegistry}") }
        return 0
    }

    private fun cmdInstalled(target: InstallTarget): Int {
        SkillInstaller.getInstance().listInstalled(target).forEach {
            println("${it.manifest.name}\t${it.manifest.version}\t${it.location.absolutePath}")
        }
        return 0
    }

    private fun cmdInstall(rest: List<String>): Int {
        val name = rest.firstOrNull() ?: return usageErr("install requires a skill name")
        val target = parseTarget(rest.drop(1))
        val manifest = RegistryManager.getInstance()
            .fetchAll(enabledSources()).values.flatten()
            .firstOrNull { it.name == name }
            ?: return failed("skill '$name' not found in any enabled registry")
        val result = SkillInstaller.getInstance().install(manifest, target, projectBasePathOrNull())
        return if (result.isSuccess) {
            println("installed: ${result.getOrThrow().absolutePath}"); 0
        } else {
            failed("install failed: ${result.exceptionOrNull()?.message}")
        }
    }

    private fun cmdRemove(rest: List<String>): Int {
        val name = rest.firstOrNull() ?: return usageErr("remove requires a skill name")
        val target = parseTarget(rest.drop(1))
        val result = SkillInstaller.getInstance().uninstall(name, target, projectBasePathOrNull())
        return if (result.isSuccess) 0 else failed("remove failed: ${result.exceptionOrNull()?.message}")
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
        if (idx >= 0 && idx + 1 < args.size) {
            return enumValues<InstallTarget>().firstOrNull { it.name == args[idx + 1] }
                ?: error("Unknown --target: ${args[idx + 1]}")
        }
        return AgentrySettings.getInstance().defaultInstallTarget
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
              list                                    list available skills
              installed [--target T]                  list installed skills at target T
              install <name> [--target T]             install a skill
              remove  <name> [--target T]             uninstall a skill
              refresh                                 fetch all enabled registries

            Targets: ${enumValues<InstallTarget>().joinToString { it.name }}
        """.trimIndent())
    }
}
