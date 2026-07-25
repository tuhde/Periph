# Arduino Library Manager Integration

## Overview

The library uses an orphan `arduino` branch so the Arduino Library Manager
can discover it. The branch has `library.properties` at the repo root, which
is required by the LM — the main branch cannot satisfy this because `library.properties`
lives in `cpp/`.

## Branch layout

```
arduino/
  library.properties   ← version stamped on each release
  src/                 ← from cpp/src/  (all chips and transports)
  examples/            ← from cpp/examples/, .ino only (no *_Zephyr dirs)
```

## Tagging scheme

Each release creates two tags:

| Tag | Branch | Purpose |
|-----|--------|---------|
| `v1.2.3` | `main` | GitHub Actions release trigger; PyPI, npm, crates.io, JVM artifacts |
| `arduino-v1.2.3` | `arduino` | Arduino Library Manager version index |

## How the branch is updated

### Via release.sh (local)

`release.sh` adds an arduino branch update section after pushing `main`:

1. Opens the `arduino` branch in a git worktree at `/tmp/periph-arduino`
2. Clears all content, copies `cpp/src/` and Arduino-only examples
3. Stamps the version in `library.properties`
4. Commits, tags `arduino-vX.Y.Z`, removes the worktree
5. Pushes `arduino` branch and tag to both remotes via `git push all`

### Via GitHub Actions (CI)

The `arduino-branch` job in `.github/workflows/release.yml` runs on every
`vX.Y.Z` tag push and performs the same sync, committing and pushing
`arduino-vX.Y.Z` to `origin`.

## Registering with the Arduino Library Manager

Submit a pull request to
[`arduino/library-registry`](https://github.com/arduino/library-registry)
adding an entry to `repositories.txt`:

```
https://github.com/tuhde/Periph
```

The LM will index the `library.properties` file on the default branch of that
URL. Since the LM needs `library.properties` at the root, point it at the
`arduino` branch by including `branch` in the submission:

```yaml
- name: Periph
  repository: https://github.com/tuhde/Periph
  branch: arduino
```

Once registered, every `arduino-vX.Y.Z` tag on the `arduino` branch will
appear as a new version in the Arduino IDE Library Manager.
