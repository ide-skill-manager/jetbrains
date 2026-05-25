# Install-target picker + scope-aware tool window — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `ComponentActions` from hardcoding `InstallScope.Project`; add a tool-window picker so the user chooses Project vs User per install; show install location on each row as a compact `[U]`/`[P]`/`[U][P]` badge.

**Architecture:** `InstallTarget` enum trims to two values that map cleanly onto `InstallScope` via a new `toScope()`. The boolean `installed` flag on tree nodes becomes `installedScopes: Set<InstallScope>` populated by a new `PluginInstallState.locationsOf()`. The tool window grows a footer combo box whose selection is stuffed into the action `DataContext` under a typed `INSTALL_TARGET_DATA_KEY`; both install pipelines (`ComponentActions`, `BatchOperations`) read it with a fall-back to `AgentrySettings.defaultInstallTarget` for non-UI callers.

**Tech Stack:** Kotlin 2.0, IntelliJ Platform Gradle Plugin 2.2.1, JUnit 4 (`BasePlatformTestCase`), Swing for the picker (`com.intellij.openapi.ui.ComboBox`).

---

## File structure

**Modified:**
- `src/main/kotlin/dev/agentry/jetbrains/model/InstallTarget.kt` — trim enum, add `toScope()`
- `src/main/kotlin/dev/agentry/jetbrains/util/AgentryPaths.kt` — remove `agentrySkillsRoot`
- `src/main/kotlin/dev/agentry/jetbrains/settings/AgentrySettings.kt` — flip default, add unknown-value coercion in `loadState`
- `src/main/kotlin/dev/agentry/jetbrains/install/PluginInstallState.kt` — replace `isInstalled` with `locationsOf`
- `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTreeNodes.kt` — `Skill`/`Component` `installed: Boolean` → `installedScopes: Set<InstallScope>`; add `installedScopes` on `Orphan`
- `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTreeBuilder.kt` — call `locationsOf`, populate `installedScopes`
- `src/main/kotlin/dev/agentry/jetbrains/actions/AgentryDataKeys.kt` — add `INSTALL_TARGET_DATA_KEY`
- `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/AgentryToolWindowPanel.kt` — footer picker, `DataContext` wiring, picker-aware button counts
- `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTree.kt` — renderer: badge after name, `[U]`/`[P]`/`[U][P]` tags, 120-char cap on `describeComponent`
- `src/main/kotlin/dev/agentry/jetbrains/actions/ComponentActions.kt` — read picker via `INSTALL_TARGET_DATA_KEY`
- `src/main/kotlin/dev/agentry/jetbrains/install/BatchOperations.kt` — same
- `src/main/kotlin/dev/agentry/jetbrains/cli/AgentryStarter.kt` — error message + usage update
- `src/main/kotlin/dev/agentry/jetbrains/sync/ProjectSyncService.kt` — `log.warn` on unknown yaml target
- `src/main/resources/META-INF/plugin.xml` — `<change-notes>` 0.1.2 entry
- `build.gradle.kts` — `version` 0.1.1 → 0.1.2 (two places)

**New:**
- `src/test/kotlin/dev/agentry/jetbrains/SkillTreeBuilderTest.kt`

**Tests extended:** `AgentrySettingsRoundTripTest.kt`, `PluginInstallerTest.kt`

---

## Task 1: Trim `InstallTarget` to two values and add `toScope()`

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/model/InstallTarget.kt`
- Modify: `src/main/kotlin/dev/agentry/jetbrains/util/AgentryPaths.kt`
- Test: `src/test/kotlin/dev/agentry/jetbrains/InstallTargetTest.kt` *(new file)*

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/agentry/jetbrains/InstallTargetTest.kt`:

```kotlin
package dev.agentry.jetbrains

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.model.InstallTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstallTargetTest {

    @Test fun `enum holds only CLAUDE_USER and CLAUDE_PROJECT`() {
        val names = enumValues<InstallTarget>().map { it.name }.toSet()
        assertEquals(setOf("CLAUDE_USER", "CLAUDE_PROJECT"), names)
    }

    @Test fun `toScope CLAUDE_USER maps to Global regardless of project path`() {
        assertEquals(InstallScope.Global, InstallTarget.CLAUDE_USER.toScope(null))
        assertEquals(InstallScope.Global, InstallTarget.CLAUDE_USER.toScope("/tmp/proj"))
    }

    @Test fun `toScope CLAUDE_PROJECT maps to Project with the given path`() {
        val scope = InstallTarget.CLAUDE_PROJECT.toScope("/tmp/proj")
        assertTrue(scope is InstallScope.Project)
        assertEquals(File("/tmp/proj"), (scope as InstallScope.Project).projectDir)
    }

    @Test(expected = IllegalStateException::class)
    fun `toScope CLAUDE_PROJECT with null path errors`() {
        InstallTarget.CLAUDE_PROJECT.toScope(null)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.InstallTargetTest
```

Expected: compile failure (`Unresolved reference: toScope`) AND `enum holds only…` failure listing AGENTRY_CACHE + JUNIE_PROJECT in the actual set.

- [ ] **Step 3: Rewrite `InstallTarget.kt`**

Replace the entire file content with:

```kotlin
package dev.agentry.jetbrains.model

import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.util.InputValidation
import java.io.File

/**
 * Where a skill / component is written on disk. Two scopes:
 *
 *   - [CLAUDE_USER]    `~/.claude/skills/<name>/`         (default)
 *   - [CLAUDE_PROJECT] `<project>/.claude/skills/<name>/`
 *
 * The newer plugin-component pipeline routes via [toScope], which maps the enum onto the
 * sealed [InstallScope] used by `InstallPaths.destinationsFor`. The legacy single-skill
 * pipeline (`SkillInstaller`) consumes [baseDir] / [resolvePath] directly.
 */
enum class InstallTarget(val displayName: String) {
    CLAUDE_USER("~/.claude/ (user)"),
    CLAUDE_PROJECT("<project>/.claude/ (project)");

    /** Bridge to the sealed [InstallScope] used by the plugin-component install pipeline. */
    fun toScope(projectBasePath: String?): InstallScope = when (this) {
        CLAUDE_USER -> InstallScope.Global
        CLAUDE_PROJECT -> InstallScope.Project(
            File(projectBasePath ?: error("CLAUDE_PROJECT requires a project base path"))
        )
    }

    /** Root directory containing all skills installed at this target. */
    fun baseDir(projectBasePath: String?): File? = when (this) {
        CLAUDE_USER -> File(System.getProperty("user.home"), ".claude/skills")
        CLAUDE_PROJECT -> projectBasePath?.let { File(it, ".claude/skills") }
    }

    /**
     * Resolve the install directory for [skillName]. Validates the name and verifies the
     * resolved path stays inside [baseDir] — guards against `name = "../../etc/passwd"`.
     */
    fun resolvePath(projectBasePath: String?, skillName: String): File {
        require(InputValidation.isValidSkillName(skillName)) {
            "Invalid skill name: '$skillName'"
        }
        val base = baseDir(projectBasePath)
            ?: error("$name requires a project base path")
        val dest = File(base, skillName)
        require(InputValidation.isInsideDir(dest, base)) {
            "Resolved path '${dest.absolutePath}' escapes install root '${base.absolutePath}'"
        }
        return dest
    }
}
```

