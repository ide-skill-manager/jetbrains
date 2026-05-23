# UI Improvements — implementation plan

Spec: [`docs/ui-mockup.md`](../ui-mockup.md).

## Phase 1 — Remote ref discovery

- [ ] `registry/RemoteRefs.kt` — value type `RemoteRefs(branches: List<String>, tags: List<String>, defaultRef: String?)`
- [ ] `registry/BranchListService.kt` (app service) — runs `git ls-remote --heads --tags --symref <url> HEAD`, parses output, returns `Result<RemoteRefs>`. Same env hardening as `RegistryManager` (`GIT_ALLOW_PROTOCOL`, `GIT_TERMINAL_PROMPT=0`, validated URL only). Result cached for 60 s keyed by `redactCredentials(url)`.
- [ ] Tests: ls-remote output parsing; cache hit/miss; rejection of invalid URLs.

## Phase 2 — Add Registry dialog

- [ ] `ui/dialogs/AddRegistryDialog.kt` extending `DialogWrapper`. Fields: URL (text + inline status line), Ref (combo box), Name (text, optional), "Enable on add" (checkbox).
- [ ] URL validation: focus-loss + Validate button. Runs `BranchListService.fetch` in a background task; while in-flight the dropdown shows a spinner. On success populate combo and pre-select `defaultRef ?: "main"`. On failure show error message and fall back to a plain text field for the ref.
- [ ] Combo grouping: branches first, then a separator, then tags, then a separator + "Use a commit SHA…" entry that swaps the combo for a text input.
- [ ] "Add" stays disabled until URL validates and a ref is chosen.
- [ ] Replace `AddRegistryAction.actionPerformed` body to show this dialog.

## Phase 3 — Tree-based tool window

- [ ] `model/SkillTreeModel.kt` — node types: `RegistryNode(source, status, skillCount)`, `SkillNode(manifest, installed, checked)`, `OrphanGroupNode`. The model is rebuilt from `(sources, manifestsBySource, installedNames)` snapshots and stays fully UI-thread-mutable.
- [ ] `ui/toolwindow/SkillTreeRenderer.kt` — `ColoredTreeCellRenderer` subclass that renders registry headers (URL @ ref, count, enabled badge) and skill rows (checkbox + display name + version + status fragment). Theme colors via `JBColor`/`SimpleTextAttributes`.
- [ ] Click handling: checkbox region toggles `SkillNode.checked`; rest of row selects the node.
- [ ] Replace `JBList<SkillEntry>` in `AgentryToolWindowPanel` with the tree.

## Phase 4 — Batch install / uninstall

- [ ] `install/BatchOperations.kt` (app service) — `installAll(manifests, target, basePath)` and `uninstallAll(names, target, basePath)` each run inside one `Task.Backgroundable`, collect per-item `Result`, and emit one notification (`"Installed N, failed K — first error: …"`).
- [ ] Bottom action bar in the tool window: `Install selected (N)` / `Uninstall selected (N)` enable based on checkbox state, dispatch via `ActionManager.tryToExecute` with a custom data key carrying the selected manifests (same pattern we used for `SKILL_NAME_DATA_KEY`).
- [ ] Two new actions registered in `plugin.xml`: `Agentry.InstallSelected`, `Agentry.UninstallSelected`. Both fire `SKILLS_CHANGED` on completion.
- [ ] Right-click context menu on tree rows: same operations plus *Open install location* (skill), *Edit / Disable / Remove / Refresh now* (registry).

## Phase 5 — Synthetic "Installed orphans" group

- [ ] When building the tree, diff `installer.listInstalled(defaultTarget)` against the union of manifests-from-enabled-registries by name. Anything installed that isn't covered surfaces under an `OrphanGroupNode`. Provides an uninstall path for skills whose registry was removed.

## Phase 6 — Verification + review

- [ ] Build + tests green locally.
- [ ] Dispatch parallel review agents (security-sentinel, architecture-strategist, code-simplicity-reviewer, agent-native-reviewer).
- [ ] Address findings.
- [ ] Open PR.

## Carried over from mockup open questions

- Commands and subagents as separate UI tabs: **deferred**. The tree node type hierarchy is extensible (sealed class) so it can grow without restructuring.
- Tree widget: standard JetBrains `com.intellij.ui.tree.Tree` + `DefaultMutableTreeNode`. No Compose dep.
- `ls-remote` cache: **60 s TTL**, keyed by redacted URL, invalidated when the user hits Refresh on the registry header.
