# AgentInstaller — closing the spec's first open question

Spec source: [`docs/plans/microsoft-agent-plugins-spec.md`](microsoft-agent-plugins-spec.md), open question 1 — *"Custom-agent on-disk target on JetBrains."*

## What the research confirms

Source: [GitHub Docs — Creating custom agents for Copilot cloud agent in your IDE](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/create-custom-agents-in-your-ide), [GitHub Docs — Custom agents configuration](https://docs.github.com/en/copilot/reference/custom-agents-configuration), [VS Code — Custom agents](https://code.visualstudio.com/docs/copilot/customization/custom-agents), [GitHub Changelog Nov 18 2025 — public preview for JetBrains](https://github.blog/changelog/2025-11-18-custom-agents-available-in-github-copilot-for-jetbrains-eclipse-and-xcode-now-in-public-preview/).

| Question | Answer |
|---|---|
| Project-scope path | `<project>/.github/agents/<name>.agent.md` — auto-discovered, no manual registration |
| Global-scope path | Dual-written: `~/.copilot/agents/<plugin>__<name>.agent.md` AND `~/.claude/agents/<plugin>__<name>.agent.md`. `~/.copilot/agents/` is documented by the Copilot for JetBrains [Agent Configuration and Extensibility wiki](https://github.com/microsoft/copilot-intellij-feedback/wiki/Agent-Configuration-and-Extensibility) as `$HOME/.copilot/agents/**/*.agent.md` under "Local Agent Harness, user-level"; `~/.claude/agents/` is read by Claude Code (mirror of the project-scope dual-write). Filename is namespaced by plugin id because both home-level dirs are shared cross-IDE writable surfaces — two plugins shipping a same-named agent would otherwise collide. |
| Filename | `<name>.agent.md` is canonical. `.chatmode.md` is legacy-renamed-to; `.md` still accepted. Always write `.agent.md`. |
| Frontmatter required | `description` (shown in the Copilot Chat dropdown). |
| Frontmatter optional we pass through | `name`, `model`, `tools`, `target`, `argument-hint` |
| Customizations panel "Path" column | One row per file (per-file rows; not per-directory) |

## Phase 1 — Path resolution + installer

- [x] `InstallPaths.destinationsFor(component, plugin, scope)` — returns every dest the install lands at. Agents dual-write to `.github/agents/` AND `.claude/agents/`; Global-scope filenames are namespaced as `<plugin>__<name>.agent.md`. Single source of truth: `AgentInstaller`, `PluginInstallState`, and `ComponentActions` (uninstall) all resolve through this. (Replaces the prior `skillDir("agent-${name}", scope)` placeholder.)
- [x] `installers/AgentInstaller.kt` — copies the source `.md` / `.agent.md` to every destination after rewriting frontmatter:
      1. `description:` backfilled from source → component override → first non-empty paragraph → `"Custom agent: <name>"`. Loader requires it.
      2. `name:` backfilled from the source frontmatter, falling back to the component name.
      3. All other frontmatter (`model`, `tools`, `target`, `argument-hint`, etc.) passes through. Loader ignores unknowns silently.
      Plus: source-file size cap (1 MB) before read, symlinked source refused, per-destination containment check against an install root derived from scope (not from dest), and always emits `.agent.md` regardless of input extension.
- [x] `InstallPaths.destFor` — dispatches through `destinationsFor`; the agent branch returns the dual-write list (with global namespacing) instead of the old `skillDir("agent-…")` placeholder.
- [x] Render: AGENT rows no longer carry an "unsupported on JetBrains" badge in the renderer.

## Phase 2 — Tests

- [x] Agent install coverage in `PluginInstallerTest` (BasePlatformTestCase). Cases:
      - Project-scope: source has `description` → file lands at `.github/agents/<name>.agent.md`, frontmatter preserved.
      - Project-scope: source missing `description` → installer backfills from the first paragraph (and falls back to `name` if the body is empty).
      - Project-scope: source missing `name` → installer backfills with the component name.
      - Global-scope: files land at `~/.copilot/agents/<plugin>__<name>.agent.md` AND `~/.claude/agents/<plugin>__<name>.agent.md` (mocked via `user.home` override). Asserts the plugin-id namespacing so two plugins shipping the same agent name can't overwrite each other.
      - Name-validation: malicious `name = "../etc/passwd"` is refused (same gate as the other installers).
- [x] Install-state detection routes through `InstallPaths.destinationsFor` (so agent state checks see the dual-write + namespaced destinations, not the old `skillDir` placeholder).

## Phase 3 — Wire-up clean-up

- [x] `UnsupportedComponentException` throw removed from `AgentInstaller.install`. Real install path now.
- [x] `SkillTreeRenderer.renderComponent` — AGENT-specific "unsupported on JetBrains" badge removed.
- [x] `PluginInstallerTest.testAgentInstallSurfacesAsRecoverableFailure` — flipped to assert successful install paths (`testAgentInstallDualWritesGithubAndClaudePaths`, `testAgentInstallGlobalScopeNamespacesByPluginAndDualWrites`, etc.). A separate `mixed-result` test exercises the recoverable-failure path via a missing MCP config.

## Phase 4 — Documentation

- [ ] `docs/plans/microsoft-agent-plugins-spec.md` — strike-through the "AgentInstaller is a stub" line in the deferred list, link to the implementation; update the spec's open-questions section to note the JetBrains-global path is still unverified empirically.
- [ ] `CONTRIBUTING.md` smoke-checklist: add a Chat-Agents row (install an agent → verify it appears in Tools → GitHub Copilot → Customizations → Chat Agents → Workspace).

## Phase 5 — Verification + review

- [ ] `./gradlew test` green.
- [ ] `./gradlew runIde` — install a real agent component from a marketplace; confirm the file lands at `.github/agents/` and shows up in the Copilot Customizations panel.
- [ ] Dispatch parallel review agents (security, architecture, simplicity, agent-native).
- [ ] Address findings.
- [ ] Open PR + run review-loop.

## Decisions baked in

- **Project-scope first.** Global is best-effort; we ship it but call out the empirical gap in the PR description. If a v1 user reports the global install isn't picked up by JetBrains, we revisit. Both project and global paths dual-write to `.github/agents/` AND `.claude/agents/`; global filenames are namespaced by plugin id (`<plugin>__<name>.agent.md`) because both home dirs are shared cross-IDE writable surfaces.
- **Always write `.agent.md`** — never `.md` or `.chatmode.md`. The loader accepts the canonical form on every IDE.
- **Description fallback policy.** Source has it → keep. Missing → use the first non-empty paragraph from the body, trimmed to 120 chars. Body empty → use `"Custom agent: $name"`. The loader rejects files with no description; missing it would break the install in the user's face.

## Follow-ups (not blocking this PR)

- **Hook filename pattern fix** — the wiki documents hooks as `*.hooks.json` (flat,
  plural extension) at `.github/hooks/`. Our `HookInstaller` writes
  `.github/hooks/<plugin>/hooks.json` (directory per plugin). Real bug — the current
  file isn't recognised by the JetBrains loader.
- **Customizations panel "Path" column UX** — does it show a directory or a file?
  Best guess per-file. Doesn't affect install logic.
- **`target: github-copilot` frontmatter** — recognised by the loader spec; we don't set it.
