# Chip and transport workflow

Orchestration procedure for new chips and transports. Referenced from `CLAUDE.md`.

**Claude Code** handles orchestration: planning, speccing, coordinating work, and deciding what to build.
**OpenCode** handles implementation: writing the actual source code.

## Orchestration entry points

| Method | When to use |
|---|---|
| GitHub Issue | Formal request; creates a traceable record |
| `backlog.md` | Offline or batch work; Claude Code processes pending items |
| Direct conversation | Ad-hoc exploration or one-off requests |

## Flow for chip issues
1. Claude Code obtains the datasheet from the issue (download PDF attachment or fetch URL) and commits it to `datasheets/<category>/<chipname>.pdf`
2. Claude Code reads the datasheet and produces a spec in `specs/<category>/` using `specs/_template_chip.md`
   - For chips with I²C transport, also add the chip's entry to `registry/chips.json` (address(es), identity register or `null`, probe safety — see `specs/feature_i2c_discovery.md` §4) with `"driver": null` (no driver exists yet; OpenCode sets it to the driver name on implementation) and regenerate the discovery tables with `node registry/scripts/generate.js`
3. Claude Code creates a wiki page `<ChipName>.md` with key parameters, address table, quick-start snippets, and platform matrix; adds it to the wiki sidebar and links it from the Supported-Chips and Home pages (push the wiki repo, then `git add wiki` in the main repo)
4. Claude Code adds a row for the chip to the root `README.md` "Supported chips" table (alphabetical), with every language column left empty (not implemented yet; `-` is reserved for "deliberately never implemented"); OpenCode flips each cell to `✓` as that language is implemented
5. Claude Code lands steps 1–4 on `main` through a **no-CI prep PR**: commit on any branch (conventionally `prep/<chip>`), open a PR to `main`, and end every commit message **and** the PR title (which becomes the squash-merge message) with `[skip ci]`; squash-merge it. Nothing in this PR needs CI (no source code), and committing it directly to `main` would trigger a CI run (#230). Since CI is skipped, run `node registry/scripts/validate.js --check`, `node registry/scripts/generate.js --check`, and the `--check` mode of the README generators locally before opening the PR
6. Claude Code posts a **"Ready for implementation"** comment on the issue — this is what OpenCode uses to find its work
7. Claude Code removes the label `needs-spec` and adds the label `needs-implementation` and all relevant `transport:*` labels in the issue.
8. The chosen coder tool implements against the spec on its own `impl/<chip>/<engine>-<model>` branch cut from `main` (no `feature/<chip>` branch exists) and opens a PR to `main` with CI enabled

## Flow for transport issues
1. Claude Code obtains the protocol reference from the issue (PDF attachment or URL); if it is a well-known standard with no single document, Claude Code uses its own knowledge
2. Claude Code produces a spec at `specs/transport_<name>.md` using `specs/_template_transport.md`; no datasheet is committed (transports live outside `datasheets/`), and it lands on `main` via a `[skip ci]` PR as for chips
3. Claude Code posts a **"Ready for implementation"** comment on the issue
4. Claude Code removes the label `needs-spec` and adds the label `needs-implementation` in the issue.
5. The coder tool implements, on an `impl/transport-<name>/<engine>-<model>` branch cut from `main`, the transport across all applicable platforms; it does **not** implement any chip driver

## Ready-for-implementation comment format
```
## Ready for implementation

- **Spec:** `specs/<category>/<chip>.md`   ← or `specs/transport_<name>.md`
- **Stages:** Minimal, Full               ← chips only; transports have no stages

See `AGENTS.md` for implementation guidance.
```

## Spec templates
- `specs/_template_chip.md` — for new chip drivers
- `specs/_template_chip_io_expander.md` — for IO expander chips (adds GPIO interface section)
- `specs/_template_transport.md` — for new transport implementations
