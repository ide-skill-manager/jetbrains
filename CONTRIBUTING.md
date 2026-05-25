# Contributing to Agentry

## Setup

```bash
./gradlew runIde     # launches a sandbox IDE with the plugin loaded
./gradlew test       # JUnit + BasePlatformTestCase suite
./gradlew verifyPlugin
```

The Gradle toolchain auto-provisions JDK 21 via Foojay; you only need `git` and Gradle 8.x on your PATH (the wrapper handles Gradle itself).

## Development loop

Day-to-day, the highest-ROI feedback loop is `runIde` — not unit tests.

```bash
./gradlew runIde
```

A fresh IntelliJ Community window opens. Its config lives under `build/idea-sandbox/` (isolated from your real IDE).

### Run against a known-good project

Always open the sandbox into [`examples/fixture-project/`](examples/fixture-project/) — it has a pre-populated `.agentry/config.yaml`, so the trust-prompt flow, tool window, and project sync all exercise on first open. **File → Open** → that directory.

### Tail the sandbox log while you work

Plugin exceptions go to the sandbox log, not Gradle's stdout:

```bash
tail -f build/idea-sandbox/system/log/idea.log
```

Keep this in a side terminal.

### Enable internal mode for diagnostic tools

Edit the **Run/Debug Configurations** for `Run Plugin` (or pass via Gradle) and add to VM options:

```
-Didea.is.internal=true
```

Unlocks **Tools → Internal Actions** in the sandbox — UI inspector, PSI viewer, action search, and other things you'll want once you start debugging UI.

### Test against other IDE flavors

```bash
./gradlew runIde -PplatformType=PY      # PyCharm Community
./gradlew runIde -PplatformType=WS      # WebStorm
./gradlew runIde -PplatformType=RD      # Rider
```

Do this *before* publishing, not after a user files a bug.

## Testing layers

| Layer | When to use | Run with |
|---|---|---|
| Pure JUnit (`src/test/.../*Test.kt` without `BasePlatformTestCase`) | Input validation, parsing, path math | `./gradlew test` |
| `BasePlatformTestCase` | Anything that needs a `Project`, VFS, or service container | `./gradlew test` |
| `verifyPlugin` | API-compatibility regression across IDE versions | `./gradlew verifyPlugin` |
| Manual smoke | UI behavior, multi-IDE feel, end-to-end git fetch | the checklist below |

Add tests in the appropriate layer. Pure-logic things go in the cheap layer; anything touching the platform goes in `BasePlatformTestCase` so it runs against real platform APIs.

## Manual smoke checklist (pre-release)

Run through this in a sandbox IDE before tagging a release. About 15 minutes.

- [ ] **Tool window appears** — sandbox IDE has an "Agentry" tool window on the right (View → Tool Windows → Agentry).
- [ ] **Add a registry** — open Settings → Tools → Agentry, paste a real git URL (e.g. `https://github.com/<your-org>/<test-registry>.git`), ref `main`, click Add, OK.
- [ ] **Refresh** — click Refresh in the tool window. Skills appear within ~10s. Progress indicator shows; Cancel works (try it once).
- [ ] **Install** — select a skill, click Install. File lands at `<project>/.claude/skills/<skill-name>/` (or your default target). Project view picks it up automatically (no manual refresh).
- [ ] **Uninstall** — click Remove on the same skill. Directory removed; Project view updates.
- [ ] **Branch workflow** — point a registry at a WIP branch (`feature/foo`), push a new commit to that branch, click Refresh in the IDE, verify the new manifest version appears.
- [ ] **Settings persist** — restart the sandbox IDE (`./gradlew runIde` again), open Settings, verify the registry you added is still there.
- [ ] **Trust prompt** — open the fixture project for the first time in a fresh sandbox; the trust balloon appears. Click "Sync once". Skills install. Reopen the project — no balloon (trust isn't auto-elevated, but the file watcher still works if you turned auto-sync on).
- [ ] **Chat agent install** — install a marketplace plugin that ships a custom agent component. Confirm the `.agent.md` file lands at `<project>/.github/agents/<name>.agent.md` with `description:` present in frontmatter, then open **Tools → GitHub Copilot → Customizations → Chat Agents → Workspace** and verify the agent appears in the table.
- [ ] **Bad input is rejected** — Settings, paste `ext::sh -c id` as a URL. Should show the validation warning dialog, not accept it.
- [ ] **Network failure is graceful** — disable wifi, click Refresh. Notification appears with the git error; the IDE doesn't freeze; subsequent refreshes work after reconnecting.
- [ ] **Headless CLI works** — in a terminal: `idea agentry list` (after `./gradlew buildPlugin` and installing). Skills print to stdout. Exit code 0.
- [ ] **Repeat in PyCharm Community** — `./gradlew runIde -PplatformType=PY`. The tool window and core flows must work identically.

If any of these fail, file an issue and block the release.

## Pull-request checklist

- [ ] `./gradlew test` passes locally
- [ ] `./gradlew verifyPlugin` passes locally (slow; CI also runs it)
- [ ] `./gradlew buildPlugin` produces a `.zip` under `build/distributions/`
- [ ] For UI-touching changes: smoke checklist run at least partially
- [ ] No untracked files in `build/`, `.gradle/`, or `.idea/`
- [ ] If you added a new file that shells out, modifies the filesystem, or accepts untrusted input — it has matching `InputValidation` coverage

## Architectural ground rules

- **Plugin code is thin.** Heavy logic (git fetch, manifest resolution, network) belongs in services so it can be replaced in tests via `ServiceContainerUtil.replaceService`. Avoid `new RegistryManager()` at call sites — use `RegistryManager.getInstance()`.
- **No EDT IO.** File reads/writes and `git` calls go through `Task.Backgroundable`. The platform has assertions for "slow operation on EDT" — they will fire in newer SDKs if you regress.
- **Validate every external input.** URLs, refs, and skill names from `.agentry/config.yaml` or registry manifests all flow through `InputValidation`. Any new code that consumes these must call the corresponding validator before passing to `git`, `File`, or `ProcessBuilder`.
- **Refresh the VFS** after any filesystem mutation the IDE might already have indexed (`VfsUtil.markDirtyAndRefresh`). Otherwise the Project view shows stale state.
- **Make actions, not handlers.** New UI buttons should fire registered `AnAction`s via `ActionManager.fireAction(id)` — never inline the logic. This keeps the agent-callable surface in lock-step with the human-clickable surface.
