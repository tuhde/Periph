# UIFlow 2 native custom blocks

Blockly custom-block definitions in the `.m5b2` format used by **M5Stack UIFlow 2**
(uiflow2.m5stack.com), wrapping [Periph](https://github.com/tuhde/Periph) MicroPython chip
drivers. See [`UIFLOW2_BLOCKS.md`](UIFLOW2_BLOCKS.md) for the full format reference and
authoring workflow — the short version: each chip gets one annotated Python wrapper class
(`<Chip>.py`) and one exported `<Chip>.m5b2`, hand-built in the UiFlow 2 web IDE's Block
Designer. There is no generator for this format, unlike [`python/uiflow1/`](../uiflow1/)'s
`.m5b` (UiFlow 1), which is a separate, non-interoperable deliverable — see
`UIFLOW2_BLOCKS.md` §0.

> **Verification status:** the `.m5b2` files here were hand-authored directly from the
> documented format rather than exported from the real UiFlow 2 Block Designer (no browser
> access was used to build them). The wrapper class and `data`/`members` portions follow
> the format precisely, but `uiflow2.jscode` and `uiflow2.toolbox` are a best-effort
> reconstruction of undocumented internals and have **not** been confirmed to import
> correctly into the real app. Import a chip's `.m5b2` into UiFlow 2 and check it before
> relying on it; if the Designer doesn't accept it, rebuild that file from the Designer's
> own export instead of trusting this reconstruction — see `UIFLOW2_BLOCKS.md` §6
> Troubleshooting.

## Layout

```
uiflow2/
  <category>/
    <chip>/
      <Chip>.py      # annotated wrapper class — the source of truth (pyCode)
      <Chip>.m5b2     # exported block package
```

## Chips

| Chip | Category | Blocks |
|------|----------|--------|
| [AHT21](environmental/aht21/) | environmental | init, `read_temperature`, `read_humidity` |

## Using these blocks

Import a chip's `<Chip>.m5b2` into UiFlow 2 via **Extension → Import**. The init block
constructs the driver over I²C (bus/address inputs) and names the instance; every other
block calls the matching method on that named instance, mirroring how UiFlow 2's own
device blocks work. Place the init block once, before any other block for that chip, in
your flow.

The `periph` package must be present on the device's filesystem (or frozen into the
firmware) for the wrapped driver to import at runtime.
