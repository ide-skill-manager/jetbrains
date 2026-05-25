# Agentry for JetBrains

Browse, install, and manage AI-agent skills from git-hosted registries — without leaving the IDE. One plugin works in IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider, RubyMine, PhpStorm, CLion, and Android Studio.

> **Status:** v0.1.0 skeleton. APIs and on-disk layout may change.

## What it does

A skill is a folder with a manifest (`skill.json` / `package.json` / `manifest.json`) that an AI coding agent — Claude Code, Junie, GitHub Copilot — reads to extend its behavior. Agentry treats *any git repo* as a skill registry, fetches the manifests, and writes the skill files into whichever agent's well-known directory you target.

The killer move: commit a `.agentry/config.yaml` to your project and your teammates get the same agent setup on `Open Project` (after a one-click trust prompt) — no shared `~/.claude/` to babysit.

## Features

### Skill registries from any git URL — and any branch

- Add one or more git URLs (`https://`, `http://`, `ssh://`, `git://`, or scp-form `user@host:repo.git`) as registry sources under **Settings → Tools → Agentry**.
- Pin to a branch, tag, or commit via the `ref` field. Default is `HEAD`.
- **Branch-based WIP workflow:** point a registry at `feature/wip-skill`, push commits to that branch, click **Refresh** in the tool window — Agentry runs `git fetch --depth=1 origin <ref>` + `reset --hard FETCH_HEAD`, so you always see the latest manifest from your work branch.
- Cached under `~/.agentry/cache/<sha256-prefix>-<readable-name>/` (collision-free per source URL).
- Inputs are validated before they reach `git`: URLs must use a known transport, refs must match git's refname rules, and both are passed after `--` so they can't be interpreted as git flags. `git` runs with `GIT_ALLOW_PROTOCOL=https:http:ssh:git`, `GIT_PROTOCOL_FROM_USER=1`, and `GIT_TERMINAL_PROMPT=0`.

### Tool window for browse / install / remove

- Docked panel under **View → Tool Windows → Agentry** (right side by default).
- Searchable list of every skill discovered across all enabled registries.
- One-click **Install** / **Remove**; long operations run as background tasks with a cancellable progress indicator.
- File IO happens off the EDT; the IDE's VFS is refreshed after every install/uninstall so the Project view picks up the new files immediately.

### Targeted installation per agent

Skills install into whichever directory the target agent reads:

| Target | Path |
|---|---|
| `CLAUDE_PROJECT` | `<project>/.claude/skills/<skill>/` |
| `CLAUDE_USER` | `~/.claude/skills/<skill>/` |
| `JUNIE_PROJECT` | `<project>/.junie/skills/<skill>/` |
| `AGENTRY_CACHE` | `~/.agentry/skills/<skill>/` |

Default target is set in Settings; can be overridden per skill in `.agentry/config.yaml`. Skill names are validated (`^[a-z0-9][a-z0-9._-]{0,63}$`, case-insensitive) and the resolved destination is canonical-path-checked to stay inside the install root — `name = "../../etc/passwd"` is rejected.

Symlinks in skill source trees are **refused**, not followed, so a malicious registry can't include `link → ~/.ssh/id_rsa` and have its contents copied into your install dir.

### Project-level config (`.agentry/config.yaml`) with trust-on-first-use

Commit this file to share your team's agent setup:

```yaml
version: "1"
defaultTarget: CLAUDE_PROJECT
sources:
  - name: company-skills
    url: https://github.com/acme/agent-skills.git
    ref: main
  - name: my-wip
    url: https://github.com/me/skills.git
    ref: feature/new-reviewer-skill
skills:
  - name: code-reviewer
    target: CLAUDE_PROJECT
  - name: pr-summarizer
    target: CLAUDE_USER
```

On project open, Agentry detects the file and shows a balloon notification with **Sync once** and **Trust and auto-sync** actions. There's no silent network egress — every clone goes through that one-time user opt-in per project. Trust is remembered in application settings.

Once a project is trusted, Agentry registers a VFS listener on `.agentry/config.yaml`: edit it (by hand or via an agent) and the plugin re-syncs automatically — no IDE restart.

### Agent-callable surface (Actions + CLI)

Every UI action is also a registered `AnAction` an agent can fire by ID:

| Action ID | What it does |
|---|---|
| `Agentry.Refresh` | Fetch all enabled registries |
| `Agentry.SyncConfig` | Apply `.agentry/config.yaml` for the current project |
| `Agentry.Install` | Install a skill by name |
| `Agentry.Remove` | Uninstall a skill by name |
| `Agentry.AddRegistry` | Add a git URL as a registry |

They also live under **Tools → Agentry** in the menu bar, and `Find Action` (⇧⌘A / Ctrl+Shift+A) lists them all.

For headless use (CI, terminal-based agents), the plugin ships an `ApplicationStarter`:

