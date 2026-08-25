# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A multi-language library for peripheral chips (sensors, actuators, etc.) connected via SPI, I²C, or other transports.

Implementations:
- **Python** — three supported targets: MicroPython (primary, embedded), CircuitPython (embedded), Linux kernel (host, via `smbus2` / `/dev/i2c-N`)
- **C++** — Arduino, Linux GCC, Zephyr RTOS, ESP-IDF, and Raspberry Pi Pico SDK
- **Node.js / Node-RED** — plain JS drivers (`periph` npm package) + per-category Node-RED node packages (`node-red-contrib-periph-<category>`)
- **Rust** — two targets: Linux host (via `linux-embedded-hal`) and ESP32-S3 bare-metal (via `esp-hal`); generic over `embedded-hal` 1.0
- **Java / Kotlin / Groovy** — JVM target: Linux host via i2c-dev / FFM (no native libraries); connections in Java (shared by all three); drivers in Java, Kotlin, and Groovy
- **Go** — two targets: Linux host (standard `go build`, raw syscalls via `golang.org/x/sys/unix`, no cgo) and TinyGo (`tinygo build`, bare-metal embedded via the `machine` package; hardware-in-loop tests pinned to a Raspberry Pi Pico W)

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
| **Minimal** | Primary use case only; works out of the box with sensible defaults; no configuration required beyond the connection | Base class |
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
    connection/         # Connection base + platform bus implementations (i2c_micropython.py, i2c_circuitpython.py, i2c_linux.py), plus InputPin/OutputPin
    chips/
      <category>/       # One module per chip, grouped by category
  examples/
    <category>/
      <chip>/           # minimal.py, complete.py, demo.py
  uiflow/
    <category>/
      <chip>/           # blocks.json + one .py per block — M5Stack UIFlow 2 Blockly custom blocks wrapping the chip driver
  tests/
cpp/
  CMakeLists.txt        # Zephyr module build file (see zephyr/module.yml) — builds every chip driver into a "periph" library
  zephyr/
    module.yml           # Zephyr module manifest (name: periph); consumed via ZEPHYR_EXTRA_MODULES, not west manifest discovery
  src/
    connection/         # Pure virtual Connection base + SPI/I2C/NeoPixel implementations (Arduino, Linux, Zephyr, ESP-IDF, Pico SDK variants), plus InputPin/OutputPin
    chips/
      <category>/       # One header+source per chip, grouped by category
  examples/
    linux/
      <category>/
        <Chip>/
          minimal/      # main.cpp  (compiled with g++ directly)
          complete/
          demo/
    arduino/
      <category>/
        <Chip>/
          minimal/      # minimal.ino  (dir name must match .ino filename)
          complete/     # complete.ino
          demo/         # demo.ino
    zephyr/
      <category>/
        <Chip>/
          minimal/      # main.cpp, CMakeLists.txt, prj.conf  (standalone Zephyr app)
          complete/
          demo/
    espidf/
      <category>/
        <Chip>/
          minimal/      # CMakeLists.txt, main/CMakeLists.txt, main/main.cpp, sdkconfig.defaults
          complete/
          demo/
    picosdk/
      <category>/
        <Chip>/
          minimal/      # CMakeLists.txt, src/main.cpp
          complete/
          demo/
  library.properties    # Arduino library metadata
nodejs/
  package.json          # npm workspaces root
  packages/
    periph/             # Single plain JS driver package (name: "periph")
      src/
        connection/     # I2C, SPI, NeoPixel connection implementations, plus InputPin/OutputPin
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
      connection/       # Connection<BUS> wrapper (generic over embedded-hal I2C/SPI) + periph-owned custom-protocol connections (neopixel, etc.)
      chips/
        <category>/     # One module per chip; no_std, generic over embedded-hal traits
  examples/
    linux/
      <category>/
        <chip>/
          minimal/      # Cargo.toml + src/main.rs  (Linux host; in workspace)
          complete/
          demo/
    embedded/           # excluded from workspace; build per-crate
      esp32s3/          # current target; rp2040/, stm32f4/, etc. added here as needed
        <category>/
          <chip>/
            minimal/    # Cargo.toml + src/main.rs + rust-toolchain.toml + .cargo/config.toml
            complete/
            demo/
  tests/
    <category>/
      <chip>_test/      # Linux integration test crate
      <chip>_test_esp32s3/  # ESP32-S3 smoke test (excluded from workspace)
jvm/
  pom.xml               # Parent POM: groupId=it.uhde, artifactId=periph, multi-module
  periph-connection/    # Java-only connection library (JPMS module: it.uhde.periph.connection)
    src/main/java/
      module-info.java          # exports it.uhde.periph.connection
      it/uhde/periph/connection/ # Connection interface + Linux i2c-dev implementations (FFM), plus InputPin/OutputPin
    pom.xml
  periph-java/          # Java chip drivers (JPMS module: it.uhde.periph)
    src/
      main/java/
        module-info.java        # exports it.uhde.periph.chips.*; requires it.uhde.periph.connection
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
  tests/                # JBang integration test scripts (run on Linux hardware)
    <category>/
      <chip>/           # <chip>Test.java (or .kt / .groovy)  (//DEPS it.uhde:periph-java:...)
go/
  go.mod                # Module github.com/tuhde/Periph/go
  periph/
    connection/         # Connection interface + <protocol>_linux.go / <protocol>_tinygo.go pairs, selected by build tags, plus InputPin/OutputPin
    chips/
      <category>/       # One file per chip, grouped by category; package name drops underscores (adc_dac/ -> package adcdac)
  examples/
    linux/
      <category>/
        <chip>/
          minimal/minimal.go     # go build ./go/examples/linux/.../<chip>/minimal/
          complete/complete.go
          demo/demo.go
    tinygo/
      <category>/
        <chip>/
          minimal/minimal.go     # tinygo build -target=pico-w ./go/examples/tinygo/.../<chip>/minimal/
          complete/complete.go
          demo/demo.go
  tests/
    <category>/
      <chip>_test/          # Linux host
      <chip>_test_tinygo/   # Raspberry Pi Pico W smoke test
sigrok/
  <chip>/               # Sigrok protocol decoder
    pd.py               # Decoder implementation
    __init__.py         # Re-exports Decoder
  tests/
    <chip>/             # Captured .sr session for manual verification
```

Each chip driver depends only on the `Connection` abstraction, never on a concrete bus implementation.

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

Source files carry full inline API documentation in the platform-native format (Python docstrings, C++ Doxygen, JSDoc, Rust `///`, JVM Javadoc/KDoc/Groovydoc). The three example tiers serve as usage documentation, with the demo as the narrative entry point for new users.

## Status

No build system configured yet. Update this file with build, lint, and test commands once established.
