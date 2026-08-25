# UIFlow 2 custom blocks

Blockly custom-block definitions that wrap [Periph](https://github.com/tuhde/Periph) MicroPython chip
drivers for [M5Stack UIFlow 2](https://uiflow2.m5stack.com/). Each chip gets its own folder containing a
`blocks.json` manifest plus one MicroPython file per block, following the format used by the
[UIFlow Block Maker](http://block-maker.m5stack.com/) and the community
[uiflow-custom-block-generator](https://github.com/3110/uiflow-custom-block-generator) tool.

This is source-level integration only — no `.m5b` binaries are committed here, and nothing is
pre-bundled into UIFlow's palette. Anyone can import a chip's blocks into their own project (see below).

## Layout

```
uiflow/
  <category>/
    <chip>/
      blocks.json              # category, color, and block/param definitions
      <chip>_init.py           # execute block: opens the connection and constructs the driver
      <chip>_read_<value>.py   # value block(s): return one reading each
```

All blocks share the `Periph` category and the `#a6bbcf` color, matching the palette color already used by
the [Node-RED nodes](../../nodejs/packages/) so Periph-backed tooling looks consistent across integrations.

Each `<chip>_init` block constructs the driver via `periph.connection.i2c_auto.I2CConnection`
(auto-detects the platform; on UIFlow 2 this resolves to `machine.I2C`) and stores it in a module-level
global (e.g. `_periph_aht21`) that the chip's other blocks reference. Place the init block once, before any
read blocks, in your flow — matching how M5Stack's own device blocks work.

Read blocks call whichever *Full*-class getter returns a single value, so each block plugs directly into a
number/string slot elsewhere in the flow rather than returning a dict. Every wrapped method already exists
on the underlying driver in `python/periph/chips/<category>/<chip>.py` — the blocks add no new logic.

## Chips

| Chip | Category | Blocks |
|------|----------|--------|
| [AHT21](environmental/aht21/) | environmental | `aht21_init`, `aht21_read_temperature`, `aht21_read_humidity` |
| [ENS160](gas/ens160/) | gas | `ens160_init`, `ens160_read_aqi`, `ens160_read_tvoc`, `ens160_read_eco2` |

## Using these blocks

**Option A — UIFlow Block Maker.** Open [block-maker.m5stack.com](http://block-maker.m5stack.com/), create a
block using the same `category`/`color`/`params` as the chip's `blocks.json`, and paste in the matching
`.py` file's contents for each block's code field.

**Option B — uiflow-custom-block-generator.** Install the generator and point it at a chip's `blocks.json`
to produce an importable `.m5b` file:

```sh
pip install git+https://github.com/3110/uiflow-custom-block-generator
python -m uiflow_custom_block_generator python/uiflow/environmental/aht21/blocks.json
```

Then import the generated `.m5b` into your UIFlow 2 project via **Extension → Import**.

Either way, the `periph` package must be present on the device's filesystem (or frozen into the firmware)
for the generated blocks to import it at runtime.
