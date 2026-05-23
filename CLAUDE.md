# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A multi-language library for peripheral chips (sensors, actuators, etc.) connected via SPI, I²C, or other transports.

Implementations:
- **Python** — three supported targets: MicroPython (primary, embedded), CircuitPython (embedded), Linux kernel (host, via `smbus2` / `/dev/i2c-N`)
- **C++** — Arduino, Linux GCC, and Zephyr RTOS
- **Node.js / Node-RED** — plain JS drivers (`periph` npm package) + per-category Node-RED node packages (`node-red-contrib-periph-<category>`)
- **Rust** — two targets: Linux host (via `linux-embedded-hal`) and ESP32-S3 bare-metal (via `esp-hal`); generic over `embedded-hal` 1.0
- **Java / Kotlin / Groovy** — JVM target: Raspberry Pi via Pi4J; transports in Java (shared by all three); drivers in Java, Kotlin, and Groovy

## Workflow

**Claude Code** handles orchestration: planning, speccing, coordinating work, and deciding what to build.  
**OpenCode** handles implementation: writing the actual source code.

### Orchestration entry points

| Method | When to use |
|---|---|
| GitHub Issue | Formal request; creates a traceable record |
| `backlog.md` | Offline or batch work; Claude Code processes pending items |
| Direct conversation | Ad-hoc exploration or one-off requests |

### Flow for chip issues
1. Claude Code obtains the datasheet from the issue (download PDF attachment or fetch URL) and commits it to `datasheets/<category>/<chipname>.pdf`
2. Claude Code reads the datasheet and produces a spec in `specs/<category>/` using `specs/_template_chip.md`
3. Claude Code creates a wiki page `<ChipName>.md` with key parameters, address table, quick-start snippets, and platform matrix; adds it to the wiki sidebar and links it from the Supported-Chips and Home pages
4. Claude Code posts a **"Ready for implementation"** comment on the issue — this is what OpenCode uses to find its work
5. Claude Code removes the label `needs-spec` and adds the label `needs-implementation` and all relevant `transport:*` labels in the issue.
6. OpenCode implements against the spec on the feature branch

### Flow for transport issues
1. Claude Code obtains the protocol reference from the issue (PDF attachment or URL); if it is a well-known standard with no single document, Claude Code uses its own knowledge
2. Claude Code produces a spec at `specs/transport_<name>.md` using `specs/_template_transport.md`; no datasheet is committed (transports live outside `datasheets/`)
3. Claude Code posts a **"Ready for implementation"** comment on the issue
4. Claude Code removes the label `needs-spec` and adds the label `needs-implementation` in the issue.
5. OpenCode implements the transport across all applicable platforms; it does **not** implement any chip driver

### Ready-for-implementation comment format
```
## Ready for implementation

- **Spec:** `specs/<category>/<chip>.md`   ← or `specs/transport_<name>.md`
- **Branch:** `feature/<chip>`             ← or `feature/transport-<name>`
- **Stages:** Minimal, Full               ← chips only; transports have no stages

See `AGENTS.md` for implementation guidance.
```

### Spec templates
- `specs/_template_chip.md` — for new chip drivers
- `specs/_template_chip_io_expander.md` — for IO expander chips (adds GPIO interface section)
- `specs/_template_transport.md` — for new transport implementations

## Implementation stages

Every chip driver is implemented in two stages:

| Stage | Goal | Class relationship |
|-------|------|--------------------|
| **Minimal** | Primary use case only; works out of the box with sensible defaults; no configuration required beyond the transport | Base class |
| **Full** | Complete chip functionality | Extends Minimal |

The Full class inherits Minimal and adds the rest — it never duplicates. Specs define both APIs explicitly, including which register defaults are baked into Minimal.

## Repository layout