- [ ] **Step 4: Remove `agentrySkillsRoot` from `AgentryPaths.kt`**

Replace `AgentryPaths.kt` content with:

```kotlin
package dev.agentry.jetbrains.util

import java.io.File

/**
 * Single source of truth for on-disk locations Agentry uses. Previously these were duplicated
 * across `RegistryManager`, `SkillInstaller`, and `InstallTarget`; consolidating prevents
 * silent drift if one location changes.
 */
object AgentryPaths {

    private val userHome: File get() = File(System.getProperty("user.home"))

    /** Registry working copies live here, one subdirectory per source. */
    val registryCacheRoot: File get() = File(userHome, ".agentry/cache")
}
```

- [ ] **Step 5: Run the new test, expect pass**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.InstallTargetTest
```

Expected: all 4 tests pass.

- [ ] **Step 6: Run the full test suite, fix any callers that referenced dropped enum values**

```bash
./gradlew --no-daemon test
```

Likely callers needing patches: any test referencing `InstallTarget.AGENTRY_CACHE` or `InstallTarget.JUNIE_PROJECT`. Replace with `InstallTarget.CLAUDE_USER` (the closest equivalent). If `AgentryPaths.agentrySkillsRoot` is referenced anywhere outside the file we just edited, update those call sites (likely just `InstallTarget.AGENTRY_CACHE.baseDir` which is now deleted — should already be gone after Step 3).

Expected after fixes: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/model/InstallTarget.kt \
        src/main/kotlin/dev/agentry/jetbrains/util/AgentryPaths.kt \
        src/test/kotlin/dev/agentry/jetbrains/InstallTargetTest.kt
git commit -m "Trim InstallTarget to CLAUDE_USER + CLAUDE_PROJECT; add toScope()

Removes AGENTRY_CACHE and JUNIE_PROJECT. New toScope() bridges the
enum onto the sealed InstallScope used by InstallPaths."
```

---

## Task 2: Flip Settings default to `CLAUDE_USER`; coerce unknown persisted values

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/settings/AgentrySettings.kt`
- Test: `src/test/kotlin/dev/agentry/jetbrains/AgentrySettingsRoundTripTest.kt`

- [ ] **Step 1: Add failing test cases**

Open `src/test/kotlin/dev/agentry/jetbrains/AgentrySettingsRoundTripTest.kt` and append (inside the existing test class — no new file):

```kotlin
fun testDefaultInstallTargetIsClaudeUser() {
    val freshState = AgentrySettings.State()
    assertEquals("CLAUDE_USER", freshState.defaultInstallTarget)
}

fun testLoadStateCoercesUnknownTargetToClaudeUser() {
    val settings = AgentrySettings()
    val stale = AgentrySettings.State().apply { defaultInstallTarget = "AGENTRY_CACHE" }
    settings.loadState(stale)
    assertEquals("CLAUDE_USER", settings.state.defaultInstallTarget)
}

fun testLoadStateKeepsKnownTarget() {
    val settings = AgentrySettings()
    val state = AgentrySettings.State().apply { defaultInstallTarget = "CLAUDE_PROJECT" }
    settings.loadState(state)
    assertEquals("CLAUDE_PROJECT", settings.state.defaultInstallTarget)
}
```

(If `settings.state` is private, expose via the existing public `getState()` method instead. Confirm with `grep -n "fun getState\|var state\|private var state" src/main/kotlin/dev/agentry/jetbrains/settings/AgentrySettings.kt` first.)

- [ ] **Step 2: Run the new tests, expect fail**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.AgentrySettingsRoundTripTest
```

Expected: `testDefaultInstallTargetIsClaudeUser` fails (default is still `CLAUDE_PROJECT`); `testLoadStateCoercesUnknownTargetToClaudeUser` fails (no coercion yet).

- [ ] **Step 3: Update `AgentrySettings.kt`**

Two changes — the `State` data class default and the `loadState` body:

```kotlin
data class State(
    var registrySources: MutableList<RegistrySourceState> = mutableListOf(),
    var defaultInstallTarget: String = InstallTarget.CLAUDE_USER.name,  // was CLAUDE_PROJECT
    var autoSyncOnOpen: Boolean = false,
    var trustedProjects: MutableSet<String> = mutableSetOf()
)
```

And `loadState`:

```kotlin
override fun loadState(state: State) {
    this.state = state
    // Coerce values persisted by older versions (AGENTRY_CACHE, JUNIE_PROJECT) — both
    // removed in 0.1.2. Any unrecognised value falls back to the current default so the
    // settings panel never shows an enum the combo can't display.
    if (enumValues<InstallTarget>().none { it.name == state.defaultInstallTarget }) {
        state.defaultInstallTarget = InstallTarget.CLAUDE_USER.name
    }
}
```

- [ ] **Step 4: Run the new tests, expect pass**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.AgentrySettingsRoundTripTest
```

Expected: all tests pass (including pre-existing round-trip tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/settings/AgentrySettings.kt \
        src/test/kotlin/dev/agentry/jetbrains/AgentrySettingsRoundTripTest.kt
git commit -m "Default install target → CLAUDE_USER; coerce removed enum values

Existing installs that persisted AGENTRY_CACHE / JUNIE_PROJECT in
agentry.xml are silently normalised to CLAUDE_USER on load."
```

---

