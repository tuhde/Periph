# Chip Spec: <ChipName>

**Manufacturer:** <Manufacturer>  
**Datasheet:** `datasheets/<category>/<filename>`  
**Category:** <category directory, e.g. environmental, imu, temperature>  
**Transports:** <SPI | I²C | both>

## Overview

<!-- One paragraph: what the chip does and why you'd use it. -->

## Transport Configuration

### I²C
- **Address:** `0x??` (default) — `0x??` (alternate, if applicable)
- **Max clock:** <e.g. 400 kHz>

### SPI
- **Mode:** CPOL=? CPHA=? (Mode ?)
- **Max clock:** <e.g. 10 MHz>
- **Bit order:** MSB first
- **CS active:** low

## Pin Configuration

<!-- Include only if the chip has an INT or EN pin. Remove entirely for chips with no external GPIO signals beyond the bus. -->

| Pin | Active | Notes |
|-----|--------|-------|
| INT | low, open-drain | interrupt output — requires external pull-up |
| EN  | high | enable / power; float or drive high to power chip |

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00`  | NAME | R   | `0x00`| |

### Bit Fields

#### `REGISTER_NAME` (`0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4  | FIELD_A | |
| 3:0  | FIELD_B | |

## Initialization Sequence

1. <step>
2. <step>
3. Wait <N> ms for <reason>

## Interrupt

<!-- Remove this section for Level-0 chips (no INT output).
     Level 1 = single fixed condition; Level 2 = selectable sources; Level 3 = multiple INT lines.
     See specs/feature_connection_design.md for the full design. -->

| Property | Value |
|----------|-------|
| INT pin | active-low, open-drain — requires external pull-up |
| Level | 1 / 2 / 3 |
| Condition(s) | e.g. data-ready; threshold exceeded; alarm |
| Clear mechanism | read status register / write clear bit |

### Interrupt sources
<!-- Only for Level 2/3 chips. Delete for Level 1. -->

| Constant | Value | Condition |
|----------|-------|-----------|
| `SOURCE_DATA_READY` | `0x01` | New measurement available |
| `SOURCE_THRESHOLD`  | `0x02` | Configured threshold crossed |

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback)` | Subscribe; callback receives status int |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Read & clear interrupt-status register |
| `enable_interrupt` | `enable_interrupt(source)` | Enable one interrupt source *(Level 2/3 only)* |
| `disable_interrupt` | `disable_interrupt(source)` | Disable one interrupt source *(Level 2/3 only)* |

### Status register bit layout

| Bit | Constant | Meaning |
|-----|----------|---------|
| 0 | `SOURCE_DATA_READY` | New sample ready |
| 1 | `SOURCE_THRESHOLD` | Threshold crossed |

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: expose the chip's primary use case with a simple, user-friendly interface. No configuration required beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Runs initialization sequence with sensible defaults |
| `read_<value>` | — | float | Unit: <e.g. °C> |

**Sensible defaults:** <!-- List the register settings baked in for Minimal -->

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `configure` | ... | — | Exposes all relevant settings |
| `read_<value>` | — | float | |

**Additional configuration options:** <!-- List all settings exposed in Full -->

## Data Conversion

<!-- Formulas mapping raw register values to real-world units. -->

```
value = raw * <scale> + <offset>
```

## Node-RED

Node name: `periph-<chip>`  
Package: `node-red-contrib-periph-<category>`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message   | `{ <key>: <value>, ... }`   | <!-- List the readings exposed --> |

<!-- Describe the config panel fields (I2C address, bus number, etc.) -->

### Demo flow

<!-- Describe the Node-RED demo flow: which nodes are wired together and what the flow demonstrates. -->

## Examples

### Demo

<!-- Describe the scenario the demo example should implement. Be specific:
     what it measures, what it prints/does, what makes it interesting to watch.
     The minimal and complete examples are fully implied by the API tables above.

     Comment rules (see AGENTS.md § Example tiers for the full spec):
     - All three tiers: trailing Tier-1 signature comment on every call.
     - Complete adds: a Tier-2 "what it does" line immediately below each call.
     - Demo uses: Tier-3 block comments at logical section boundaries instead of Tier-2 lines. -->

## Timing Constraints

<!-- Any delays, startup times, or measurement durations the driver must respect. -->

## Implementation Notes

<!-- Quirks, errata, or non-obvious datasheet behavior. -->

## Sigrok Decoder

<!-- One paragraph describing what the sigrok decoder annotates: which registers,
     which bit fields, which computed values are shown. Mention input transport
     (i2c or logic), the decoder id, and the addresses / channels it matches. -->

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/<category>/<chip>.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/<category>/<chip>/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/<category>/<chip>/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/<category>/<chip>/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/<category>/<chip>_test.py` (MicroPython)
- [ ] Tests `python/tests/<category>/<chip>_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/<category>/<chip>_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/<category>/<Chip>.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/<category>/<Chip>.cpp`
- [ ] Examples `cpp/examples/<Chip>_Minimal/<Chip>_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/<Chip>_Complete/<Chip>_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/<Chip>_Demo/<Chip>_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/<Chip>_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/<Chip>_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/<Chip>_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/<category>/<chip>_test/<chip>_test.ino` (Arduino)
- [ ] Tests `cpp/tests/<category>/<chip>_test_linux/<chip>_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/<category>/<chip>_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/<category>/<chip>.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/<category>/<chip>/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/<category>/<chip>/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/<category>/<chip>/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/<category>/<chip>_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-<category>/nodes/<chip>/<chip>.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-<category>/nodes/<chip>/<chip>.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-<category>/examples/<chip>/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/<category>/<chip>.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/<chip>_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/<chip>_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/<chip>_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/<category>/<chip>_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/<category>/<chip>_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/<category>/<Chip>Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/<category>/<Chip>Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/<category>/<Chip>Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/<category>/<Chip>Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/<category>/<Chip>Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/<category>/<Chip>Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/<category>/<chip>/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/<category>/<chip>/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/<category>/<chip>/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/<category>/<chip>/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/<category>/<chip>/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/<category>/<chip>/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/<category>/<chip>/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/<category>/<chip>/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/<category>/<chip>/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/<category>/<chip>/<Chip>Test.java` (Pi hardware, JBang)

### Sigrok
- [ ] Decoder `sigrok/<chip>/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/<chip>/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only
