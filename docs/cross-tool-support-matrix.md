# Cross-tool support matrix

What each tool reads from disk for agent customisations — skills, agents, commands, hooks,
MCP servers, instructions / prompts — and which sources we used to verify each entry.

Maintained as the canonical reference for Agentry's install-target decisions.
**Read this before changing an `InstallPaths` path.**

---

## Quick reference

| Component | Claude Code | Copilot for JetBrains | VS Code Copilot | Copilot CLI | Copilot cloud agent |
|---|---|---|---|---|---|
| Skills (`SKILL.md`) | `.claude/skills/` ✅<br>`~/.claude/skills/` ✅ | `.github/skills/` ✅<br>`.claude/skills/` ✅<br>`agents/skills/` ✅<br>`~/.copilot/skills/` ⚠️ <sup>[1]</sup><br>`~/.agents/skills/` ✅<br>`~/.claude/skills/` ✅ | `.github/skills/` ✅<br>`.claude/skills/` ✅<br>`.agents/skills/` ✅<br>`~/.copilot/skills/` ✅<br>`~/.agents/skills/` ✅ | same as VS Code | `.github/skills/` ✅<br>`.claude/skills/` ✅<br>`.agents/skills/` ✅ |
| Custom chat agents (`.agent.md`) | `.claude/agents/` ✅<br>`~/.claude/agents/` ✅ | `.github/agents/**/*.agent.md` ✅<br>`.claude/agents/**/*.agent.md` ✅<br>`~/.copilot/agents/**/*.agent.md` ✅ | `.github/agents/*.agent.md` ✅<br>`~/.copilot/agents/*.agent.md` ✅ | same as VS Code | not applicable (cloud-only) |
| Slash commands | `.claude/commands/<name>.md` ✅<br>`~/.claude/commands/<name>.md` ✅ | `.github/prompts/*.prompt.md` ✅ (different shape) | `.github/prompts/*.prompt.md` ✅ | `.github/prompts/*.prompt.md` ✅ | n/a |
| Hooks | `.claude/hooks/hooks.json` ✅ | `.github/hooks/*.hooks.json` ✅ <sup>[2]</sup> | `.github/hooks/*.hooks.json` ✅ | `.github/hooks/*.hooks.json` ✅ | n/a |
| MCP servers | `.mcp.json` (project) ✅<br>`~/.claude.json` ✅ | location undocumented as of 2026-05 ⚠️ <sup>[3]</sup> | `.vscode/mcp.json` ✅ | `~/.copilot/mcp.json` ✅ | n/a |
| Project instructions | `CLAUDE.md` ✅ (also `CLAUDE.local.md`) | `.github/copilot-instructions.md` ✅<br>`.github/instructions/*.instructions.md` ✅<br>`AGENTS.md` ✅ (toggle in Customizations)<br>`CLAUDE.md` ✅ (toggle in Customizations) | same as JetBrains | same as JetBrains | `.github/copilot-instructions.md` ✅ |
| User-level instructions | n/a (always project) | `~/.copilot/instructions/*.instructions.md` ✅ | same | `~/.copilot/copilot-instructions.md` ✅ | n/a |

Legend: ✅ documented + works · ⚠️ documented but reported broken · ❌ not supported

