# Microsoft agent-plugins spec — implementation plan

Background research and test fixture: see [`ide-skill-manager/example-skills`](https://github.com/ide-skill-manager/example-skills) (`main` has four plugins covering every component type; `feature/wip-skill` adds a fifth experimental skill plugin for refresh-flow testing). Cross-tool reference: [Chris Ayers, "Agent Skills, Plugins, and Marketplaces"](https://chris-ayers.com/posts/agent-skills-plugins-marketplace/).

The plugin currently parses a VS Code Marketplace–shaped `skill.json` / `package.json` / `manifest.json` (see [`registry/ManifestParser.kt`](../../src/main/kotlin/dev/agentry/jetbrains/registry/ManifestParser.kt)). Agentry's job after this work is to ingest the **Microsoft agent-plugins / Claude Code marketplace** shape — a marketplace catalog of plugins, each with a plugin manifest and one or more component types — and translate every component into the on-disk location JetBrains GitHub Copilot expects.

**Two manifest dialects in the wild.** The same spec is published at two canonical paths:

- **Microsoft / Copilot CLI / VS Code Copilot:** `.github/plugin/marketplace.json` (catalog) and `<plugin>/.github/plugin.json` (per-plugin manifest).
- **Claude Code:** `.claude-plugin/marketplace.json` (catalog) and `<plugin>/.claude-plugin/plugin.json` (per-plugin manifest).

Component directories (`skills/`, `commands/`, `agents/`, `hooks/`, `.mcp.json`) sit under the plugin root and are shared by both dialects. Real-world repos may publish either dialect or both (the `example-skills` fixture dual-publishes). Agentry must accept either.

### Cross-tool compatibility matrix

Adapted from Chris Ayers's post, March 2026:

| Capability | Copilot CLI | VS Code Copilot | Claude Code | JetBrains Copilot (Agentry target) |
|---|---|---|---|---|
| Skills (`SKILL.md`) | ✅ | ✅ | ✅ | ✅ (preview, Feb 2026) |
| Custom agents | ✅ | ✅ | ✅ | ✅ (GA, Nov 2025) |
| Plugin manifest | `.github/plugin.json` | `.github/plugin.json` | `.claude-plugin/plugin.json` | (whichever Agentry's parser resolves) |
| Marketplace catalog | `.github/plugin/marketplace.json` | same | `.claude-plugin/marketplace.json` | (Agentry consumes both) |
| Hooks | ✅ | ✅ | ✅ | ✅ (preview, Mar 2026) |
| MCP servers | ✅ | ✅ | ✅ | ✅ (with auto-approve) |
| Slash commands | ✅ | ✅ | ✅ | ⚠ via "Prompt Files" — lossy mapping |
| LSP servers | ✅ | ✅ | ✅ | ❌ |

### Component-to-target mapping (Agentry's translation table)

| Source component | In the plugin tree | JetBrains Copilot install target |
|---|---|---|
| Skill | `skills/<name>/SKILL.md` or root `SKILL.md` | Project: `<project>/.claude/skills/<name>/` ・ Global: `~/.copilot/skills/<name>/` |
| Slash command | `commands/<name>.md` | JetBrains "Prompt Files" — `<project>/.github/prompts/<name>.prompt.md` (frontmatter mapped, lossy) |
| Subagent | `agents/<name>.agent.md` (or `.md` for backward compat) | JetBrains "Chat Agents" — `<project>/.github/agents/<name>.agent.md` (Project) ・ `~/.copilot/agents/<name>.agent.md` (Global) |
| Hook | `hooks/hooks.json` + `scripts/` | `<project>/.github/hooks/` (preview as of 2026-03 changelog) |
| MCP server | `.mcp.json` | IntelliJ MCP config location (see [open questions](#open-questions)) |

### Reference install paths (used by other tools — useful for debugging)

| Tool | Marketplace-installed | Direct-installed |
|---|---|---|
| Copilot CLI | `~/.copilot/installed-plugins/<marketplace>/<plugin>/` | `~/.copilot/installed-plugins/_direct/<plugin>/` |
| VS Code Copilot (macOS) | `~/Library/Application Support/Code/agentPlugins/` | same |
| VS Code Copilot (Windows) | `%APPDATA%/Code/agentPlugins/` | same |

Agentry should follow a similar convention for its own cache, e.g. `~/.agentry/installed-plugins/<marketplace>/<plugin>/`, so users can `ls` to debug what landed.

## Phase 1 — Marketplace + plugin manifest parsing

- [ ] `model/MarketplaceManifest.kt` — root catalog: `name`, `description?`, `owner: Owner`, `metadata: { pluginRoot: String? }?`, `plugins: List<PluginEntry>`. `PluginEntry` carries `name`, `source: String | SourceObject`, plus the overlapping `plugin.json` fields (`displayName?`, `description?`, `version?`, `author?`, `category?`, `tags?`).
- [ ] `model/PluginManifest.kt` — per-plugin: `name` (required), `displayName?`, `version?`, `description?`, `author?`, `homepage?`, `repository?`, `license?`, `keywords?`, `skills?`, `commands?`, `agents?`, `hooks?`, `mcpServers?`, `lspServers?`. Path fields accept `String` or `List<String>`.
- [ ] `registry/MarketplaceParser.kt` — replaces `ManifestParser` for the marketplace path. Probes the registry root in order: `.github/plugin/marketplace.json` first (Microsoft canonical), then `.claude-plugin/marketplace.json` (Claude Code) as a fallback. If both exist (dual-publish), `.github/` wins but a debug log notes the dual presence. Resolves each `plugins[*].source` (string → directory under `metadata.pluginRoot` or relative path; object form `{ "source": "github"|"url", ... }` deferred to Phase 6).
- [ ] `registry/PluginManifestParser.kt` — reads the per-plugin manifest with the same dialect precedence: `.github/plugin.json` first, then `.claude-plugin/plugin.json`. If neither is present, derive `name` from the directory basename, leave other fields default. Both files at once is supported; `.github/` wins.
- [ ] Tolerate unknown top-level fields per spec ("Claude Code ignores top-level fields it does not recognize"). Same applies to `$schema` keys on either dialect.
- [ ] Keep `ManifestParser` alive but deprecate it — invoke it as a fallback only when a registry has neither a `.github/` nor a `.claude-plugin/` directory, to keep older test fixtures loading. Emit a one-shot notification recommending migration.
- [ ] Tests: each dialect parses standalone; dual-publish picks `.github/`; `pluginRoot` resolution; single vs array path fields; missing-manifest fallback (name from dirname); tolerant of unknown top-level fields per the spec.

## Phase 2 — Component discovery within a plugin

- [ ] `model/PluginComponent.kt` (sealed): `Skill(name, sourceFile, supportFiles)`, `Command(name, sourceFile)`, `Agent(name, sourceFile)`, `Hook(configFile, scripts)`, `McpServer(configFile, scripts)`. Each component knows its **source path** and the **list of file globs** to copy.
- [ ] `registry/PluginScanner.kt` — given a `PluginManifest` and the plugin root on disk, return `List<PluginComponent>`. Discovery rules per the spec:
  - Skills: default `skills/` + each path in manifest `skills`. Skill name = frontmatter `name` if present, else directory basename. A root-level `SKILL.md` is loaded as a single-skill plugin.
  - Commands: default `commands/` + each path in manifest `commands`. Flat `.md` files; command name = filename stem.
  - Agents: default `agents/` + each path in manifest `agents`. Prefer files ending in `.agent.md` (Microsoft canonical); accept plain `.md` as a fallback for backward compat. Agent name = frontmatter `name` if present, else filename stem with `.agent` stripped.
  - Hooks: `hooks/hooks.json` or `plugin.json#hooks` (path or inline JSON object). Carry the referenced scripts directory along.
  - MCP: `.mcp.json` or `plugin.json#mcpServers` (path or inline). Carry any referenced bundled binaries.
- [ ] Frontmatter reader: tiny `--- … ---` YAML splitter (no dependency — we only need `name`, `description`, and a few scalar fields). Reuse for skills, commands, agents.
- [ ] Tests using the four `example-skills` plugins as fixtures: each plugin should produce exactly the expected component list (no extras, no misses).

## Phase 3 — Installer dispatch per component type

- [ ] `install/PluginInstaller.kt` — entry point: `installPlugin(plugin: PluginManifest, components: List<PluginComponent>, scope: InstallScope, projectDir: File)`. Dispatches to a per-component installer based on the sealed type.
- [ ] `install/SkillInstaller.kt` — copies `SKILL.md` and adjacent files (scripts, reference docs) into `<target>/<skillName>/`. Scope: `Project → <project>/.claude/skills/<name>/`, `Global → ~/.copilot/skills/<name>/`. Updates manifest frontmatter `name` if missing (fallback to skill folder name).
- [ ] `install/CommandInstaller.kt` — translates `commands/<name>.md` to JetBrains prompt-file shape: rename to `<name>.prompt.md`, drop `argument-hint` (unsupported), keep `description`. Target: `<project>/.github/prompts/<name>.prompt.md` initially; global location TBD.
- [x] `install/AgentInstaller.kt` — copy the source `.agent.md` to `<project>/.github/agents/<name>.agent.md` (Project) or `~/.copilot/agents/<name>.agent.md` (Global). Backfills `name:` and `description:` frontmatter so the loader (which rejects files with no `description`) accepts the install. See [`docs/plans/agent-installer.md`](agent-installer.md).
- [ ] `install/HookInstaller.kt` — copy `hooks/hooks.json` + referenced scripts into `<project>/.github/hooks/<pluginName>/`. **Variable expansion**: rewrite `${CLAUDE_PLUGIN_ROOT}` to the install destination (the script's new absolute path's parent), leave `${CLAUDE_PROJECT_DIR}` for JetBrains' runtime to expand, and resolve `${CLAUDE_PLUGIN_DATA}` to `~/.agentry/plugin-data/<pluginId>/` (created lazily). See [variable-expansion table](#variable-expansion).
- [ ] `install/McpInstaller.kt` — same idea as hooks: copy server scripts, expand `${CLAUDE_PLUGIN_ROOT}` to the on-disk install path, leave `${CLAUDE_PROJECT_DIR}` as a literal. Write the rewritten `.mcp.json` into the IntelliJ MCP config location (target path is an open question).
- [ ] `install/InstallScope.kt` — sealed: `Project(File)`, `Global`. Per-component scope override allowed (skill global, hook project-only). Default scope is configurable per registry.
- [ ] Every installer returns `Result<InstalledComponent>` and contributes to a `PluginInstallReport(installed: List<InstalledComponent>, failed: List<ComponentError>)`. Partial success is a real outcome.

## Phase 4 — UI: plugin-aware tree and scope picker

Builds on Phase 3 of [`ui-improvements.md`](./ui-improvements.md) (tree-based tool window).

- [ ] Extend `SkillTreeModel` with `PluginNode(manifest, components, installState)` under each `RegistryNode`. Component breakdown is a child group: `ComponentGroupNode(kind: ComponentKind, items: List<ComponentNode>)`. Counts in the registry header become "N plugins / M components".
- [ ] `ui/dialogs/InstallScopeDialog.kt` — invoked from the right-click "Install…" action. Per-component scope picker: a small table with rows for each component, scope dropdown (`Project` / `Global` / `n/a` when unsupported), defaults from registry settings.
- [ ] Toolwindow action `Agentry.InstallPlugin` — installs the whole plugin via `PluginInstaller`. The existing `Install selected (N)` action installs a batch in one task and produces a single summary notification.
- [ ] Status badges per component type: a small chip on the component node ("skill installed", "hook installed (project)", "MCP — unsupported on this IDE version").

## Phase 5 — Variable expansion

`${CLAUDE_PLUGIN_ROOT}`, `${CLAUDE_PLUGIN_DATA}`, and `${CLAUDE_PROJECT_DIR}` are inlined inside skills, agents, hook commands, monitor commands, and MCP / LSP configs (spec: "all are substituted inline anywhere they appear"). Agentry needs a deterministic policy:

| Variable | Where it appears | Agentry's policy |
|---|---|---|
| `${CLAUDE_PLUGIN_ROOT}` | hook `command`, MCP `command`/`args`/`env`/`cwd`, skill/agent body | **Rewrite at install time** to the absolute install path of the plugin's component bundle. Source-string substitution; no runtime indirection. |
| `${CLAUDE_PLUGIN_DATA}` | hook commands (persistent state) | **Rewrite at install time** to `~/.agentry/plugin-data/<pluginIdSlug>/`. Create directory on first access (the spec is explicit that this directory is created lazily). |
| `${CLAUDE_PROJECT_DIR}` | hook / MCP commands | **Leave as a literal** — JetBrains Copilot's hook/MCP runtime expands it. If we discover the runtime *doesn't* expand it, fall back to expanding at install time using the host project's path (less portable; means re-install on project move). |

- [ ] `install/VariableExpansion.kt` — small utility: `expand(string, env: ExpansionEnv): String`. Operates on text content (SKILL.md bodies, hook JSON, MCP JSON). Env captures the three variables; any of them may be `Literal` (left as-is) or `Substitute(value)`.
- [ ] Tests: each variable substituted/preserved exactly per the policy above; edge case where a hook command quotes the variable (`"${CLAUDE_PLUGIN_ROOT}"/scripts/x.sh`) round-trips through expansion correctly.

## Phase 6 — Plugin sources beyond local paths

The spec allows `source` to be an object: `{ "source": "github", "repo": "owner/repo", "ref?": "...", "sha?": "..." }` or `{ "source": "url", "url": "..." }`. Today's `RegistryManager` knows how to clone a single registry URL; this extends that to per-plugin cloning when a marketplace points at out-of-tree plugins.

- [ ] `registry/PluginSourceResolver.kt` — given a `PluginEntry.source`, produce an on-disk path. For local paths: resolve relative to `metadata.pluginRoot` or registry root. For `github`/`url`: shallow-clone to `~/.agentry/cache/plugins/<sha>/` and pin to `ref` or `sha`.
- [ ] Cache by SHA. Reuse existing `git ls-remote` plumbing from [`ui-improvements.md`](./ui-improvements.md) Phase 1 for ref → SHA resolution.
- [ ] Tests: object-form sources resolve correctly; ref pinning works; SHA cache hit avoids re-clone.

## Phase 7 — Test fixtures and integration tests

- [ ] Add `ide-skill-manager/example-skills` as a fixture registry in integration tests. Clone in a temp dir, point a `RegistryManager` at it, assert the catalog parses into four `PluginManifest`s on `main` and five on `feature/wip-skill`. The fixture dual-publishes both manifest dialects — confirm `.github/` is preferred and `.claude-plugin/` works in isolation by deleting one or the other in a tweaked clone.
- [ ] Per-plugin assertions:
  - `code-reviewer`: one skill, no other components.
  - `pr-summarizer`: two commands.
  - `commit-message-writer`: one skill (root SKILL.md), one hook config + two scripts.
  - `repo-toolkit`: one agent, one MCP server config + one script.
  - `api-doc-generator` (wip branch only): one skill.
- [ ] Refresh test: switch the local clone from `main` to `feature/wip-skill`, run refresh, assert the new plugin appears and `pr-summarizer`'s version bumps from `0.1.0` → `0.2.0`.
- [ ] Install round-trip per component type: install into a temp "project" + temp "home" dir, assert the expected file landed at the expected path with `${CLAUDE_PLUGIN_ROOT}` rewritten.

## Phase 8 — Verification + review

- [ ] Build + tests green locally.
- [ ] Dispatch parallel review agents (security-sentinel, architecture-strategist, code-simplicity-reviewer, agent-native-reviewer).
- [ ] Address findings.
- [ ] Open PR.

## Known issues to handle defensively

Surfaced by Chris Ayers's post (March 2026) and the [`microsoft/copilot-intellij-feedback`](https://github.com/microsoft/copilot-intellij-feedback) tracker. Agentry should fail loudly where the ecosystem fails silently:

- **Silent manifest errors.** Malformed `plugin.json` / `marketplace.json` causes the feed to silently not appear in some clients. Agentry's parser must surface parse failures as visible notifications (registry node turns red with the underlying exception in the tooltip) rather than dropping plugins.
- **Aggressive feed caching.** Some clients cache the marketplace feed and need a reload or alternate URL to refresh. Agentry's `Refresh` action should bypass any in-memory cache and re-clone if the upstream commit SHA changed.
- **No branch installs in Copilot CLI.** The Copilot CLI cannot install from a specific branch directly. Agentry has its own clone, so this is non-blocking, but the UX should make the "ref" choice prominent so users don't expect upstream-CLI parity.
- **VS Code Copilot: marketplaces are user-level only.** Workspace-level marketplaces aren't honored. Agentry stores registry config in plugin/IDE-global settings (already true today via `RegistryManager`) — keep it that way.
- **JetBrains: global skills not always recognized** ([issue #1517](https://github.com/microsoft/copilot-intellij-feedback/issues/1517)). When installing globally, also offer to mirror into the active project's `.claude/skills/` (or `.github/skills/`) as a workaround. Surface a tooltip on the global scope option explaining the limitation.
- **Dev containers.** Plugin installation may not work inside dev containers (per Chris). Agentry's installer should detect a container environment and warn before writing files.

## Open questions

- ~~**Custom-agent on-disk target on JetBrains.**~~ **Resolved.** The Copilot for JetBrains plugin auto-discovers `.agent.md` files under `<project>/.github/agents/`. Implementation lives in `install/installers/AgentInstaller.kt`; planning + research notes in [`docs/plans/agent-installer.md`](agent-installer.md). The `~/.copilot/agents/` global path is documented for VS Code; we ship it for JetBrains too but haven't empirically verified pickup — flagged in the new plan doc.
- **MCP config location on JetBrains.** Per the same changelog, MCP auto-approve config lives under `Settings → GitHub Copilot → Chat → MCP Server and Tool Auto-approve Configuration`. The persistent on-disk format and any project-scoped vs. global split is unclear. Likely candidates: IntelliJ `options/` XML, or a JSON file in the project's `.idea/` or `~/.config/` tree.
- **Slash-command translation lossiness.** JetBrains "Prompt Files" support `description` and a body, but the spec's `argument-hint` and positional `$1…$n` semantics are not 1:1 with `.prompt.md` substitution. Decide: drop unsupported features with a warning, or render the command body as a skill (one folder per command) and forfeit the `/<name>` invocation shortcut.
- **Scope persistence.** Where does Agentry remember "this plugin's hook was installed globally vs project-locally"? Likely the existing settings service, but needs a per-component schema rather than a per-skill one.

## Carried over from earlier scoping

- `experimental.themes`, `experimental.monitors`, `outputStyles`, `lspServers`, `userConfig`, `channels`, plugin `dependencies` — **deferred**. None are exercised by the example-skills fixture; revisit when a marketplace in the wild ships them.
- Marketplace `$schema` field — accept and ignore (per spec, "Claude Code ignores this field at load time" — we should too).