```
specs/                  # Chip and transport specs (produced by Claude Code, consumed by OpenCode)
  <category>/           # One subdirectory per category (see below)
  _template_chip.md
  _template_transport.md
datasheets/
  <category>/           # Mirrors specs/ category structure
python/
  periph/
    transport/          # Abstract base + platform transports (i2c_micropython.py, i2c_circuitpython.py, i2c_linux.py)
    chips/
      <category>/       # One module per chip, grouped by category
  examples/
    <category>/
      <chip>/           # minimal.py, complete.py, demo.py
  tests/
cpp/
  src/
    transport/          # Pure virtual Transport interface + SPI/I2C/NeoPixel implementations (Arduino + Linux + Zephyr variants)
    chips/
      <category>/       # One header+source per chip, grouped by category
  examples/
    <Chip>_Minimal/     # <Chip>_Minimal.ino  (dir name must match .ino filename)
    <Chip>_Complete/    # <Chip>_Complete.ino
    <Chip>_Demo/        # <Chip>_Demo.ino
  library.properties    # Arduino library metadata
zephyr/
  src/
    transport/          # Zephyr transport headers (header-only, included from examples/tests)
  examples/
    <Chip>_Minimal/     # src/main.cpp, CMakeLists.txt, prj.conf
    <Chip>_Complete/
    <Chip>_Demo/
  tests/
nodejs/
  package.json          # npm workspaces root
  packages/
    periph/             # Single plain JS driver package (name: "periph")
      src/
        transport/      # I2C, SPI, NeoPixel transport wrappers
        chips/
          <category>/   # One module per chip, grouped by category
      examples/
        <category>/
          <chip>/       # minimal.js, complete.js, demo.js
    node-red-contrib-periph-<category>/  # Per-category Node-RED node packages
      index.js          # Auto-discovers and registers nodes in nodes/
      nodes/
        <chip>/
          <chip>.js     # Node-RED runtime node (wraps periph driver)
          <chip>.html   # Node-RED editor UI (config panel)
      examples/
        <chip>/
          demo.json     # Importable Node-RED flow demonstrating the node
rust/
  Cargo.toml            # Workspace root (library crate + Linux examples/tests; ESP32-S3 test excluded)
  periph/
    src/
      transport/        # embedded-hal transport wrappers (neopixel, etc.)
      chips/
        <category>/     # One module per chip; no_std, generic over embedded-hal traits
  examples/
    <chip>_minimal/     # Cargo.toml + src/main.rs  (Linux host)
    <chip>_complete/
    <chip>_demo/
  tests/
    <category>/
      <chip>_test/      # Linux integration test crate
      <chip>_test_esp32s3/  # ESP32-S3 smoke test (excluded from workspace)
jvm/
  pom.xml               # Parent POM: groupId=it.uhde, artifactId=periph, multi-module
  periph-transport/     # Java-only transport library (JPMS module: it.uhde.periph.transport)
    src/main/java/
      module-info.java          # exports it.uhde.periph.transport; requires com.pi4j
      it/uhde/periph/transport/ # Transport interface + Pi4J I2C/SPI implementations
    pom.xml
  periph-java/          # Java chip drivers (JPMS module: it.uhde.periph)
    src/
      main/java/
        module-info.java        # exports it.uhde.periph.chips.*; requires it.uhde.periph.transport
        it/uhde/periph/chips/
          <category>/   # One class per chip, grouped by category
      test/java/
    pom.xml
  periph-kotlin/        # Kotlin chip drivers (kotlin-maven-plugin; no JPMS)
    src/
      main/kotlin/
        it/uhde/periph/chips/
          <category>/   # One class per chip, grouped by category
      test/kotlin/
    pom.xml
  periph-groovy/        # Groovy chip drivers (gmavenplus-plugin; no JPMS)
    src/
      main/groovy/
        it/uhde/periph/chips/
          <category>/   # One class per chip, grouped by category
      test/groovy/
    pom.xml
  examples/             # JBang scripts — run with: jbang Minimal.java (or .kt / .groovy)
    java/
      <category>/
        <chip>/         # Minimal.java, Complete.java, Demo.java  (//DEPS it.uhde:periph-java:...)
    kotlin/
      <category>/
        <chip>/         # Minimal.kt, Complete.kt, Demo.kt        (//DEPS it.uhde:periph-kotlin:...)
    groovy/
      <category>/
        <chip>/         # Minimal.groovy, Complete.groovy, Demo.groovy (//DEPS it.uhde:periph-groovy:...)
  tests/                # JBang integration test scripts (run on Pi hardware)
    <category>/
      <chip>/           # <chip>Test.java (or .kt / .groovy)  (//DEPS it.uhde:periph-java:...)
```