Footnotes:
1. **`~/.copilot/skills/` on Copilot for JetBrains** is documented by the wiki <sup>[wiki]</sup>
   but reported broken in [microsoft/copilot-intellij-feedback#1517][issue-1517] on plugin
   version 1.5.66-243 (a 2024.3-line build). Per user report (May 2026, **TODO cite**) GitHub
   Copilot is ending support for JetBrains 2024.2 / 2024.3 — so the bug is effectively frozen on
   the affected platform; behaviour on current 2025.1+ Copilot plugin builds is unverified.
2. **Hook filename pattern** per the wiki is `*.hooks.json` (flat, plural extension) — *not*
   `hooks.json` inside a per-plugin subdirectory. Agentry's current `HookInstaller` writes the
   wrong pattern; tracked as a follow-up.
3. **MCP on JetBrains** has a Customizations panel entry per the Mar 2026 changelog but the
   on-disk persistence format isn't documented. Likely `.idea/`-rooted XML or shared with the
   VS Code `.vscode/mcp.json` location; needs empirical verification.

---

## Per-tool detail

### Claude Code (CLI + Desktop)

- **Project paths:** `.claude/skills/<name>/SKILL.md`, `.claude/commands/<name>.md`,
  `.claude/agents/<name>.md`, `CLAUDE.md`, `CLAUDE.local.md`, `.mcp.json`.
- **User paths:** mirror project under `~/.claude/`. MCP user-level: `~/.claude.json`.
- **Frontmatter for skills:** `name`, `description`, `tools` (per `SKILL.md` schema).
- **Discovery:** auto-scan at startup.
- **Source:** [code.claude.com — Extend Claude with skills][claude-skills],
  [Claude Code customization guide][alexop-claude], [anthropics/skills repo][anthropic-skills].

### Copilot for JetBrains

The most-cross-tool tool: scans both `.github/` *and* `.claude/` paths and exposes a
"Customizations" panel with toggles for `AGENTS.md` and `CLAUDE.md`.

- **Skills (project):** `agents/skills/**/SKILL.md`, `.github/skills/**/SKILL.md`,
  `.claude/skills/**/SKILL.md`.
- **Skills (user):** `~/.agents/skills/**/SKILL.md`, `~/.copilot/skills/**/SKILL.md`,
  `~/.claude/skills/**/SKILL.md`. *(See footnote 1.)*
- **Agents (project):** `.github/agents/**/*.agent.md`, `.claude/agents/**/*.agent.md`.
- **Agents (user):** `~/.copilot/agents/**/*.agent.md`.
- **Hooks (project):** `.github/hooks/*.hooks.json` — flat, plural ext.
- **Instructions / prompts (project):** `.github/instructions/*.instructions.md`,
  `.github/prompts/*.prompt.md`. `AGENTS.md` and `CLAUDE.md` (incl. nested experimental)
  via Customizations toggle.
- **Discovery:** auto-scan; the Customizations panel lists discovered items with file paths.
- **Source:** [Agent Configuration and Extensibility wiki][wiki] (authoritative for
  JetBrains paths); [Copilot for JetBrains custom-agents docs][gh-custom-agents] (cross-tool
  agent format); user-supplied screenshot of the Customizations panel
  (Tools → GitHub Copilot → Customizations).

### VS Code Copilot

- **Skills:** `.github/skills/`, `.claude/skills/`, `.agents/skills/`; user-level
  `~/.copilot/skills/`, `~/.agents/skills/`.
- **Agents:** `.github/agents/*.agent.md`. The legacy `.chatmode.md` extension is still
  accepted but new files should be `.agent.md`. Frontmatter `description` required.
- **Prompts:** `.github/prompts/*.prompt.md`.
- **Instructions:** `.github/copilot-instructions.md`, `.github/instructions/*.instructions.md`
  (with `applyTo:` glob).
- **MCP (project):** `.vscode/mcp.json`. MCP (workspace): also `.mcp.json` at root for
  cross-tool consumption.
- **Plugin marketplaces:** `chat.plugins.marketplaces` setting; auto-discovers installed plugins
  under `~/Library/Application Support/Code/agentPlugins/` (macOS).
- **Source:** [VS Code custom-agents docs][vscode-custom-agents], [VS Code custom-instructions
  docs][vscode-instructions], [VS Code agent-plugins spec][vscode-agent-plugins],
  [GitHub Docs — add skills][gh-add-skills].

### Copilot CLI

- **Project:** `.github/skills/`, `.github/agents/`, `.github/prompts/`, `.github/hooks/`,
  `.github/copilot-instructions.md`.
- **User:** `~/.copilot/skills/`, `~/.copilot/agents/`, `~/.copilot/instructions/`,
  `~/.copilot/copilot-instructions.md`, `~/.copilot/mcp.json`.
- **Plugin installs:** `~/.copilot/installed-plugins/<marketplace>/<plugin>/` for
  marketplace installs, `~/.copilot/installed-plugins/_direct/<plugin>/` for `gh skill install`
  / `copilot plugin install`.
- **Source:** [GitHub Docs — add skills][gh-add-skills], [Chris Ayers — Agent Skills,
  Plugins, and Marketplaces][ayers].

### Copilot cloud agent

- **Project paths only:** `.github/skills/`, `.claude/skills/`, `.agents/skills/`,
  `.github/copilot-instructions.md`.
- **No user-level concept** (each repo is the unit of context).
- **Discovery:** auto-scan from the repo. `gh skill install --pin <sha>` writes
  provenance fields into the installed `SKILL.md` frontmatter.
- **Source:** [GitHub Docs — add skills][gh-add-skills].

---

## Agent-plugin bundle format (Microsoft / Claude Code spec)

Cross-tool *bundling* format. A plugin bundle is a directory containing:

```
my-plugin/
  .github/plugin.json          OR  .claude-plugin/plugin.json  (manifest; dual-publish supported)
  skills/<name>/SKILL.md
  agents/<name>.agent.md
  commands/<name>.md
  hooks/hooks.json
  .mcp.json
  scripts/
```

`plugin.json` declares which components ship in the bundle. The catalog format
`marketplace.json` (at `.github/plugin/marketplace.json` or
`.claude-plugin/marketplace.json` at the registry root) lists one or more plugins with
local-path or `{ source: "github", repo: ..., ref: ... }` references.

When installed, each component is unpacked into its tool's canonical install path
(table above).

Sources: [Chris Ayers — Agent Skills, Plugins, and Marketplaces][ayers],
[VS Code agent-plugins spec][vscode-agent-plugins].

---

## Agentry's choices

| Component | What we write | Why |
|---|---|---|
| Skill | `.claude/skills/<name>/` (Project) ・ `~/.copilot/skills/<name>/` (Global) | Today: Claude-first. Standardise on `.claude/skills/` follow-up — per the GitHub docs<sup>[gh-add-skills]</sup> all Copilot variants read it too, so one write hits every tool. |
| Agent | **`.github/agents/<name>.agent.md` + `.claude/agents/<name>.agent.md`** (Project) ・ **`~/.copilot/agents/<plugin>__<name>.agent.md` + `~/.claude/agents/<plugin>__<name>.agent.md`** (Global) | Dual-write: VS Code Copilot's custom-agents docs only mention `.github/`; Claude Code only reads `.claude/`. Two writes hit every tool. Global filenames are namespaced because the home-level dirs are shared cross-IDE writable surfaces. |
| Command | `.github/prompts/<name>.prompt.md` (Project) | Lossy translation: Claude Code's `commands/<name>.md` becomes JetBrains' Prompt File. `argument-hint` dropped with a warning; positional `$1` markers passed through as literal text. |
| Hook | `.github/hooks/<plugin>/hooks.json` (Project) — **bug**, wiki says `.hooks.json` flat | Tracked as a follow-up. |
| MCP server | `.github/mcp/<plugin>/` (Project) — **placeholder**; real JetBrains MCP path undocumented | Tracked as the spec's open question. |
| Agent fall-back description | First non-empty paragraph of the body, ≤ 120 chars; literal `"Custom agent: <name>"` when body empty | Loader silently drops files without `description`. |

The dual-write decision for agents is captured in `install/installers/InstallPaths.kt`'s
`agentDestinations` helper and was driven by the discussion thread on PR #6.

---

## Known issues we've collided with

- **[microsoft/copilot-intellij-feedback#1517][issue-1517]** — *"Global Skills Not Recognized
  – Requires Duplication in Project .github/skills Directory"*. Wiki documents global skill
  paths as supported on JetBrains; this bug report contradicts that. Either a regression or a
  stale wiki entry. Doesn't affect our agents path (`~/.copilot/agents/` documented
  separately).
- **JetBrains Customizations panel "Path" column** — undocumented whether it shows per-file
  or per-directory rows. Best guess: per-file. Doesn't change install logic.
- **`target: github-copilot` frontmatter** for agents — recognised by the loader spec; we
  don't set it. Defer until a real use case appears.
- **`.mcp.json` shape on JetBrains** — Customizations panel exposes an MCP entry but
  no on-disk format is documented. Open question; tracked in `microsoft-agent-plugins-spec.md`.

---

## References

[claude-skills]: https://code.claude.com/docs/en/skills "Extend Claude with skills"
[anthropic-skills]: https://github.com/anthropics/skills "anthropics/skills repo"
[alexop-claude]: https://alexop.dev/posts/claude-code-customization-guide-claudemd-skills-subagents/ "Claude Code customization guide"
[wiki]: https://github.com/microsoft/copilot-intellij-feedback/wiki/Agent-Configuration-and-Extensibility "Copilot for JetBrains: Agent Configuration and Extensibility wiki"
[gh-custom-agents]: https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/create-custom-agents-in-your-ide "GitHub Docs — Creating custom agents in your IDE"
[gh-add-skills]: https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills "GitHub Docs — Add skills to the Copilot cloud agent"
[vscode-custom-agents]: https://code.visualstudio.com/docs/copilot/customization/custom-agents "VS Code — Custom agents"
[vscode-instructions]: https://code.visualstudio.com/docs/copilot/customization/custom-instructions "VS Code — Custom instructions"
[vscode-agent-plugins]: https://code.visualstudio.com/docs/copilot/customization/agent-plugins "VS Code — Agent plugins"
[ayers]: https://chris-ayers.com/posts/agent-skills-plugins-marketplace/ "Chris Ayers — Agent Skills, Plugins, and Marketplaces"
[issue-1517]: https://github.com/microsoft/copilot-intellij-feedback/issues/1517 "Issue 1517: Global Skills Not Recognized"
- [GitHub Changelog Nov 18 2025 — Custom agents in JetBrains/Eclipse/Xcode public preview](https://github.blog/changelog/2025-11-18-custom-agents-available-in-github-copilot-for-jetbrains-eclipse-and-xcode-now-in-public-preview/)
- [GitHub Changelog Mar 11 2026 — Major agentic capabilities in Copilot for JetBrains](https://github.blog/changelog/2026-03-11-major-agentic-capabilities-improvements-in-github-copilot-for-jetbrains-ides/)
- [github/awesome-copilot AGENTS.md](https://github.com/github/awesome-copilot/blob/main/AGENTS.md)
- IntelliJ Copilot plugin Customizations panel screenshot (user-supplied, 2026-05)

---

## How to use this doc

- **Adding a new install target** → check the matrix first; pick the path the *most tools*
  read; if multiple paths needed, prefer dual-write over a runtime "router".
- **Disagreement between sources** → wiki + GitHub Docs win over blog posts + issue reports
  (the latter can be stale or anecdotal). Cite both.
- **No source for a path** → mark as a follow-up in the spec's open-questions and don't
  guess; the empirical sandbox test is cheaper than a wrong default that ships.
