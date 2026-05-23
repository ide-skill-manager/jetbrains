package dev.agentry.jetbrains.ui.toolwindow

import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.ui.toolwindow.AgentryNode
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.ui.toolwindow.RegistryStatus
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.model.SkillManifest
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings

/**
 * Builds the tree shown in the tool window from the current settings + on-disk install state.
 *
 * One registry header per configured source (enabled or not). Skills the user has installed
 * but whose registry has been removed surface under a synthetic *orphan* group so they can
 * still be uninstalled from the UI.
 */
object SkillTreeBuilder {

    /** Live fetch. Network IO inside `fetchAll`; call from a background task. */
    fun build(
        target: InstallTarget,
        projectBasePath: String?
    ): AgentryNode.Root {
        val settings = AgentrySettings.getInstance()
        val sourcesStates = settings.registrySources.toList()
        val sources = sourcesStates.map {
            RegistrySource(it.url, it.ref, it.enabled, it.displayName)
        }
        val enabled = sources.filter { it.enabled }
        val fetched = RegistryManager.getInstance().fetchAll(enabled)
        val cached: Map<RegistrySource, List<SkillManifest>> =
            sources.filter { !it.enabled }.associateWith { RegistryManager.getInstance().getCached(it) }
        val allByName = (fetched.values.flatten() + cached.values.flatten())
            .associateBy { it.name }
        val installed = SkillInstaller.getInstance().listInstalled(target, projectBasePath)
        val installedNames = installed.map { it.manifest.name }.toSet()

        val root = AgentryNode.Root()
        sources.forEach { source ->
            val manifests = fetched[source] ?: cached[source] ?: emptyList()
            val status = when {
                !source.enabled -> RegistryStatus.DISABLED
                fetched.containsKey(source) && manifests.isEmpty() -> RegistryStatus.EMPTY
                !fetched.containsKey(source) -> RegistryStatus.UNREACHABLE
                else -> RegistryStatus.OK
            }
            val registryNode = AgentryNode.Registry(source, status, manifests.size)
            manifests.forEach { m ->
                registryNode.add(AgentryNode.Skill(m, installed = m.name in installedNames))
            }
            root.add(registryNode)
        }

        val orphanInstalls = installed.filter { it.manifest.name !in allByName }
        if (orphanInstalls.isNotEmpty()) {
            val group = AgentryNode.OrphanGroup(orphanInstalls.size)
            orphanInstalls.forEach { group.add(AgentryNode.Orphan(it)) }
            root.add(group)
        }
        return root
    }
}
