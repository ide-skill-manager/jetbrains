# Agentry fixture project

A sample project used by Agentry contributors to exercise the plugin end-to-end during development.

## How to use

```bash
./gradlew runIde
```

When the sandbox IDE opens, choose **File → Open…** and point it at this directory.

What should happen:

1. A balloon notification appears: *"Agentry configuration detected"*.
2. Click **Sync once** (or **Trust and auto-sync**).
3. Skills listed in `.agentry/config.yaml` are fetched from the configured registry and installed under `.claude/skills/`.
4. The Project view picks the new files up automatically.

## Customising the registry

`.agentry/config.yaml` ships with a placeholder registry URL. Replace it with one of:

- A public test registry on GitHub
- A local bare repo you set up for testing:
  ```bash
  mkdir -p /tmp/agentry-test-registry && cd /tmp/agentry-test-registry
  git init --bare
  # (then push a repo with a skill.json or package.json to it)
  ```
  Note that `file://` URLs are rejected by `InputValidation` (security). For local
  testing point a tiny local http git server at the bare repo, or use SSH form
  pointing at a host alias in your `~/.ssh/config`.
- A registry your team already maintains — paste the URL into the fixture's config.

## What's exercised by opening this project

- `.agentry/config.yaml` parsing (`AgentryProjectConfig.loadFrom`)
- Trust-prompt notification flow (`AgentryStartupActivity`)
- Sync pipeline (`ProjectSyncService.syncBlocking`)
- `git clone --depth=1 --branch <ref>` path in `RegistryManager`
- Skill copy with VFS refresh (`SkillInstaller`)
- `.agentry/config.yaml` live-reload via the registered VFS listener (try editing the file while the project is open after enabling auto-sync)
