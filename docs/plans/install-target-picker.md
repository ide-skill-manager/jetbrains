# Install-target picker + scope-aware tool window

Closes the bug a user hit when installing the example skill bundle: the tool
window said "installed" but nothing landed under `~/.claude/` because the
plugin-component install pipeline hardcoded `InstallScope.Project` when any
project was open, ignoring the user's setting. Same change adds:

- A target picker in the tool window so the user can install at user **or**
  project scope per click, without round-tripping through Settings.
- Per-row badges showing where each item is installed: `[U]`, `[P]`, `[U][P]`,
  or no badge.
- Additive install semantics (no destructive moves) and per-scope uninstall.

Ships as **0.1.2**.

## What the user sees

**Settings — new default.** Default install target changes from `CLAUDE_PROJECT`
to `CLAUDE_USER`. New installs (fresh `agentry.xml`) write to `~/.claude/`
instead of the open project's `.claude/`. Existing users with a persisted
setting keep theirs.

**Settings — enum trimmed.** The combo now holds two values:

- `CLAUDE_USER` — `~/.claude/ (user)` *(default)*
- `CLAUDE_PROJECT` — `<project>/.claude/ (project)`

`AGENTRY_CACHE` and `JUNIE_PROJECT` are dropped from the enum, the Settings
combo, the CLI `--target` allow-list, and the `.agentry/config.yaml` schema's
practical input set. (Persisted or yaml-declared values for the dropped names
coerce to `CLAUDE_USER` — see [Migration](#migration).)

**Tool window — toolbar split.**

```
Header:  [ search… ]                          [+ Add Registry…] [Refresh]
Footer:  Install to: [User ▾]  [Install selected (3)] [Uninstall selected (2)]    Ready
```

The picker is a `ComboBox<InstallTarget>` seeded from
`AgentrySettings.defaultInstallTarget`. The selection lives in
`AgentryToolWindowPanel` state — **not** written back to Settings — so the user
can flip it for one install without changing their global default.

When `project.basePath == null`, `CLAUDE_PROJECT` is omitted from the combo's model
entirely (model filtering is the reliable mechanism in Swing — renderers can style a
disabled item but don't prevent selection). If the project closes mid-session the
combo retains its prior model until the tool window is reopened; acceptable for v0.1.2.

**Tool window — row badges.** Layout becomes:

```
<checkbox> <name>  [scope-tag]  <version|describe>  <description, truncated>
```

Where `[scope-tag]` is `""` (not installed), `"[U]"`, `"[P]"`, or `"[U][P]"`,
rendered in the existing green-success colour. Placing the tag *immediately
after the name* fixes the bug where a long description currently pushes the
indicator off the right edge in `renderComponent`. Description truncation
stays at 120 chars in `renderSkill`; `renderComponent.describeComponent`
adopts the same `text.take(120)` rule so component rows can't sprawl wider
than skill rows.

**Button counts** become picker-aware: `Install selected (N)` counts items in
the selection where the picker's scope is *not* in `installedScopes`;
`Uninstall selected (N)` counts items where it *is*. Disabled when 0.

**Install semantics — additive.** Picker = User on a `[P]`-only item →
install lands at user too → badge becomes `[U][P]`. To strip a scope, switch
the picker and click Uninstall once. No destructive moves; no confirmation
prompts.

## Model + backend

**`InstallTarget` enum.**

```kotlin
enum class InstallTarget(val displayName: String) {
    CLAUDE_USER("~/.claude/ (user)"),
    CLAUDE_PROJECT("<project>/.claude/ (project)");

    fun toScope(projectBasePath: String?): InstallScope = when (this) {
        CLAUDE_USER -> InstallScope.Global
        CLAUDE_PROJECT -> InstallScope.Project(
            File(projectBasePath ?: error("CLAUDE_PROJECT requires a project base path"))
        )
    }

    // baseDir(), resolvePath() — existing methods stay, narrowed to the two cases.
}
```

`AgentryPaths.agentrySkillsRoot` deleted (only `AGENTRY_CACHE` used it). Any
companion references in tests removed.

**Install-state tracking — sets instead of booleans.**

```kotlin
// PluginInstallState.kt — replace isInstalled(c, m, basePath): Boolean with:
fun locationsOf(
    component: PluginComponent,
    manifest: PluginManifest,
    projectBasePath: String?,
): Set<InstallScope>
```

Implementation: iterate `InstallScope.Global` and (if `projectBasePath != null`)
`InstallScope.Project(...)`. For each scope, query
`InstallPaths.destFor(component, manifest, scope)` — the *primary* destination
path (`destinationsFor(...).first()`) — and include the scope in the result if
that path exists on disk. The badge tracks the primary because partial
dual-write loss (user manually deleted one of the dual-write siblings) is a
human-edit edge case; the install is still considered present as long as the
primary survives. Returns an empty set when neither scope has it.

`SkillTreeBuilder` calls `locationsOf(...)` per leaf and stores the result on:

- `AgentryNode.Skill.installedScopes: Set<InstallScope>`
- `AgentryNode.Component.installedScopes: Set<InstallScope>`
- `AgentryNode.Orphan.installedScopes: Set<InstallScope>` — orphans know their
  source scope (one-element set built from the `InstalledSkill` they wrap).

The `installed: Boolean` field is removed; `installedScopes.isNotEmpty()`
replaces all current readers.

**Action routing — picker over hardcoded scope.**

New data key in `AgentryDataKeys.kt`:

```kotlin
val INSTALL_TARGET_DATA_KEY: DataKey<InstallTarget> = DataKey.create("AgentryInstallTarget")
```

`AgentryToolWindowPanel.fireAction` / `fireComponentAction` add the picker's
current selection to the `DataContext` alongside the existing `SELECTED_*`
keys.

`ComponentActions.runComponentOp` replaces:

```kotlin
val scope: InstallScope = if (basePath != null) InstallScope.Project(basePath) else InstallScope.Global
```

with:

```kotlin
val target = e.getData(INSTALL_TARGET_DATA_KEY) ?: AgentrySettings.getInstance().defaultInstallTarget
val scope = target.toScope(project.basePath)
```

The fallback to `settings.defaultInstallTarget` keeps the CLI / agent-fired
paths working (they never set the data key).

`BatchOperations.installByNames` / `uninstallByNames` (legacy flat-skill flow)
get the same change.

`AgentryStartupActivity` and `ProjectSyncService.syncBlocking` are untouched —
they're driven by `.agentry/config.yaml`, not the picker. They already honour
each skill's per-skill `target:` field, falling back to
`settings.defaultInstallTarget`.

## Migration

`AgentrySettings.loadState` is the single chokepoint for persisted settings.
After the existing `this.state = state` line, normalise:

```kotlin
override fun loadState(state: State) {
    this.state = state
    if (enumValues<InstallTarget>().none { it.name == state.defaultInstallTarget }) {
        state.defaultInstallTarget = InstallTarget.CLAUDE_USER.name
    }
}
```

This covers existing installs that persisted `"AGENTRY_CACHE"` or
`"JUNIE_PROJECT"`. Unrecognised values silently coerce to the new default;
no notification — the previous behaviour for those targets was already
"install somewhere the user can't easily find."

`.agentry/config.yaml` parsing: `ProjectSyncService.syncBlocking` currently
does `enumValues<InstallTarget>().firstOrNull { it.name == dep.target } ?:
settings.defaultInstallTarget`. With the trimmed enum, that fall-back already
handles unknown yaml values cleanly. A `log.warn` is added when the fall-back
fires, naming the rejected target so users editing the yaml see why their
config didn't take effect.

## CLI

`AgentryStarter.parseTarget()` allow-list shrinks to the two remaining values.
Unknown `--target` produces:

```
agentry: Unknown --target: AGENTRY_CACHE (one of: CLAUDE_USER, CLAUDE_PROJECT)
```

`printUsage()` updates the `Targets:` line accordingly. No other CLI surface
changes.

## Tests

- `PluginInstallerTest` — existing tests keep their Project-scope assertions;
  add:
  - `testInstallAtUserScopeWhenAlreadyAtProjectEndsUpAtBoth` — install at
    `InstallScope.Global` with the dual-write installer when the same
    component is already at `InstallScope.Project`. Assert both paths exist.
  - `testUninstallAtProjectScopeWhenAtBothLeavesUserIntact` — start in
    both-scopes state, uninstall at Project, assert only Project files
    removed.
- `SkillTreeBuilderTest` *(new file)* — given fixture installs at User only /
  Project only / both, assert `installedScopes` on each `AgentryNode.*` leaf
  reflects reality.
- `AgentrySettingsTest` — load a persisted state where `defaultInstallTarget =
  "AGENTRY_CACHE"`; assert it coerces to `CLAUDE_USER`.

## Out of scope

- **Symlink existing project-scope installs into `~/.claude/`** — separate
  one-time command if the user wants it; not built into the plugin.
- **Move semantics** (install at User auto-removes from Project) — explicitly
  rejected during brainstorming; additive is the locked behaviour.
- **Per-component scope override in `.agentry/config.yaml`** — already
  supported via the `target:` field per skill; no schema changes needed.
- **Re-adding `JUNIE_PROJECT` / a `.agentry/skills/` neutral target** — can
  be revisited if users ask, but no current demand.

## Version

`build.gradle.kts` `version` 0.1.1 → 0.1.2 (in both `version =` lines).
`plugin.xml` `<change-notes>` prepends a 0.1.2 entry summarising the picker,
the default-target flip, and the enum trim.