## Task 3: Replace `PluginInstallState.isInstalled` with `locationsOf`

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/install/PluginInstallState.kt`
- Test: append to `src/test/kotlin/dev/agentry/jetbrains/PluginInstallerTest.kt` *(or create a new `PluginInstallStateTest.kt` if you prefer; this plan uses the existing file)*

- [ ] **Step 1: Add failing tests**

Append to `PluginInstallerTest.kt` (inside the existing test class):

```kotlin
fun testLocationsOfEmptyWhenNothingInstalled() {
    val (root, projectDir) = newPluginAndProject("loc-empty")
    val manifest = manifest(root, "loc-empty")
    val component = PluginComponent.Skill(
        name = "untouched",
        skillDir = File(root, "skills/untouched"),
        skillFile = File(root, "skills/untouched/SKILL.md"),
        bundledFiles = emptyList()
    )
    val locs = PluginInstallState.locationsOf(component, manifest, projectDir.absolutePath)
    assertTrue("expected empty, got $locs", locs.isEmpty())
}

fun testLocationsOfReportsProjectAfterProjectInstall() {
    val (root, projectDir) = newPluginAndProject("loc-proj")
    File(root, "skills/here").mkdirs()
    File(root, "skills/here/SKILL.md").writeText("---\nname: here\n---\n")
    val manifest = manifest(root, "loc-proj")
    val components = listOf(
        PluginComponent.Skill("here", File(root, "skills/here"),
            File(root, "skills/here/SKILL.md"), emptyList())
    )
    PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
    val locs = PluginInstallState.locationsOf(components.first(), manifest, projectDir.absolutePath)
    assertEquals(setOf<InstallScope>(InstallScope.Project(projectDir)), locs)
}
```

- [ ] **Step 2: Run the new tests, expect fail**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.PluginInstallerTest.testLocationsOfEmptyWhenNothingInstalled \
                          --tests dev.agentry.jetbrains.PluginInstallerTest.testLocationsOfReportsProjectAfterProjectInstall
```

Expected: compile failure (`Unresolved reference: locationsOf`).

- [ ] **Step 3: Rewrite `PluginInstallState.kt`**

```kotlin
package dev.agentry.jetbrains.install

import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Cheap on-disk check of where a plugin component is currently installed.
 *
 * The tree builder uses [locationsOf] to render the `[U]` / `[P]` / `[U][P]` badge.
 * Symlink-aware: a symlinked destination doesn't count — we want a true positive so the
 * badge can't be spoofed and uninstall can't be tricked into walking out of the install root.
 *
 * Tracks the *primary* destination per scope only (`destFor`, not all of `destinationsFor`).
 * Partial dual-write loss (user manually deleted one of the two `.github/` + `.claude/`
 * agent files) leaves the install considered present as long as the primary survives —
 * documented in `docs/plans/install-target-picker.md`.
 */
object PluginInstallState {

    /**
     * The set of scopes [component] is currently installed in. Empty when not installed anywhere;
     * one-element set when only project or user; two-element set when both.
     */
    fun locationsOf(
        component: PluginComponent,
        plugin: PluginManifest,
        projectBasePath: String?
    ): Set<InstallScope> {
        val scopes = buildList<InstallScope> {
            projectBasePath?.let { add(InstallScope.Project(File(it))) }
            add(InstallScope.Global)
        }
        return scopes.filterTo(mutableSetOf()) { scope ->
            val primary = InstallPaths.destFor(component, plugin, scope).toPath()
            Files.exists(primary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(primary)
        }
    }
}
```

- [ ] **Step 4: Run the new tests, expect pass**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.PluginInstallerTest.testLocationsOfEmptyWhenNothingInstalled \
                          --tests dev.agentry.jetbrains.PluginInstallerTest.testLocationsOfReportsProjectAfterProjectInstall
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/install/PluginInstallState.kt \
        src/test/kotlin/dev/agentry/jetbrains/PluginInstallerTest.kt
git commit -m "PluginInstallState.locationsOf: Set<InstallScope> instead of Boolean

Foundation for the per-scope install badge ([U]/[P]/[U][P]) the tool
window will render. Symlink-aware; tracks primary destination only."
```

---

## Task 4: Tree nodes carry `installedScopes`; builder populates them

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTreeNodes.kt`
- Modify: `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTreeBuilder.kt`
- Modify: `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTree.kt` *(only the readers of `.installed`)*
- Modify: `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/AgentryToolWindowPanel.kt` *(only the readers of `.installed`)*

- [ ] **Step 1: Update `SkillTreeNodes.kt`**

Find the three classes (`Skill`, `Component`, `Orphan`) and replace as follows:

```kotlin
class Skill(
    val manifest: SkillManifest,
    var installedScopes: Set<dev.agentry.jetbrains.install.InstallScope>
) : AgentryNode(manifest) {
    val name: String get() = manifest.name
    val installed: Boolean get() = installedScopes.isNotEmpty()
}
```

```kotlin
class Component(
    val component: PluginComponent,
    val kind: ComponentKind,
    var installedScopes: Set<dev.agentry.jetbrains.install.InstallScope>
) : AgentryNode(component) {
    val name: String get() = component.name
    val installed: Boolean get() = installedScopes.isNotEmpty()
}
```

```kotlin
class Orphan(
    val installed: InstalledSkill,
    val installedScopes: Set<dev.agentry.jetbrains.install.InstallScope>
) : AgentryNode(installed) {
    val name: String get() = installed.manifest.name
    val location: File get() = installed.location
}
```

