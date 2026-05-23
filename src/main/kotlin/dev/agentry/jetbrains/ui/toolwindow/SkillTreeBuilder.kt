package dev.agentry.jetbrains.ui.toolwindow

import com.intellij.openapi.diagnostic.logger
import dev.agentry.jetbrains.install.PluginInstallState
import dev.agentry.jetbrains.install.SkillInstaller
import dev.agentry.jetbrains.model.ComponentKind
import dev.agentry.jetbrains.model.InstallTarget
import dev.agentry.jetbrains.model.ManifestDialect
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginEntry
import dev.agentry.jetbrains.model.PluginManifest
import dev.agentry.jetbrains.model.RegistrySource
import dev.agentry.jetbrains.registry.MarketplaceParser
import dev.agentry.jetbrains.registry.PluginManifestParser
import dev.agentry.jetbrains.registry.PluginScanner
import dev.agentry.jetbrains.registry.PluginSourceResolver
import dev.agentry.jetbrains.registry.RegistryManager
import dev.agentry.jetbrains.settings.AgentrySettings
import java.io.File

/**
 * Builds the tree shown in the tool window. Each registry is interrogated to decide which
 * shape of contents it has, in this priority:
 *
 *   1. **Marketplace**: a `marketplace.json` at one of the two canonical paths. Every
 *      `plugins[*]` becomes a `Plugin` child; component groups hang off the plugin.
 *   2. **Single plugin**: a `plugin.json` at the registry root (no marketplace catalog).
 *      The registry contains exactly one `Plugin` child.
 *   3. **Legacy flat skills**: the original `ManifestParser`-shaped registry. Skills are
 *      direct children of the `Registry` node.
 *
 * The tree builder runs on a background thread; nodes are constructed off-EDT and the
 * caller hands them to the UI via `invokeLater`.
 */
object SkillTreeBuilder {

    private val log = logger<SkillTreeBuilder>()
    private val marketplaceParser = MarketplaceParser()
    private val pluginManifestParser = PluginManifestParser()
    private val pluginScanner = PluginScanner()

    fun build(target: InstallTarget, projectBasePath: String?): AgentryNode.Root {
        val settings = AgentrySettings.getInstance()
        val sources = settings.registrySources.map {
            RegistrySource(it.url, it.ref, it.enabled, it.displayName)
        }
        val registry = RegistryManager.getInstance()
        val enabled = sources.filter { it.enabled }
        // Force-clone any enabled source so we can inspect its on-disk layout.
        registry.fetchAll(enabled)

        val installer = SkillInstaller.getInstance()
        val installedSkillNames = installer.listInstalled(target, projectBasePath)
            .map { it.manifest.name }.toSet()

        val root = AgentryNode.Root()
        sources.forEach { source ->
            val cloneDir = registry.localDirFor(source)
            val registryNode = buildRegistryNode(source, cloneDir, installedSkillNames, projectBasePath)
            root.add(registryNode)
        }

        addOrphans(root, installer.listInstalled(target, projectBasePath).map { it }, sources, target, projectBasePath)
        return root
    }

    private fun buildRegistryNode(
        source: RegistrySource,
        cloneDir: File,
        installedSkillNames: Set<String>,
        projectBasePath: String?
    ): AgentryNode.Registry {
        // 1. Marketplace catalog?
        val marketplace = marketplaceParser.parse(cloneDir)
        if (marketplace != null) {
            val parsed = marketplace.getOrNull()
            if (parsed == null) {
                log.warn("marketplace.json parse failed for ${source.url}: ${marketplace.exceptionOrNull()?.message}")
                return registryWithStatus(source, RegistryStatus.UNREACHABLE, 0)
            }
            val node = registryWithStatus(source, RegistryStatus.OK, parsed.plugins.size)
            parsed.plugins.forEach { entry ->
                buildPluginNodeFromEntry(entry, cloneDir, parsed.metadata?.pluginRoot, projectBasePath)
                    ?.let { node.add(it) }
            }
            return node
        }

        // 2. Single plugin at the registry root?
        val pluginManifestResult = pluginManifestParser.parse(cloneDir)
        if (pluginManifestResult.isSuccess) {
            val plugin = pluginManifestResult.getOrThrow()
            if (plugin.dialect != ManifestDialect.DIRNAME_ONLY) {
                val node = registryWithStatus(source, RegistryStatus.OK, 1)
                buildPluginNodeFromManifest(plugin, projectBasePath)?.let { node.add(it) }
                return node
            }
        }

        // 3. Legacy: flat skill registry.
        val manifests = RegistryManager.getInstance().getCached(source)
        val status = when {
            manifests.isEmpty() -> RegistryStatus.EMPTY
            else -> RegistryStatus.OK
        }
        val node = registryWithStatus(source, status, manifests.size)
        manifests.forEach { m ->
            node.add(AgentryNode.Skill(m, installed = m.name in installedSkillNames))
        }
        return node
    }

