# AgentInstaller — closing the spec's first open question

Spec source: [`docs/plans/microsoft-agent-plugins-spec.md`](microsoft-agent-plugins-spec.md), open question 1 — *"Custom-agent on-disk target on JetBrains."*

## What the research confirms

Source: [GitHub Docs — Creating custom agents for Copilot cloud agent in your IDE](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/create-custom-agents-in-your-ide), [GitHub Docs — Custom agents configuration](https://docs.github.com/en/copilot/reference/custom-agents-configuration), [VS Code — Custom agents](https://code.visualstudio.com/docs/copilot/customization/custom-agents), [GitHub Changelog Nov 18 2025 — public preview for JetBrains](https://github.blog/changelog/2025-11-18-custom-agents-available-in-github-copilot-for-jetbrains-eclipse-and-xcode-now-in-public-preview/).

| Question | Answer |
|---|---|
| Project-scope path | `<project>/.github/agents/<name>.agent.md` — auto-discovered, no manual registration |
| Global-scope path | `~/.copilot/agents/<name>.agent.md` — documented by the Copilot for JetBrains [Agent Configuration and Extensibility wiki](https://github.com/microsoft/copilot-intellij-feedback/wiki/Agent-Configuration-and-Extensibility) as `$HOME/.copilot/agents/**/*.agent.md` under "Local Agent Harness, user-level". |
| Filename | `<name>.agent.md` is canonical. `.chatmode.md` is legacy-renamed-to; `.md` still accepted. Always write `.agent.md`. |
| Frontmatter required | `description` (shown in the Copilot Chat dropdown). |
| Frontmatter optional we pass through | `name`, `model`, `tools`, `target`, `argument-hint` |
| Customizations panel "Path" column | One row per file (per-file rows; not per-directory) |

## Phase 1 — Path resolution + installer

- [ ] `InstallPaths.agentFile(name, scope)` — returns `<project>/.github/agents/<name>.agent.md` (Project) or `~/.copilot/agents/<name>.agent.md` (Global). Replaces the current `skillDir("agent-${name}", scope)` placeholder used by `destFor` + `PluginInstallState`.
- [ ] `installers/AgentInstaller.kt` — copy the source `.md` (or `.agent.md`) into `<dest>` after rewriting frontmatter to:
      1. Ensure `description:` is present. If missing in source, derive from the component's first non-empty paragraph or fall back to `name`. Loader requires it.
      2. Ensure `name:` matches the install-time name. (Same backfill pattern as `SkillBundleInstaller`.)
      3. Drop any field that's recognised-but-not-supported on the JetBrains side (the docs list a few; in practice the loader ignores unknowns silently so this is defensive).
- [ ] `InstallPaths.destFor` — agent branch now calls `agentFile` instead of the `skillDir("agent-…")` placeholder.
- [ ] Render: drop the "unsupported on JetBrains" badge on AGENT rows in `SkillTreeRenderer`.

## Phase 2 — Tests

- [ ] `AgentInstallerTest` (BasePlatformTestCase). Cases:
      - Project-scope: source has `description` → file lands at `.github/agents/<name>.agent.md`, frontmatter preserved.
      - Project-scope: source missing `description` → installer backfills from the first paragraph (and falls back to `name` if the body is empty).
      - Project-scope: source missing `name` → installer backfills with the component name.
      - Global-scope: file lands at `~/.copilot/agents/<name>.agent.md` (mocked via `user.home` override).
      - Name-validation: malicious `name = "../etc/passwd"` is refused (same gate as the other installers).
- [ ] `PluginInstallStateTest` (extend the existing if any, else new pure JUnit) — agent install state detected via `agentFile` rather than the old `skillDir` placeholder.

## Phase 3 — Wire-up clean-up

- [ ] Remove the `UnsupportedComponentException` throw from `AgentInstaller.install`. Real path now.
- [ ] `SkillTreeRenderer.renderComponent` — delete the AGENT-specific "unsupported on JetBrains" badge.
- [ ] `PluginInstallerTest.testAgentInstallSurfacesAsRecoverableFailure` — flip to assert a *successful* install path now that we have one. Keep one test that exercises the recoverable path via a *different* deliberately-unsupported component if we add one later.

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

- **Project-scope first.** `~/.copilot/agents/` for global is best-effort; we'll ship it but call out the empirical gap in the PR description. If a v1 user reports the global install isn't picked up by JetBrains, we revisit.
- **Always write `.agent.md`** — never `.md` or `.chatmode.md`. The loader accepts the canonical form on every IDE.
- **Description fallback policy.** Source has it → keep. Missing → use the first non-empty paragraph from the body, trimmed to 120 chars. Body empty → use `"Custom agent: $name"`. The loader rejects files with no description; missing it would break the install in the user's face.

## Follow-ups (not blocking this PR)

- **Dual-write to `.claude/agents/`** — the Copilot for JetBrains wiki lists
  `$PROJECT_ROOT/.claude/agents/**/*.agent.md` as a valid pickup path alongside
  `.github/agents/`. Adding a second write site lets one Agentry install reach both
  Copilot for JetBrains and Claude Code from a single source. Same shape as the
  cross-tool dual-write we should add for skills (`.github/skills/` + `.claude/skills/`).
- **Hook filename pattern fix** — the wiki documents hooks as `*.hooks.json` (flat,
  plural extension) at `.github/hooks/`. Our `HookInstaller` writes
  `.github/hooks/<plugin>/hooks.json` (directory per plugin). Real bug — the current
  file isn't recognised by the JetBrains loader.
- **Customizations panel "Path" column UX** — does it show a directory or a file?
  Best guess per-file. Doesn't affect install logic.
- **`target: github-copilot` frontmatter** — recognised by the loader spec; we don't set it.