Notes:
- Keep the existing `installed: Boolean` *getter* on `Skill` and `Component` (it's the same data, derived); no other reader needs to change.
- `Orphan.installed` was already the `InstalledSkill` instance (not a boolean) — different name collision is fine because the new field is `installedScopes`, not `installed`.

- [ ] **Step 2: Update `SkillTreeBuilder.kt` to populate `installedScopes`**

Three call sites change. The legacy flat-skill branch (around the end of `buildRegistryNode`) currently does:

```kotlin
node.add(AgentryNode.Skill(m, installed = m.name in installedSkillNames))
```

Replace with:

```kotlin
node.add(
    AgentryNode.Skill(
        m,
        installedScopes = if (m.name in installedSkillNames) setOf(InstallScope.Project(File(projectBasePath ?: ""))) else emptySet()
    )
)
```

*(Legacy SkillInstaller only writes to the configured target — we surface "project" if the project-scope install is detected. For now, this branch doesn't dual-scope. Future work could enrich.)*

In `groupComponentsByKind`, replace:

```kotlin
val installed = PluginInstallState.isInstalled(c, manifest, projectBasePath)
group.add(AgentryNode.Component(c, kind, installed))
```

with:

```kotlin
val locs = PluginInstallState.locationsOf(c, manifest, projectBasePath)
group.add(AgentryNode.Component(c, kind, installedScopes = locs))
```

In `addOrphans`, replace:

```kotlin
orphans.forEach { group.add(AgentryNode.Orphan(it)) }
```

with:

```kotlin
orphans.forEach { orphan ->
    // Orphans came from disk; we know which scope they're at via their `location`.
    val scope: InstallScope = if (projectBasePath != null && orphan.location.absolutePath.startsWith(projectBasePath))
        InstallScope.Project(File(projectBasePath))
    else InstallScope.Global
    group.add(AgentryNode.Orphan(orphan, installedScopes = setOf(scope)))
}
```

Add the import: `import dev.agentry.jetbrains.install.InstallScope` and `import java.io.File`.

- [ ] **Step 3: Fix all other readers of `.installed`**

Run:

```bash
grep -rn "\.installed\b" src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/ src/main/kotlin/dev/agentry/jetbrains/actions/
```

Expected hits, all of which can keep using `.installed` (the derived getter we added) without modification:
- `AgentryToolWindowPanel.kt` — uses `.installed` in `installButton`/`uninstallButton` listeners
- `SkillTree.kt` — `renderSkill` / `renderComponent` / `renderOrphan` read `.installed` for the badge

No source changes needed for these — the derived `installed: Boolean` getter on `Skill`/`Component` preserves the call signature. `Orphan` always had `.installed` as `InstalledSkill`; the new `installedScopes` field is additive.

- [ ] **Step 4: Build to confirm compile**

```bash
./gradlew --no-daemon compileKotlin
```

Expected: SUCCESS.

- [ ] **Step 5: Run full test suite**

```bash
./gradlew --no-daemon test
```

Expected: all existing tests still pass — this task is internal refactor that preserves public behaviour.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTreeNodes.kt \
        src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTreeBuilder.kt
git commit -m "Tree nodes carry installedScopes: Set<InstallScope>

Skill / Component / Orphan all gain installedScopes; .installed stays
as a derived boolean so existing readers keep compiling. Builder uses
PluginInstallState.locationsOf for component scopes; orphans derive
scope from their on-disk location."
```

---

## Task 5: Add `INSTALL_TARGET_DATA_KEY`

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/actions/AgentryDataKeys.kt`

- [ ] **Step 1: Add the typed key**

Append to `AgentryDataKeys.kt`, alongside the other `DataKey` declarations:

```kotlin
/**
 * The user's currently-selected install target from the tool-window picker. Actions
 * route their `InstallScope` decision through this when set, falling back to
 * `AgentrySettings.defaultInstallTarget` for CLI / agent-fired paths that never populate
 * the data context.
 */
val INSTALL_TARGET_DATA_KEY: DataKey<dev.agentry.jetbrains.model.InstallTarget> =
    DataKey.create("AgentryInstallTarget")
```

- [ ] **Step 2: Build to confirm compile**

```bash
./gradlew --no-daemon compileKotlin
```

Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/actions/AgentryDataKeys.kt
git commit -m "Add INSTALL_TARGET_DATA_KEY for action-routed install target

Producers (tool window) set it; consumers (ComponentActions,
BatchOperations) read it with a fallback to settings default."
```

---

## Task 6: `ComponentActions` reads picker target (the bug fix)

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/actions/ComponentActions.kt`
- Test: append to `src/test/kotlin/dev/agentry/jetbrains/PluginInstallerTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `PluginInstallerTest.kt`:

```kotlin
fun testComponentActionRespectsClaudeUserTargetEvenWithOpenProject() {
    val (root, projectDir) = newPluginAndProject("respect-user")
    File(root, "skills/picked").mkdirs()
    File(root, "skills/picked/SKILL.md").writeText("---\nname: picked\n---\n")
    val manifest = manifest(root, "respect-user")
    val component = PluginComponent.Skill(
        "picked",
        File(root, "skills/picked"),
        File(root, "skills/picked/SKILL.md"),
        emptyList()
    )

    // Simulate the tool window stuffing CLAUDE_USER into the data context.
    val dataContext = com.intellij.openapi.actionSystem.DataContext { id ->
        when (id) {
            com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> project
            dev.agentry.jetbrains.actions.SELECTED_COMPONENTS_DATA_KEY.name ->
                listOf(dev.agentry.jetbrains.ui.toolwindow.AgentryNode.Component(
                    component, dev.agentry.jetbrains.model.ComponentKind.SKILL, emptySet()
                ).also { /* parent-less Component won't resolve manifest; we test runComponentOp directly */ })
            dev.agentry.jetbrains.actions.INSTALL_TARGET_DATA_KEY.name ->
                dev.agentry.jetbrains.model.InstallTarget.CLAUDE_USER
            else -> null
        }
    }
    // Direct invocation of the scope-resolution logic — calling the action fully would also
    // exercise the tree-parent lookup which the synthetic Component above lacks.
    // (Implementation tip: extract the scope-resolution into a top-level/internal helper so
    // this test can call it cleanly without setting up an AgentryNode.Plugin parent.)
    val resolved = dev.agentry.jetbrains.actions.resolveInstallScope(dataContext, project.basePath)
    assertEquals(dev.agentry.jetbrains.install.InstallScope.Global, resolved)
}
```

*(This test calls a helper `resolveInstallScope` that doesn't exist yet — Step 3 extracts it for testability.)*

- [ ] **Step 2: Run the test, expect fail**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.PluginInstallerTest.testComponentActionRespectsClaudeUserTargetEvenWithOpenProject
```

Expected: compile failure (`Unresolved reference: resolveInstallScope`, `Unresolved reference: INSTALL_TARGET_DATA_KEY` if not yet wired).

- [ ] **Step 3: Extract `resolveInstallScope` and use it in `runComponentOp`**

At the top level of `src/main/kotlin/dev/agentry/jetbrains/actions/ComponentActions.kt`, add:

```kotlin
/**
 * Map the action's [DataContext] to the [InstallScope] the install/uninstall should
 * target. Reads the user's pick from the tool-window combo via [INSTALL_TARGET_DATA_KEY];
 * falls back to `AgentrySettings.defaultInstallTarget` for CLI / agent-fired paths that
 * never set the key. `CLAUDE_PROJECT` with a null project base path falls back to
 * `CLAUDE_USER` to keep the resolution total — should be unreachable from the panel
 * (it disables `Project` in the combo when no project is open) but matters for CLI.
 */
internal fun resolveInstallScope(
    dataContext: com.intellij.openapi.actionSystem.DataContext,
    projectBasePath: String?,
): dev.agentry.jetbrains.install.InstallScope {
    val target = dataContext.getData(INSTALL_TARGET_DATA_KEY)
        ?: dev.agentry.jetbrains.settings.AgentrySettings.getInstance().defaultInstallTarget
    return when (target) {
        dev.agentry.jetbrains.model.InstallTarget.CLAUDE_USER ->
            dev.agentry.jetbrains.install.InstallScope.Global
        dev.agentry.jetbrains.model.InstallTarget.CLAUDE_PROJECT ->
            if (projectBasePath != null) target.toScope(projectBasePath)
            else dev.agentry.jetbrains.install.InstallScope.Global
    }
}
```

Replace `runComponentOp`'s current scope line:

```kotlin
val scope: InstallScope = if (basePath != null) InstallScope.Project(basePath) else InstallScope.Global
```

with:

```kotlin
val scope: InstallScope = resolveInstallScope(e.dataContext, project.basePath)
```

(Where `e` is the `AnActionEvent` passed into the action. If `runComponentOp` doesn't currently take `e` as a parameter, thread it through from both call sites in the action's `actionPerformed`.)

- [ ] **Step 4: Run the test, expect pass**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.PluginInstallerTest.testComponentActionRespectsClaudeUserTargetEvenWithOpenProject
```

Expected: PASS.

- [ ] **Step 5: Run full suite**

```bash
./gradlew --no-daemon test
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/actions/ComponentActions.kt \
        src/test/kotlin/dev/agentry/jetbrains/PluginInstallerTest.kt
git commit -m "ComponentActions: scope from picker, not project presence

Closes the bug where installs landed at project scope even when the
user's settings said CLAUDE_USER. resolveInstallScope reads the new
INSTALL_TARGET_DATA_KEY from the action's DataContext, falling back
to AgentrySettings.defaultInstallTarget for CLI / agent-fired paths."
```

---

## Task 7: `BatchOperations` (legacy SkillInstaller flow) also reads the picker

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/install/BatchOperations.kt`
- Read first to find the current scope/target-resolution code, then patch it the same way.

- [ ] **Step 1: Read `BatchOperations.kt` to find where install target is decided**

```bash
grep -n "defaultInstallTarget\|InstallTarget\|InstallScope" src/main/kotlin/dev/agentry/jetbrains/install/BatchOperations.kt
```

Expected: a line picking the target from `AgentrySettings.getInstance().defaultInstallTarget` directly. We need to give callers a way to override.

- [ ] **Step 2: Change `installByNames` / `uninstallByNames` signatures to accept an override**

If the current signature is:

```kotlin
fun installByNames(project: Project, names: List<String>) { ... }
```

Change to:

```kotlin
fun installByNames(
    project: Project,
    names: List<String>,
    target: InstallTarget = AgentrySettings.getInstance().defaultInstallTarget,
) { ... }
```

And use `target` instead of re-reading the setting inside the body. Same for `uninstallByNames`.

- [ ] **Step 3: Update the two callers in `AgentryActions.kt`**

`InstallSelectedAction.actionPerformed` and `UninstallSelectedAction.actionPerformed` currently call `BatchOperations.getInstance().installByNames(project, names)`. Change to:

```kotlin
val target = e.getData(INSTALL_TARGET_DATA_KEY)
    ?: AgentrySettings.getInstance().defaultInstallTarget
BatchOperations.getInstance().installByNames(project, names, target)
```

(Same change for `UninstallSelectedAction` with `uninstallByNames`.)

- [ ] **Step 4: Build + test**

```bash
./gradlew --no-daemon test
```

Expected: all existing tests pass (no behaviour change in the absence of a set key; defaults match prior behaviour).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/install/BatchOperations.kt \
        src/main/kotlin/dev/agentry/jetbrains/actions/AgentryActions.kt
git commit -m "BatchOperations: explicit target parameter, default to settings

Mirrors ComponentActions — the InstallSelected / UninstallSelected
actions now read the picker and pass it down; non-UI callers keep
the settings default via the parameter default."
```

---

## Task 8: Tool-window footer picker; producer-side `DataContext` wiring; picker-aware button counts

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/AgentryToolWindowPanel.kt`

- [ ] **Step 1: Add picker field, footer rearrangement**

Near the existing button fields, add:

```kotlin
private val installTargetCombo: com.intellij.openapi.ui.ComboBox<dev.agentry.jetbrains.model.InstallTarget> =
    com.intellij.openapi.ui.ComboBox(
        // When no project is open, omit CLAUDE_PROJECT entirely from the model so it can't
        // be selected. Swing cell renderers can style a disabled item but don't prevent
        // selection — filtering the model is the reliable guarantee. If the project opens/
        // closes mid-tool-window-life (rare), the user will see the missing entry until the
        // tool window is reopened; acceptable for v0.1.2.
        if (project.basePath != null) dev.agentry.jetbrains.model.InstallTarget.values()
        else arrayOf(dev.agentry.jetbrains.model.InstallTarget.CLAUDE_USER)
    )
```

In `buildPanel`'s footer construction, change from:

```kotlin
val bottom = JPanel(BorderLayout()).apply {
    val actions = JPanel().apply { add(installButton); add(uninstallButton) }
    add(actions, BorderLayout.WEST)
    add(statusLabel, BorderLayout.CENTER)
}
```

to:

```kotlin
val bottom = JPanel(BorderLayout()).apply {
    val actions = JPanel().apply {
        add(javax.swing.JLabel("Install to: "))
        add(installTargetCombo)
        add(javax.swing.Box.createHorizontalStrut(8))
        add(installButton)
        add(uninstallButton)
    }
    add(actions, BorderLayout.WEST)
    add(statusLabel, BorderLayout.CENTER)
}
```

- [ ] **Step 2: Seed the picker from settings; render display names**

At the bottom of the `init {}` block (after the existing listeners), add:

```kotlin
// Show the friendly displayName (`~/.claude/ (user)`) instead of the enum constant name.
installTargetCombo.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { t ->
    t.displayName
}
// Seed from settings, but only honour it if the model contains that value
// (CLAUDE_PROJECT may have been omitted above when no project is open).
val defaultTarget = AgentrySettings.getInstance().defaultInstallTarget
val model = (0 until installTargetCombo.itemCount).map { installTargetCombo.getItemAt(it) }
installTargetCombo.selectedItem = if (defaultTarget in model) defaultTarget else model.first()
installTargetCombo.addActionListener { updateActionButtonState() }
```

- [ ] **Step 3: Stuff the picker into both `fireAction` and `fireComponentAction` data contexts**

In `fireAction`, change the `DataContext` lambda from:

```kotlin
val dataContext = DataContext { dataId ->
    when (dataId) {
        SELECTED_SKILLS_DATA_KEY.name -> skillNames
        CommonDataKeys.PROJECT.name -> project
        else -> null
    }
}
```

to:

```kotlin
val dataContext = DataContext { dataId ->
    when (dataId) {
        SELECTED_SKILLS_DATA_KEY.name -> skillNames
        CommonDataKeys.PROJECT.name -> project
        dev.agentry.jetbrains.actions.INSTALL_TARGET_DATA_KEY.name -> installTargetCombo.selectedItem
        else -> null
    }
}
```

Same edit in `fireComponentAction`.

- [ ] **Step 4: Picker-aware button counts in `updateActionButtonState`**

Find the current `updateActionButtonState` body (computes `toInstall` / `toUninstall` from `checkedSkills`, `checkedOrphans`, `checkedComponents`). Replace the count computation with picker-aware logic:

```kotlin
private fun updateActionButtonState() {
    val checkedSkills = skillTree.selectedSkills()
    val checkedOrphans = skillTree.selectedOrphans()
    val checkedComponents = skillTree.selectedComponents()
    val pickedTarget = installTargetCombo.selectedItem as? dev.agentry.jetbrains.model.InstallTarget
        ?: AgentrySettings.getInstance().defaultInstallTarget
    val pickedScope = pickedTarget.toScope(project.basePath ?: "")  // safe: CLAUDE_USER ignores the path
    val toInstall =
        checkedSkills.count { pickedScope !in it.installedScopes } +
        checkedComponents.count { pickedScope !in it.installedScopes }
    val toUninstall =
        checkedSkills.count { pickedScope in it.installedScopes } +
        checkedOrphans.size +
        checkedComponents.count { pickedScope in it.installedScopes }
    installButton.text = if (toInstall > 0) "Install selected ($toInstall)" else "Install selected"
    uninstallButton.text = if (toUninstall > 0) "Uninstall selected ($toUninstall)" else "Uninstall selected"
    installButton.isEnabled = toInstall > 0
    uninstallButton.isEnabled = toUninstall > 0
}
```

*(Casting to a guaranteed non-null `InstallTarget` via `?:` covers the case where the combo's `selectedItem` is null between model updates.)*

- [ ] **Step 5: Build + test, then manually verify in sandbox**

```bash
./gradlew --no-daemon test
./gradlew runIde
```

Manual checks in the sandbox IDE:
- Open Agentry tool window → footer shows `Install to: [User ▾] [Install selected] [Uninstall selected]` plus status
- Default selection matches the Settings default
- Switching the picker re-counts on the install/uninstall buttons
- Closing the project leaves the picker on `CLAUDE_USER` (PROJECT entry disabled)

Stop the sandbox after verification.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/AgentryToolWindowPanel.kt
git commit -m "Tool-window footer: install-target picker + picker-aware counts

Combo seeded from AgentrySettings.defaultInstallTarget; selection
written into the action DataContext under INSTALL_TARGET_DATA_KEY;
Install/Uninstall counts now reflect what the picker scope would
change."
```

---

## Task 9: Renderer — badge moves after the name; `[U]` / `[P]` / `[U][P]` tags; description cap on components

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTree.kt`

- [ ] **Step 1: Add a helper for the scope tag**

In `SkillTree.kt`, at the bottom of the file (after `SkillTreeRenderer`), add:

```kotlin
private fun scopeTag(installedScopes: Set<dev.agentry.jetbrains.install.InstallScope>): String = buildString {
    if (installedScopes.any { it is dev.agentry.jetbrains.install.InstallScope.Global }) append("[U]")
    if (installedScopes.any { it is dev.agentry.jetbrains.install.InstallScope.Project }) append("[P]")
}
```

- [ ] **Step 2: Update `renderSkill` to emit the tag immediately after the name**

Replace `renderSkill`'s body with:

```kotlin
private fun renderSkill(node: AgentryNode.Skill) {
    val display = StringUtil.notNullize(node.manifest.displayName.ifBlank { node.manifest.name })
    textRenderer.append(display, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    val tag = scopeTag(node.installedScopes)
    if (tag.isNotEmpty()) {
        textRenderer.append("  $tag", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, successFg()))
    }
    textRenderer.append("  v${node.manifest.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    val desc = node.manifest.description.take(120)
    if (desc.isNotBlank()) {
        textRenderer.append("   ${desc}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
```

- [ ] **Step 3: Update `renderComponent` — same layout, capped description**

Replace `renderComponent`'s body with:

```kotlin
private fun renderComponent(node: AgentryNode.Component) {
    textRenderer.append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    val tag = scopeTag(node.installedScopes)
    if (tag.isNotEmpty()) {
        textRenderer.append("  $tag", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, successFg()))
    }
    val desc = describeComponent(node.component).take(120)
    if (desc.isNotBlank()) {
        textRenderer.append("  $desc", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
```

- [ ] **Step 4: Update `renderOrphan` — show the tag too**

Replace `renderOrphan`'s body with:

```kotlin
private fun renderOrphan(node: AgentryNode.Orphan) {
    textRenderer.append(node.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    val tag = scopeTag(node.installedScopes)
    if (tag.isNotEmpty()) {
        textRenderer.append("  $tag", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, successFg()))
    }
    textRenderer.append("  v${node.installed.manifest.version}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    textRenderer.append(
        "orphaned",
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE)
    )
}
```

- [ ] **Step 5: Manual sandbox verification + commit**

```bash
./gradlew --no-daemon test
./gradlew runIde
```

In the sandbox: open a project, fetch a registry, install some components at different scopes. Confirm rows show `[U]`, `[P]`, `[U][P]` next to the name (not at the end).

Stop sandbox and commit:

```bash
git add src/main/kotlin/dev/agentry/jetbrains/ui/toolwindow/SkillTree.kt
git commit -m "Tree renderer: scope badges after name; cap component description

[U] / [P] / [U][P] tags placed immediately after the name so a long
description can't push the indicator off the right edge. Component
descriptions get the same 120-char cap as skill descriptions."
```

---

## Task 10: New cross-scope tests in `PluginInstallerTest`

**Files:**
- Modify: `src/test/kotlin/dev/agentry/jetbrains/PluginInstallerTest.kt`

- [ ] **Step 1: Add additive-install test**

Append to the test class:

```kotlin
fun testInstallAtUserScopeWhenAlreadyAtProjectEndsUpAtBoth() {
    val (root, projectDir) = newPluginAndProject("both-scope-agent")
    File(root, "agents").mkdirs()
    File(root, "agents/dual.agent.md").writeText("---\nname: dual\ndescription: 'x'\n---\n")
    val components = listOf(
        PluginComponent.Agent("dual", File(root, "agents/dual.agent.md"), description = "x")
    )
    val manifest = manifest(root, "both-scope-agent")

    // First install: Project scope (current default).
    val first = PluginInstaller().installPlugin(manifest, components, InstallScope.Project(projectDir))
    assertTrue("project install OK", first.isFullSuccess)

    // Second install: Global scope. Should NOT remove project files.
    val second = PluginInstaller().installPlugin(manifest, components, InstallScope.Global)
    assertTrue("global install OK", second.isFullSuccess)

    val home = myFixture.tempDirFixture.tempDirPath  // user.home is overridden in setUp()
    assertTrue("project dest still exists",
        File(projectDir, ".github/agents/dual.agent.md").exists())
    assertTrue("project .claude dest still exists",
        File(projectDir, ".claude/agents/dual.agent.md").exists())
    assertTrue("global .copilot dest now exists",
        File(home, ".copilot/agents/both-scope-agent__dual.agent.md").exists())
    assertTrue("global .claude dest now exists",
        File(home, ".claude/agents/both-scope-agent__dual.agent.md").exists())

    // PluginInstallState.locationsOf reports both.
    val locs = PluginInstallState.locationsOf(components.first(), manifest, projectDir.absolutePath)
    assertEquals(setOf(InstallScope.Project(projectDir), InstallScope.Global), locs)
}
```

- [ ] **Step 2: Run new test, expect pass**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.PluginInstallerTest.testInstallAtUserScopeWhenAlreadyAtProjectEndsUpAtBoth
```

Expected: PASS (the install pipeline already supports both scopes; this test pins the behaviour).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/dev/agentry/jetbrains/PluginInstallerTest.kt
git commit -m "Test: install at Global when already at Project lands at both

Pins the additive semantic — installing at a second scope never
removes from the first."
```

---

## Task 11: New `SkillTreeBuilderTest.kt`

**Files:**
- Test: `src/test/kotlin/dev/agentry/jetbrains/SkillTreeBuilderTest.kt` *(new)*

- [ ] **Step 1: Create the test file**

```kotlin
package dev.agentry.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.agentry.jetbrains.install.InstallScope
import dev.agentry.jetbrains.install.PluginInstallState
import dev.agentry.jetbrains.install.installers.InstallPaths
import dev.agentry.jetbrains.model.PluginComponent
import dev.agentry.jetbrains.model.PluginManifest
import java.io.File

class SkillTreeBuilderTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Override user.home so .copilot / .claude global writes land in the test fixture dir.
        System.setProperty("user.home", myFixture.tempDirFixture.tempDirPath)
    }

    fun testLocationsOfReturnsBothScopesWhenInstalledAtBoth() {
        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "proj").apply { mkdirs() }
        val manifest = PluginManifest(name = "p", displayName = "P", version = "1", components = emptyList())
        val component = PluginComponent.Skill(
            name = "double",
            skillDir = File(projectDir, "src/double"),
            skillFile = File(projectDir, "src/double/SKILL.md"),
            bundledFiles = emptyList()
        )

        // Write the primary destinations for both scopes directly so PluginInstallState sees them.
        InstallPaths.destFor(component, manifest, InstallScope.Project(projectDir)).apply {
            parentFile.mkdirs(); writeText("dummy")
        }
        InstallPaths.destFor(component, manifest, InstallScope.Global).apply {
            parentFile.mkdirs(); writeText("dummy")
        }

        val locs = PluginInstallState.locationsOf(component, manifest, projectDir.absolutePath)
        assertEquals(
            setOf<InstallScope>(InstallScope.Project(projectDir), InstallScope.Global),
            locs
        )
    }

    fun testLocationsOfIgnoresSymlinkedDestination() {
        val projectDir = File(myFixture.tempDirFixture.tempDirPath, "proj").apply { mkdirs() }
        val manifest = PluginManifest(name = "p", displayName = "P", version = "1", components = emptyList())
        val component = PluginComponent.Skill(
            name = "linkbomb",
            skillDir = File(projectDir, "src/linkbomb"),
            skillFile = File(projectDir, "src/linkbomb/SKILL.md"),
            bundledFiles = emptyList()
        )
        val target = InstallPaths.destFor(component, manifest, InstallScope.Project(projectDir))
        target.parentFile.mkdirs()
        try {
            java.nio.file.Files.createSymbolicLink(
                target.toPath(),
                File(myFixture.tempDirFixture.tempDirPath, "elsewhere").toPath()
            )
        } catch (_: Throwable) {
            return // FS doesn't allow symlinks; skip
        }
        val locs = PluginInstallState.locationsOf(component, manifest, projectDir.absolutePath)
        assertTrue("symlink must not count: $locs", locs.isEmpty())
    }
}
```

*(If `PluginManifest`'s actual constructor takes more fields, adjust — `grep -n "data class PluginManifest" src/main/kotlin/dev/agentry/jetbrains/model/PluginManifest.kt` will tell you.)*

- [ ] **Step 2: Run, expect pass**

```bash
./gradlew --no-daemon test --tests dev.agentry.jetbrains.SkillTreeBuilderTest
```

Expected: both tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/dev/agentry/jetbrains/SkillTreeBuilderTest.kt
git commit -m "Test: PluginInstallState.locationsOf — both scopes; symlink ignored"
```

---

## Task 12: CLI error message + `ProjectSyncService` warn-on-unknown-target

**Files:**
- Modify: `src/main/kotlin/dev/agentry/jetbrains/cli/AgentryStarter.kt`
- Modify: `src/main/kotlin/dev/agentry/jetbrains/sync/ProjectSyncService.kt`

- [ ] **Step 1: AgentryStarter error message**

Find `parseTarget` (around line 160). The existing error message uses `enumValues<InstallTarget>().joinToString { it.name }`, which already auto-shrinks to `CLAUDE_USER, CLAUDE_PROJECT`. No code change needed — only the manual smoke check below to confirm.

Smoke check:

```bash
echo 'idea agentry install foo --target NUKE_FROM_ORBIT' # what users would type
```

Expected error text when run: `agentry: Unknown --target: NUKE_FROM_ORBIT` (existing message format).

- [ ] **Step 2: `ProjectSyncService` warns on unknown target**

In `ProjectSyncService.syncBlocking`, find:

```kotlin
val target = enumValues<InstallTarget>().firstOrNull { it.name == dep.target }
    ?: settings.defaultInstallTarget
```

Replace with:

```kotlin
val target = enumValues<InstallTarget>().firstOrNull { it.name == dep.target } ?: run {
    if (dep.target.isNotBlank()) {
        log.warn("Skill '${dep.name}' has unknown target '${dep.target}'; using settings default")
    }
    settings.defaultInstallTarget
}
```

(`log` is already a member of `ProjectSyncService` — `private val log = logger<ProjectSyncService>()`.)

- [ ] **Step 3: Build + test**

```bash
./gradlew --no-daemon test
```

Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/agentry/jetbrains/sync/ProjectSyncService.kt
git commit -m "ProjectSyncService: warn when a yaml skill references unknown target

Old yaml configs referencing AGENTRY_CACHE / JUNIE_PROJECT silently
fell back to the settings default. Now log.warn names the unknown
target so users editing .agentry/config.yaml see why their pick
didn't take effect."
```

---

## Task 13: Bump version + change-notes

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Bump `version` in `build.gradle.kts`**

```bash
sed -i '' 's/version = "0.1.1"/version = "0.1.2"/g' build.gradle.kts
grep -n 'version = "' build.gradle.kts
```

Expected: two lines show `version = "0.1.2"` (top-level + `intellijPlatform.pluginConfiguration`).

- [ ] **Step 2: Prepend 0.1.2 change-notes entry to `plugin.xml`**

Inside the existing `<change-notes><![CDATA[ ... ]]></change-notes>` block, prepend:

```xml
<b>0.1.2</b>
<ul>
  <li>Default install target flips from `CLAUDE_PROJECT` to `CLAUDE_USER` — new
      installs land under `~/.claude/` instead of the open project. Existing users
      keep their persisted setting.</li>
  <li>Tool-window footer gains an `Install to: [User ▾]` picker so individual
      install / uninstall clicks can target user OR project scope without
      round-tripping through Settings.</li>
  <li>Tree rows show install location as a compact `[U]` / `[P]` / `[U][P]` badge,
      placed immediately after the name so long descriptions can't push it off the
      right edge.</li>
  <li>Install is additive (installing at one scope never removes from the other);
      uninstall is per-scope.</li>
  <li>`InstallTarget` enum trimmed to `CLAUDE_USER` + `CLAUDE_PROJECT`; persisted
      values for the removed `AGENTRY_CACHE` and `JUNIE_PROJECT` coerce to
      `CLAUDE_USER` on load.</li>
  <li>Fix: `ComponentActions` previously hardcoded `InstallScope.Project` whenever
      a project was open, ignoring the user-target setting entirely. Now reads the
      picker.</li>
</ul>
```

- [ ] **Step 3: Build the distribution**

```bash
./gradlew --no-daemon buildPlugin
ls -la build/distributions/agentry-jetbrains-0.1.2.zip
```

Expected: file exists.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts src/main/resources/META-INF/plugin.xml
git commit -m "0.1.2 — install-target picker; default flips to CLAUDE_USER"
```

---

## Task 14: Open the PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/install-target-picker
```

- [ ] **Step 2: Open PR via `gh`**

```bash
gh pr create --title "0.1.2 — install-target picker + scope-aware tool window" --body "$(cat <<'EOF'
## Summary

Closes the bug a user hit when they tried to install the example skill bundle to `~/.claude/` and the plugin reported success but nothing landed there: `ComponentActions` hardcoded `InstallScope.Project` whenever a project was open. While fixing, ships the picker UX agreed in [`docs/plans/install-target-picker.md`](docs/plans/install-target-picker.md).

### User-visible changes

- Default install target flips `CLAUDE_PROJECT` → `CLAUDE_USER` (new installs; existing settings preserved)
- Footer picker: `Install to: [User ▾] [Install selected (N)] [Uninstall selected (N)]`
- Row badges: `[U]`, `[P]`, `[U][P]`, placed after the name so long descriptions can't push them off-screen
- Install is additive, uninstall is per-scope, button counts are picker-aware
- `InstallTarget` enum trimmed to two values; `AGENTRY_CACHE` and `JUNIE_PROJECT` coerce to `CLAUDE_USER` on load

## Test plan

- [x] `./gradlew test` — all existing + new tests green
- [x] `./gradlew buildPlugin` — produces `agentry-jetbrains-0.1.2.zip`
- [ ] Manual: open Agentry tool window in the sandbox, install a component at User, confirm files appear under `~/.claude/`; install the same component at Project, confirm badge becomes `[U][P]`; uninstall at User, confirm only `~/.claude/` files removed

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Confirm CI starts**

```bash
gh pr checks $(gh pr view --json number --jq .number)
```

Expected: `build` and `Verify plugin` enter `IN_PROGRESS` within ~30s.

---

## Self-review checklist (run after completing all tasks)

- [ ] `./gradlew test` green
- [ ] `./gradlew buildPlugin` succeeds; ZIP at `build/distributions/agentry-jetbrains-0.1.2.zip`
- [ ] Manual sandbox install at User scope lands files under `~/.claude/` (the original bug)
- [ ] Tree row badges reflect actual install state (`[U]` after user install; `[U][P]` after also installing at Project)
- [ ] Install/Uninstall button counts change when the picker is toggled
- [ ] `CLAUDE_PROJECT` is disabled in the picker when no project is open
