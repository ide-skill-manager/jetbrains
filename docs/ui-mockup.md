# Agentry UI redesign — mockup

Draft for review. Three areas: **(1)** the main tool window, **(2)** the Add Registry flow with branch validation, **(3)** selective install / uninstall.

---

## 1. Tool window — grouped by registry

Current behaviour: a flat `JBList<SkillEntry>` mixing skills from every enabled registry, with no way to tell which one a skill came from.

Proposed: a two-level tree. Top-level nodes are registries (URL + ref + skill count + enabled badge). Children are the skills they offer. Click a registry node to toggle its expand state; click a skill to select it. Skill rows show install status, version, and a one-line description.

```
┌─ Agentry ─────────────────────────────────────────────────────────────┐
│ [Search…]                                       [⟳ Refresh] [+ Add]   │
├───────────────────────────────────────────────────────────────────────┤
│ ▼ example-skills @ main          (3 skills)  ✓ enabled          [⚙]   │
│       https://github.com/ide-skill-manager/example-skills              │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │ ☐  ✓ Code Reviewer                       v1.0.0     installed   │ │
│   │       Reviews a diff for bugs, security, perf, style            │ │
│   ├─────────────────────────────────────────────────────────────────┤ │
│   │ ☐    PR Summarizer                        v0.1.0                │ │
│   │       Generates a concise PR title and body from a diff         │ │
│   ├─────────────────────────────────────────────────────────────────┤ │
│   │ ☐    Commit Message Writer                v1.1.0                │ │
│   │       Writes a conventional-commits message for staged changes  │ │
│   └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│ ▼ example-skills @ feature/wip-skill  (4 skills) ✓ enabled    [⚙]    │
│       https://github.com/ide-skill-manager/example-skills              │
│   ┌─────────────────────────────────────────────────────────────────┐ │
│   │ ☐  ✓ Code Reviewer                       v1.0.0     installed   │ │
│   │ ☐    PR Summarizer (WIP)                  v0.2.0   ↑ newer      │ │
│   │ ☐    Commit Message Writer                v1.1.0                │ │
│   │ ☐    API Doc Generator                    v0.1.0-wip            │ │
│   │       EXPERIMENTAL — generates OpenAPI from a controller        │ │
│   └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│ ▶ acme-internal @ main           (12 skills)  ⚠ unreachable  [⚙]      │
│                                                                       │
│ ▼ Installed (not in any enabled registry)                             │
│   ☐    legacy-formatter                   v0.4.2    orphaned          │
│                                                                       │
├───────────────────────────────────────────────────────────────────────┤
│  [Install selected (1)] [Uninstall selected (1)]      3 of 8 visible │
└───────────────────────────────────────────────────────────────────────┘
```

Notes on the layout:

- **Registry header row** shows URL + ref + the skill count fetched + an enabled badge. The `[⚙]` opens a per-registry context menu (edit, disable, remove, force re-fetch).
- **`▼` / `▶`** is the expand chevron. Collapsed by default for registries with > N skills (so an enterprise registry with 200 entries doesn't drown the others).
- **`↑ newer`** appears next to a skill version that's higher in this registry than the one currently installed elsewhere — gives the user a reason to switch which ref they install from.
- **"Installed (not in any enabled registry)"** is a synthetic group that surfaces skills currently on disk whose source registry has been disabled or removed. Without it, those skills become invisible and un-uninstallable from the UI.
- **Checkboxes** enable selective install/uninstall (see §3).

---

## 2. Add Registry — URL validation + branch dropdown

Current behaviour: two sequential `Messages.showInputDialog` calls (URL, then ref). Users have to know the branch name to type.

Proposed: a single modeless dialog. After the user enters a URL and tabs out (or clicks **Validate**), the plugin runs `git ls-remote --heads --tags <url>` in the background and either fills the dropdown or shows an inline error.

```
┌─ Add Registry ────────────────────────────────────────────────────┐
│                                                                   │
│  URL  [ https://github.com/ide-skill-manager/example-skills.git ] │
│       ✓ Reachable. Found 5 branches, 2 tags.                      │
│                                                                   │
│  Ref  [ main                                          ▼ ]         │
│       ┌──────────────────────────────────────────┐                │
│       │   ★ main                  (default)      │                │
│       │     feature/wip-skill                    │                │
│       │     experimental                         │                │
│       │     legacy-2024                          │                │
│       │     ─────────────  tags  ─────────────   │                │
│       │     v1.0.0                               │                │
│       │     v0.9.0                               │                │
│       │     ─────────────  custom  ───────────   │                │
│       │     Use a commit SHA…                    │                │
│       └──────────────────────────────────────────┘                │
│                                                                   │
│  Name [ example-skills                                          ] │
│       (optional, displayed in the tool window)                    │
│                                                                   │
│  ☑ Enable on add                                                  │
│                                                                   │
│                                       [ Cancel ]  [ Add ]         │
└───────────────────────────────────────────────────────────────────┘
```

Validation states the URL field can show inline:

| Message | Meaning |
|---|---|
| `✓ Reachable. Found <N> branches, <M> tags.` | `ls-remote` returned data, repo is public or the user's git creds work |
| `✗ Authentication required — check your credential helper for this host.` | exit code suggests auth failure |
| `✗ Host unreachable.` | network / DNS / host-not-found |
| `✗ Not a git repository.` | reachable host but no usable refs |
| `✗ URL must use https, http, ssh, or git protocol.` | `InputValidation.isValidRegistryUrl` rejection |

Branch dropdown behaviour:

- **Defaults to** the remote's default ref (whatever `HEAD` points at — usually `main` or `master`).
- **Sections** for branches and tags; "Use a commit SHA…" opens a small text input below the dropdown for pinning to a specific commit.
- **Type-ahead** — start typing `feat` and it filters to matching branches.
- **No validation needed** for the dropdown choice — the user can only pick what we already verified exists.
- **Falls back to a text field** if `ls-remote` fails (so the dialog still works for offline / restricted-network cases).

`Add` stays disabled until both URL validation succeeds and a ref is selected.

---

## 3. Selective install / uninstall

Current behaviour: select one skill, click Install or Remove. No batch.

Proposed: each skill row has a checkbox (`☐` in the mockup). The bottom action bar shows `[Install selected (N)]` and `[Uninstall selected (N)]`. Buttons enable/disable based on the selection:

- **Install selected (N)** — enabled when ≥ 1 *not-installed* skill is checked. Greyed when all checked items are already installed.
- **Uninstall selected (N)** — enabled when ≥ 1 *installed* skill is checked.
- If the selection mixes installed and not-installed, both buttons enable; clicking applies the operation only to the eligible subset.

Each operation runs as a single `Task.Backgroundable` with a determinate progress bar. Per-skill failures are collected and surfaced at the end as one balloon ("Installed 3, failed 1: `foo` — error message"), not one balloon per skill.

A right-click context menu on a skill row gives the same actions, plus **Open install location** and **Show in registry**.

---

## What I'd build first

If you sign off on this shape, I'd implement in this order:

1. Replace `JBList<SkillEntry>` with a `Tree<RegistryNode | SkillNode>` and the grouped renderer.
2. Add the synthetic "Installed (not in any enabled registry)" group so disabling a registry doesn't strand installed skills.
3. Build the new Add Registry dialog with the `git ls-remote`-backed branch dropdown.
4. Wire up checkbox multi-select + batch install/uninstall.

Open questions worth your steer before I start:

- **"Commands etc"** — Claude Code also has slash commands (`.claude/commands/`) and subagents. Should the tool window also surface those, perhaps as a sibling tab? Or do you mean *commands inside a skill*, not a separate type?
- **Tree library** — `com.intellij.ui.tree.Tree` (standard JetBrains) is what I'd reach for. Compose-for-Desktop would give a slicker look but adds a heavy dep.
- **`ls-remote` cache** — should we cache the branch list for, say, 60 seconds so re-opening Add Registry doesn't re-fetch?