```bash
idea agentry list                       # available skills across all enabled registries
idea agentry installed                  # skills installed at the default target
idea agentry install code-reviewer      # install by name
idea agentry install code-reviewer --target CLAUDE_USER
idea agentry remove code-reviewer
idea agentry refresh                    # fetch all registries
```

Exit codes: `0` success, `1` usage error, `2` operation failed.

### Settings page

Under **Settings → Tools → Agentry**:

- Manage registry sources (URL + ref + enabled). Toggle individual registries on/off without removing them. Validation runs at add-time, so a typo never ends up in `git`.
- Default install target.
- "Auto-sync `.agentry/config.yaml` on project open (only for trusted projects)" — defaults off.

Settings persist in `agentry.xml` under the IDE config dir.

### Background tasks and notifications

- All git and filesystem work runs via `Task.Backgroundable` so the UI stays responsive.
- `git` processes are cooperatively cancellable: clicking Cancel on the progress indicator destroys the running git process within ~200 ms.
- A balloon `NotificationGroup` reports install successes, failures, and project-sync results.

## Installation

Not yet published to the JetBrains Marketplace. Build locally:

```bash
./gradlew buildPlugin
# artifact: build/distributions/agentry-jetbrains-0.1.0.zip
```

Then in your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**

## Development

```bash
./gradlew runIde          # launch a sandbox IDE with the plugin loaded
./gradlew test            # JUnit + BasePlatformTestCase suite
./gradlew verifyPlugin    # check API compatibility across the IDE matrix
./gradlew buildPlugin     # produce a shippable .zip
```

Workflow tips and the manual smoke checklist live in [CONTRIBUTING.md](CONTRIBUTING.md). The most useful pointers up front:

- Launch the sandbox into [`examples/fixture-project/`](examples/fixture-project/) — it has a pre-built `.agentry/config.yaml` so the trust prompt, sync flow, and tool window all exercise on first open.
- Tail `build/idea-sandbox/system/log/idea.log` in a side terminal — plugin exceptions land there, not in Gradle's stdout.
- Add `-Didea.is.internal=true` to the run config VM options to unlock **Tools → Internal Actions** in the sandbox (UI inspector, PSI viewer, action search).
- Test against other IDEs with `./gradlew runIde -PplatformType=PY` (PyCharm), `WS` (WebStorm), `RD` (Rider).

## Testing

| Layer | Coverage | How to run |
|---|---|---|
| Pure JUnit | `InputValidation` (path-traversal / flag-injection / URL transport allowlist / branch-name workflow), `ManifestParser`, `InstallTarget.resolvePath` | `./gradlew test` |
| `BasePlatformTestCase` | `AgentrySettings` round-trip, `.agentry/config.yaml` parsing via VFS, `SkillInstaller` end-to-end against a pre-populated cache (with symlink-rejection check), cache-path collision resistance | `./gradlew test` |
| Plugin verifier | API compatibility against IDEA Community 2025.1/2/3 + 2026.1, PyCharm Community 2026.1, WebStorm 2026.1, Rider 2026.1 | `./gradlew verifyPlugin` |
| Manual smoke | UI behavior, end-to-end git fetch, multi-IDE feel | [`CONTRIBUTING.md`](CONTRIBUTING.md) checklist |

**CI** (see [`.github/workflows/`](.github/workflows/)):
- `build.yml` — `./gradlew buildPlugin && test` on every push/PR; uploads the built `.zip` as a downloadable artifact and dumps test reports on failure.
- `verify.yml` — runs the plugin verifier matrix on push to `main`, on every PR, and on a weekly cron.

## Requirements

- IntelliJ Platform IC-2025.1 or later (`since-build = 251`, `until-build = 261.*`)
- JDK 21 (Gradle toolchain auto-provisions via Foojay)
- `git` on `PATH`

## Repository layout

```
src/main/kotlin/dev/agentry/jetbrains/
├── AgentryStartupActivity.kt      project-open hook + config file watcher
├── AgentryDisposable.kt           project-scoped lifecycle anchor
├── actions/                       AnAction surfaces (agent-callable)
├── cli/                           ApplicationStarter (idea agentry ...)
├── config/AgentryProjectConfig.kt .agentry/config.yaml reader/writer
├── install/SkillInstaller.kt      install / uninstall / list (symlink-safe)
├── model/                         SkillManifest, RegistrySource, InstallTarget, InstalledSkill
├── registry/
│   ├── ManifestParser.kt          JSON → SkillManifest
│   └── RegistryManager.kt         git clone/fetch + scan
├── settings/AgentrySettings.kt    PersistentStateComponent (app-level)
├── sync/ProjectSyncService.kt     applies .agentry/config.yaml
├── util/
│   ├── AgentryPaths.kt            single source of truth for on-disk locations
│   └── InputValidation.kt         url / ref / skill-name / canonical-path checks
└── ui/
    ├── settings/                  Settings | Tools | Agentry
    └── toolwindow/                docked tool window
```

## License

See [LICENSE](LICENSE).