Each chip driver depends only on the transport abstraction, never on a concrete bus.

The `adc_dac` directory maps to the package name `node-red-contrib-periph-adc-dac` (underscore → hyphen).

### Chip categories

Categories are shared across `specs/`, `datasheets/`, `python/periph/chips/`, `cpp/src/chips/`, `nodejs/packages/periph/src/chips/`, and `nodejs/packages/node-red-contrib-periph-<category>/`:

| Directory | Covers |
|-----------|--------|
| `accelerometer` | Standalone accelerometers |
| `adc_dac` | ADC and DAC converters |
| `color` | Color sensors |
| `comms` | Wireless and wired communication modules (LoRa, RF, etc.) |
| `display` | Display drivers |
| `environmental` | Combined temperature + humidity + pressure |
| `gas` | Gas sensors |
| `gnss` | GNSS / GPS modules |
| `gpio` | GPIO expanders |
| `gyroscope` | Standalone gyroscopes |
| `humidity` | Standalone humidity sensors |
| `imu` | Combined accelerometer + gyroscope |
| `io_expander` | IO expanders |
| `led` | LED drivers |
| `light` | Light and UV sensors |
| `magnetometer` | Magnetometers |
| `memory` | Memory chips (EEPROM, Flash, FRAM, SRAM) |
| `motor` | Motor drivers |
| `power` | Power management |
| `pressure` | Standalone pressure sensors |
| `rfid` | RFID and NFC reader/writer modules |
| `rtc` | Real-time clocks |
| `temperature` | Standalone temperature sensors |
| `tof` | Time-of-flight / distance sensors |
| `other` | Anything that doesn't fit above |

## Examples

Each chip has three examples per language, placed under the `examples/` tree:

| Tier | Class used | Purpose |
|------|-----------|---------|
| `minimal` | `*Minimal` | Simplest possible usage — construct, read primary values in a loop |
| `complete` | `*Full` | Every method in the API exercised |
| `demo` | `*Full` | A real-world scenario from the spec's Demo section |

The three tiers use an additive comment system — each tier includes everything from the tier below it:

**Tier-1 (all three tiers)** — every call gets a trailing signature comment:
```
# <short verb phrase>, (<params>) → <type> <unit>   ← for calls that return a value
# <short verb phrase>, (<param>=<default> <unit>, …)  ← for void calls and constructors
```

**Tier-2 (complete adds)** — a second line immediately below each call explaining what it does:
```python
v = ina.voltage()    # Read bus voltage, () → float V
                     # converts raw bus register to volts (1.25 mV LSB)
```

**Tier-3 (demo adds)** — a multi-line block comment at each logical section boundary explaining context and purpose (the Tier-2 per-call line is dropped in demo):
```python
# --- Configure for noise-sensitive power rail monitoring ---
# 128-sample averaging suppresses switching noise on a noisy 5 V rail;
# continuous mode avoids re-triggering overhead between measurements.
ina.configure(avg=7, vbus_ct=4, vsh_ct=4, mode=7)  # Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None
```

## Units

All values use SI units exclusively — no imperial units, no non-SI conventional units. Examples: meters (not feet/inches), kilograms (not pounds), Pascals (not PSI or bar), Celsius or Kelvin (not Fahrenheit), seconds (not minutes/hours unless a compound unit like km/h is standard), volts, amperes, ohms, etc.

This applies to specs, source code, comments, examples, and documentation.

## Documentation

The spec (`specs/<category>/<chip>.md`) is the reference documentation — register maps, API tables, data conversion formulas, and timing constraints all live there. No separate `docs/` directory.

Source files carry full inline API documentation in the platform-native format (Python docstrings, C++ Doxygen, JSDoc, Rust `///`). The three example tiers serve as usage documentation, with the demo as the narrative entry point for new users.

## Status

No build system configured yet. Update this file with build, lint, and test commands once established.