    private fun registryWithStatus(source: RegistrySource, status: RegistryStatus, count: Int): AgentryNode.Registry {
        val effectiveStatus = if (!source.enabled) RegistryStatus.DISABLED else status
        return AgentryNode.Registry(source, effectiveStatus, count)
    }

    /** Resolve a plugin entry from a marketplace catalog into its on-disk plugin root. */
    private fun buildPluginNodeFromEntry(
        entry: PluginEntry,
        registryRoot: File,
        pluginRoot: String?,
        projectBasePath: String?
    ): AgentryNode.Plugin? {
        val resolved = PluginSourceResolver.getInstance()
            .resolve(entry.source, registryRoot, pluginRoot)
            .getOrElse { e ->
                log.warn("Could not resolve plugin '${entry.name}' source: ${e.message}")
                return null
            }
        val manifestResult = pluginManifestParser.parse(resolved)
        val manifest = manifestResult.getOrElse { e ->
            log.warn("Could not parse plugin.json for '${entry.name}': ${e.message}")
            return null
        }
        return buildPluginNodeFromManifest(manifest, projectBasePath)
    }

    private fun buildPluginNodeFromManifest(
        manifest: PluginManifest,
        projectBasePath: String?
    ): AgentryNode.Plugin? {
        val components = pluginScanner.scan(manifest)
        val plugin = AgentryNode.Plugin(manifest, components.size)
        groupComponentsByKind(components, manifest, projectBasePath).forEach { group ->
            plugin.add(group)
        }
        return plugin
    }

    private fun groupComponentsByKind(
        components: List<PluginComponent>,
        manifest: PluginManifest,
        projectBasePath: String?
    ): List<AgentryNode.ComponentGroup> {
        val byKind: Map<ComponentKind, List<PluginComponent>> = components
            .groupBy { it.kind }
            .toSortedMap(compareBy { it.ordinal })
        return byKind.map { (kind, items) ->
            val group = AgentryNode.ComponentGroup(kind, items.size)
            items.forEach { c ->
                val installed = PluginInstallState.isInstalled(c, manifest, projectBasePath)
                group.add(AgentryNode.Component(c, kind, installed))
            }
            group
        }
    }

    /**
     * Surface skills that are installed on disk but whose source registry is no longer
     * present. Carried over from the original `SkillTreeBuilder` so disabling a registry
     * doesn't strand the user's skills.
     */
    private fun addOrphans(
        root: AgentryNode.Root,
        installed: List<dev.agentry.jetbrains.model.InstalledSkill>,
        sources: List<RegistrySource>,
        target: InstallTarget,
        projectBasePath: String?
    ) {
        val registry = RegistryManager.getInstance()
        val knownNames: Set<String> = sources.asSequence()
            .flatMap { registry.getCached(it).asSequence() }
            .map { it.name }
            .toSet()
        val orphans = installed.filter { it.manifest.name !in knownNames }
        if (orphans.isEmpty()) return
        val group = AgentryNode.OrphanGroup(orphans.size)
        orphans.forEach { group.add(AgentryNode.Orphan(it)) }
        root.add(group)
    }

}
