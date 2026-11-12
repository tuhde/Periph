# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A multi-language library for peripheral chips (sensors, actuators, etc.) connected via SPI, I²C, or other transports.

Implementations:
- **Python** — three supported targets: MicroPython (primary, embedded), CircuitPython (embedded), Linux kernel (host, via `smbus2` / `/dev/i2c-N`); M5Stack Blockly custom blocks wrap the MicroPython drivers rather than adding a fourth target — `python/uiflow1/` for UiFlow 1's `.m5b` format (generated), `python/uiflow2/` for UiFlow 2's native `.m5b2` format (hand-built in the UiFlow 2 Block Designer, no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md`); the two formats don't interoperate, so both are mandatory per chip
- **C++** — Arduino, Linux GCC, Zephyr RTOS, ESP-IDF, Raspberry Pi Pico SDK, and STM32Cube (bare-metal HAL, NUCLEO-F411RE reference board)
- **Node.js / Node-RED** — plain JS drivers (`periph` npm package) + per-category Node-RED node packages (`node-red-contrib-periph-<category>`)
- **Rust** — two targets: Linux host (via `linux-embedded-hal`) and ESP32-S3 bare-metal (via `esp-hal`); generic over `embedded-hal` 1.0
- **Java / Kotlin / Groovy** — JVM target: Linux host via i2c-dev / FFM (no native libraries); connections in Java (shared by all three); drivers in Java, Kotlin, and Groovy
- **Go** — two targets: Linux host (standard `go build`, raw syscalls via `golang.org/x/sys/unix`, no cgo) and TinyGo (`tinygo build`, bare-metal embedded via the `machine` package; hardware-in-loop tests pinned to a Raspberry Pi Pico W)

## Workflow

**Claude Code** orchestrates (planning, speccing, deciding what to build); **OpenCode** implements the source code. Entry points are GitHub issues, `backlog.md`, or direct conversation.

New chips and transports follow a fixed spec → prep PR (`[skip ci]`) → "Ready for implementation" comment → `impl/...` branch procedure. **Read `docs/chip-workflow.md` before starting one**; it holds the step-by-step flows, the comment format, label updates, and the spec template list. Implementation guidance is in `AGENTS.md`; test runners in `TESTING.md`.

## Implementation stages

Every chip driver is implemented in two stages:

| Stage | Goal | Class relationship |
|-------|------|--------------------|
| **Minimal** | Primary use case only; works out of the box with sensible defaults; no configuration required beyond the connection | Base class |
| **Full** | Complete chip functionality | Extends Minimal |

The Full class inherits Minimal and adds the rest — it never duplicates. Specs define both APIs explicitly, including which register defaults are baked into Minimal.

## Repository layout

Top-level dirs: `specs/`, `datasheets/`, `registry/`, `python/`, `cpp/`, `nodejs/`, `rust/`, `jvm/`, `go/`, `sigrok/`, `docs/`, plus the `wiki` submodule. `specs/<category>/` and `datasheets/<category>/` mirror each other; run `ls` for the current category list (`adc_dac` maps to the npm package `node-red-contrib-periph-adc-dac`, underscore → hyphen).

Every language follows the same shape: `connection/` (Connection base, platform bus implementations, InputPin/OutputPin), `storage/` (BlockDevice + RAM mock), and `chips/<category>/` (one module per chip). Each chip driver depends only on the `Connection` abstraction, never on a concrete bus implementation.

Examples live at `<lang>/examples/[<platform>/]<category>/<chip>/{minimal,complete,demo}`. Non-obvious details:

- **Python:** `python/periph/chips/<category>/`; examples in `python/examples/<category>/<chip>/{minimal,complete,demo}.py`. Three targets (MicroPython, CircuitPython, Linux `smbus2`).
- **UIFlow:** `python/uiflow1/<category>/<chip>/` (`<chip>.json` + one `.py` per block + generated `.m5b`; run `python/uiflow1/generate.sh`, CI checks it with `--check`) and `python/uiflow2/<category>/<chip>/` (`<Chip>.py` wrapper + hand-built `<Chip>.m5b2`; see `python/uiflow2/UIFLOW2_BLOCKS.md`). Both are mandatory per chip.
- **C++:** `cpp/src/{connection,storage,chips/<category>}`; also a Zephyr module (`cpp/CMakeLists.txt`, `cpp/zephyr/module.yml`) and Arduino metadata (`cpp/library.properties`). Example platforms: `linux` (`main.cpp`), `arduino` (`<tier>/<tier>.ino`, directory name must match the `.ino`), `zephyr` (`main.cpp`, `CMakeLists.txt`, `prj.conf`), `espidf` (`main/main.cpp`, `sdkconfig.defaults`), `picosdk` (`src/main.cpp`), `stm32cube` (`Core/Src/main.cpp`, NUCLEO-F411RE).
- **Node.js:** npm workspaces under `nodejs/packages/`. `periph/` is the plain driver package; `node-red-contrib-periph-<category>/` has `nodes/<chip>/<chip>.{js,html}` and `examples/<chip>/demo.json`.
- **Rust:** workspace at `rust/Cargo.toml`; `rust/periph/src/...`; Linux examples in the workspace under `rust/examples/linux/`, embedded ones (excluded, built per crate) under `rust/examples/embedded/esp32s3/`; tests in `rust/tests/<category>/<chip>_test` and `<chip>_test_esp32s3`.
- **JVM:** `jvm/periph-connection` (Java-only, shared), `periph-java`, `periph-kotlin`, `periph-groovy` (package `it.uhde.periph.chips.<category>`); JBang examples in `jvm/examples/{java,kotlin,groovy}/`; integration tests in `jvm/tests/`.
- **Go:** module `github.com/tuhde/Periph/go`; `go/periph/connection/` uses `<protocol>_linux.go` / `<protocol>_tinygo.go` build-tag pairs; package names drop underscores (`adc_dac` → `adcdac`); tests in `go/tests/<category>/<chip>_test` and `<chip>_test_tinygo` (Pico W).
- **Sigrok:** `sigrok/<chip>/{pd.py,__init__.py}`; sample captures in `sigrok/tests/<chip>/`.

## Examples

Each chip has three examples per language: `minimal` (uses `*Minimal`, construct and read in a loop), `complete` (`*Full`, every API method), `demo` (`*Full`, the spec's Demo scenario). The tiers are additive trailing/block comment styles; the exact format and templates are in `AGENTS.md`.

## Units

All values use SI units exclusively — no imperial units, no non-SI conventional units. Examples: meters (not feet/inches), kilograms (not pounds), Pascals (not PSI or bar), Celsius or Kelvin (not Fahrenheit), seconds (not minutes/hours unless a compound unit like km/h is standard), volts, amperes, ohms, etc.

This applies to specs, source code, comments, examples, and documentation.

## Block/node color

Every Periph-authored Blockly custom block (UIFlow 1 `.m5b`, UIFlow 2 `.m5b2`) and Node-RED
node uses a single shared accent color, `#C084FC`, so Periph-backed tooling looks consistent
across integrations. Use it for any new chip's blocks/nodes; don't invent a per-chip or
per-category color.

## Documentation

The spec (`specs/<category>/<chip>.md`) is the reference documentation — register maps, API tables, data conversion formulas, and timing constraints all live there. No separate reference docs; `docs/` holds process documents only.

Source files carry full inline API documentation in the platform-native format (Python docstrings, C++ Doxygen, JSDoc, Rust `///`, JVM Javadoc/KDoc/Groovydoc). The three example tiers serve as usage documentation, with the demo as the narrative entry point for new users.

## Build, test, and generated files

Per-platform test runners: `TESTING.md`. Generated files are never hand-edited; CI runs these in `--check` mode:

- `node registry/scripts/validate.js --check`, `node registry/scripts/generate.js --check` (I²C discovery registry)
- README generators (`scripts/generate-readme(s).js` in `cpp/`, `nodejs/`, `rust/`, `python/`)
- `python/uiflow1/generate.sh --check`
- C++: `cpp/scripts/build-all.sh` builds every app on all platforms
