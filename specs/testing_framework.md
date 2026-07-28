# Spec: Test Framework — Unit / HIL / Conformance

**Scope:** cross-cutting rework of the test scripts and test levels for every language (`cpp`, `python`, `nodejs`, `rust`, `jvm`, `go`). No datasheet; this spec is produced and consumed the same way a `specs/transport_<name>.md` is, but lives at the top level since it is not tied to one transport or category.

**Tracking issue:** #73

## Overview

Today every language has exactly one test level — hardware-in-loop (HIL): compile/flash, talk to a real chip over a real bus, assert on the values it returns. This spec adds two more levels and makes each language's test script pick the deepest one the environment actually supports, automatically.

## Test Levels

### Unit
No hardware. A mock/fake `Transport` records writes and replays canned bytes for reads. Exercises pure driver logic: bit-packing, LSB/scale conversions, CRC/checksum math, and the register defaults baked into `Minimal`. Runs in CI on every push. No `testconfig` required.

### HIL (existing)
Real hardware, real bus, real chip. Value-correctness checks — `PASS <label>` / `FAIL <label>[: detail]` per check, `===DONE: N passed, N failed===` last line. Unchanged from today.

### Conformance
Real hardware, real bus, real chip, **plus** a sigrok-cli capture of the transaction, decoded with the chip's own sigrok decoder (`sigrok/<chip>/`) — the same decoder already required for every chip's manual PulseView verification. The capture's annotation timestamps are compared against the chip's timing constraints (conversion delay, write-cycle time, power-on/reset delay, min/max bus clock, etc. — the same facts already required in the chip spec's **Timing Constraints** section). Conformance is a superset of HIL: it runs the same value checks and adds timing checks.

Conformance is implemented **once per chip**, in Python (`conformance/<category>/<chip>_conformance.py` — see below), not once per language. Every language's platform script shells out to the same checker when it detects a logic analyzer is present. This avoids seven reimplementations of "start a sigrok capture, decode it, diff timestamps."

## Level Selection

Every platform script auto-detects the deepest level it can run, cascading:

1. **Sigrok present** — `sigrok-cli --scan` finds a device, or `testconfig`/`testconfig_wiring` sets `SIGROK_DRIVER` — **and** hardware present (see 2) → run **conformance**.
2. **Hardware present** — the configured bus responds at the configured address (I²C quick-write probe, or the configured serial/UF2 port exists for flash platforms) → run **HIL**.
3. **Neither** → run **unit**.

An explicit `--level unit|hil|conformance` flag overrides detection (e.g. force `unit` on a machine with a stale `/dev/i2c-1` node, or skip conformance deliberately even with the analyzer attached). `--level` is independent of `--board` (see **Test Scenarios** below) — one selects test depth, the other selects the source of wiring truth.

```
cpp/test_linux.sh power/ina226                    # auto: unit, hil, or conformance
cpp/test_linux.sh --level unit power/ina226       # forced
```

**Unit only exists on each language's native host script.** Arduino/Zephyr/ESP-IDF/Pico SDK/TinyGo/CircuitPython/MicroPython/Rust-ESP32-S3 run the *same* chip-driver source as their language's host platform (only the transport wrapper differs), so a mocked run there would duplicate the host script's unit coverage with zero added signal. Those scripts support `hil` and `conformance` only; detection cascades straight from sigrok-present → hardware-present with no unit fallback (if hardware isn't present on an embedded platform, there's nothing meaningful to fall back to — the script errors out asking for the board).

## Script Naming

One script per platform, per language directory; JVM additionally splits per language (java/kotlin/groovy) since the three JVM drivers are separate implementations, not one shared source.

| Language | Script | Levels | Notes |
|---|---|---|---|
| cpp | `cpp/test_linux.sh` | unit, hil, conformance | host |
| cpp | `cpp/test_arduino.sh` | hil, conformance | |
| cpp | `cpp/test_zephyr.sh` | hil, conformance | |
| cpp | `cpp/test_espidf.sh` | hil, conformance | |
| cpp | `cpp/test_picosdk.sh` | hil, conformance | |
| python | `python/test_linux.sh` | unit, hil, conformance | host |
| python | `python/test_mp.sh` | hil, conformance | |
| python | `python/test_cp.sh` | hil, conformance | |
| nodejs | `nodejs/test_linux.sh` | unit, hil, conformance | renamed from `test.sh` — only platform, but renamed for naming consistency with the rest |
| rust | `rust/test_linux.sh` | unit, hil, conformance | host |
| rust | `rust/test_esp32s3.sh` | hil, conformance | |
| go | `go/test_linux.sh` | unit, hil, conformance | host |
| go | `go/test_tinygo.sh` | hil, conformance | |
| jvm | `jvm/test_linux_java.sh` | unit, hil, conformance | replaces `test.sh --lang java` |
| jvm | `jvm/test_linux_kotlin.sh` | unit, hil, conformance | replaces `test.sh --lang kotlin` |
| jvm | `jvm/test_linux_groovy.sh` | unit, hil, conformance | replaces `test.sh --lang groovy` |

`testconfig` keeps its existing per-language file(s) (`testconfig`, `testconfig_zephyr`, `testconfig_espidf`, `testconfig_picosdk`, `testconfig_esp32s3`, `testconfig_tinygo`). See **Chip Wiring & Sigrok Configuration** below for the new per-chip wiring/sigrok keys and where they live.

## Test Scenarios: Fixed Board vs Free-Wire Bench

Two distinct people use HIL/conformance, and they need two different sources of wiring truth:

1. **Fixed board.** A specific, known MCU + peripheral combination — e.g. an ESP32-S3 devkit with a BME280 wired to `GPIO8`/`GPIO9` and an INA226 on a second I²C bus. The user just wants to confirm compile/flash/run works and the chip(s) on *this particular board* respond correctly. The wiring is a fact about the hardware, not about the person testing it — anyone with the same board has the same wiring, so it's shareable and belongs in the repo.
2. **Free-wire bench.** A general MCU/host platform with nothing fixed — the user wires whatever chip she's developing to whatever pins/bus she likes, with a sigrok analyzer on the host. The wiring is private to her bench and changes as she works. This is exactly the per-chip `testconfig`/`testconfig_wiring` block designed below.

**Board profiles** serve scenario 1: `<lang>/boards/<board-name>.conf`, **committed to the repo** (not gitignored — it documents public hardware, not a private rig), same `case "$CATEGORY/$CHIP"` shape as `testconfig_wiring` (pins/bus/address, and `SIGROK_CHANNELS` if that board has an analyzer permanently wired too). Board profiles live **per language**, not once at the top level: pin *numbering* for the same physical board differs by SDK (Arduino pin macros vs ESP-IDF `GPIO_NUM_x` vs Zephyr devicetree labels) even though the physical board is one object.

Selected with `--board <name>`:
```
cpp/test_espidf.sh --board esp32s3-sensor-devkit                  # self-test every chip on that board
cpp/test_espidf.sh --board esp32s3-sensor-devkit power/ina226     # self-test just that one chip on it
cpp/test_espidf.sh power/ina226                                    # scenario 2: no --board, free-wire testconfig/testconfig_wiring
```
When `--board` is given **without** a `<category>/<chip>` argument, the script runs every chip listed in that board's profile — scenario 1's whole point is "does this specific hardware work," not "test one chip." `--board` and `--level` are independent flags (a fixed board can still be probed at unit/hil/conformance depending on what's detected/forced).

Board profiles apply to platforms where a fixed devkit-plus-peripheral combination is the normal way people encounter this repo's chips: cpp, python (MicroPython/CircuitPython run on boards too), rust (ESP32-S3), go (TinyGo/Pico W). nodejs/jvm are host-only in practice (though nothing stops someone adding `nodejs/boards/` or `jvm/boards/` later if a fixed-peripheral Linux board scenario comes up) — not required by this issue's checklist.

## Chip Wiring & Sigrok Configuration

Today's `testconfig` is one flat wiring reused for whatever chip you point the script at — fine for a breadboard you rewire between runs, not for a permanent bench with several chips wired to different buses/pins/CS lines at once, each needing its own logic-analyzer channel mapping. This adds a **per-chip override block** on top of the existing flat globals, without introducing a second config file where a language only has one platform. This is the scenario-2 (free-wire bench) config source — see above for the scenario-1 (fixed board) alternative.

**Global keys** (rig-wide, added to the existing flat section of `testconfig`):
```
SIGROK_DRIVER=fx2lafw
SIGROK_CONN=
SIGROK_SAMPLERATE=24m
```
`SIGROK_DRIVER`/`SIGROK_CONN` identify the one logic analyzer on the bench; `SIGROK_SAMPLERATE` is its rig-wide default rate.

**Per-chip override block**, a `case` keyed on `<category>/<chip>`, for any chip whose wiring differs from the platform default — bus/device or pins (whichever that platform needs), address/CS, `SIGROK_CHANNELS` (required — this is what makes multi-chip-on-one-analyzer rigs work), and an optional per-chip `SIGROK_SAMPLERATE` override for buses that need a different rate than the rig default (e.g. NZR LED timing needs far more samples/s than I²C):
```bash
case "$CATEGORY/$CHIP" in
  power/ina226)
    I2C_ADDR=0x40
    SIGROK_CHANNELS="D0=SCL,D1=SDA"
    ;;
  led/ws2812b)
    SPI_BUS=0
    SPI_DEVICE=0
    SIGROK_CHANNELS="D2=DATA"
    SIGROK_SAMPLERATE=100m
    ;;
esac
```

**Precedence:** `--board <name>` profile (if given) → per-chip case block in `testconfig`/`testconfig_wiring` → flat `testconfig` globals → repo-committed `chip_defaults` (address-only fallback, unchanged).

**Where the block lives:**
- If a language has only **one** `testconfig` file (nodejs, jvm, and python — python's MicroPython/CircuitPython/Linux settings already share a single `python/testconfig`), the per-chip block is added directly to that file.
- If a language splits config across **multiple** platform-specific files (cpp: `testconfig`/`testconfig_zephyr`/`testconfig_espidf`/`testconfig_picosdk`; rust: `testconfig`/`testconfig_esp32s3`; go: `testconfig`/`testconfig_tinygo`), the block lives once in a new shared `<lang>/testconfig_wiring` (gitignored, `.example` committed), sourced by every one of that language's platform scripts — the physical rig is the same board regardless of which firmware gets flashed onto it, so the wiring shouldn't be duplicated per platform file.

**Script requirement:** every script must parse `<category>/<chip>` from its target argument **before** sourcing `testconfig`/`testconfig_wiring`, so `$CATEGORY`/`$CHIP` are set when the `case` block runs (today several scripts source config first, then parse args — this ordering flips).

## Unit Test Implementation

A mock `Transport` per language, respecting each language's own idioms rather than forcing one shape:

| Language | Mock transport | Unit test location | Run via |
|---|---|---|---|
| cpp | `cpp/src/transport/I2CTransportMock.h/.cpp` (host-only, implements the same pure-virtual `Transport`) | `cpp/tests/<category>/<chip>_test_unit/<chip>_test_unit.cpp` | `test_linux.sh` |
| python | `python/periph/transport/i2c_mock.py` | `python/tests/<category>/<chip>_test_unit.py` | `test_linux.sh` |
| nodejs | `nodejs/packages/periph/src/transport/i2c_mock.js` | `nodejs/tests/<category>/<chip>_test_unit.js` | `test_linux.sh` |
| rust | `MockI2c` — use `embedded-hal-mock` (dev-dependency) rather than hand-rolling one | `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/<category>/<chip>.rs` | `cargo test -p periph`, wrapped by `test_linux.sh` |
| go | struct literal implementing the `Transport` interface, colocated per Go convention | `go/periph/chips/<category>/<chip>_test.go` | `go test ./periph/chips/...`, wrapped by `test_linux.sh` |
| jvm | one `MockTransport` in `periph-transport`'s test scope, reused from Java/Kotlin/Groovy | `jvm/periph-java/src/test/java/.../<Chip>Test.java` (JUnit), `jvm/periph-kotlin/src/test/kotlin/...` (Kotest/JUnit5), `jvm/periph-groovy/src/test/groovy/...` (Spock) | `mvn test` per module, wrapped by `test_linux_<lang>.sh` |

Where a language already has a native test runner (`cargo test`, `go test`, `mvn test`), the platform script's `unit` path is a thin wrapper around it, filtered to the one chip requested — not a bespoke harness reimplementing what the toolchain already does.

## Conformance Implementation

One Python checker per chip: `conformance/<category>/<chip>_conformance.py`. Capture timing is **software-orchestrated, no hardware trigger** — the checker controls the sequencing itself, so there's no need for `sigrok-cli`'s fiddly, driver-dependent `-t`/`--triggers` syntax. For each named check in `<chip>_timing.conf`:

1. Starts a `sigrok-cli` capture using the rig-wide `SIGROK_DRIVER`/`SIGROK_CONN`, the chip's `SIGROK_CHANNELS` mapping (see **Chip Wiring & Sigrok Configuration** above), and *that check's own* `<check>_samplerate` / `<check>_capture_ms` (see below).
2. Invokes the already-running HIL test binary/process (passed in by the calling platform script) to trigger the transaction under test, timed to fall inside the capture window.
3. Stops the capture, decodes it with `sigrok/<chip>/pd.py` (the chip's existing decoder), and reads that check's named annotation timestamps.
4. Compares the timestamp delta against the check's min/max bound and prints the same `PASS`/`FAIL ... ===DONE: N passed, N failed===` convention as every other level, so output stays uniform across levels and languages.

`specs/<category>/<chip>_timing.conf` — one small sidecar per chip, machine-readable mirror of the spec's prose Timing Constraints section. Each check carries its bound **and** its own capture parameters, because different constraints on the same chip live in very different time domains (microsecond-scale bus edges vs millisecond/second-scale power-on delays) — a single samplerate/window for the whole chip would either drown the fast checks in imprecision or the slow ones in data volume:

```
# ina226 timing constraints + capture parameters
conversion_time_max_ms=1.1
conversion_time_samplerate=1m
conversion_time_capture_ms=5

poweron_reset_min_ms=2
poweron_reset_samplerate=10k
poweron_reset_capture_ms=50
```

Key convention: `<check_name>_min_<unit>` / `<check_name>_max_<unit>` for the bound, `<check_name>_samplerate` / `<check_name>_capture_ms` for how to capture it. `<check_name>` must match an annotation pair the chip's sigrok decoder emits (e.g. `conversion_start` / `conversion_done`), so the checker can locate both timestamps. This means: **any chip spec with a timing constraint must name the corresponding start/end annotations in its Sigrok Decoder section** — add this requirement to `specs/_template_chip.md` and `specs/_template_chip_io_expander.md`.

These capture parameters are a property of the chip's own datasheet timing, not of anyone's bench, so they stay in the repo-committed `timing.conf` — never in the user's private `testconfig`/`testconfig_wiring`, which only ever carries rig-wide (`SIGROK_DRIVER`/`SIGROK_CONN`) and per-chip-wiring (`SIGROK_CHANNELS`) facts.

Every platform script's conformance path is the same two-liner: start its own transport/process as it would for `hil`, then shell out to `conformance/<category>/<chip>_conformance.py --lang <language>` (the checker only needs to know which language's HIL binary/process to drive, since triggering the transaction is language-specific but decoding/measuring is not).

## Rollout Scope

This spec covers the **framework** plus a full reference implementation for **INA226** (already the convention for "new pattern" chips in this repo) across all 6 languages and all 3 levels. Backfilling unit/conformance tests for every other existing chip is out of scope for this issue — track it separately, incrementally, chip by chip. Going forward, `specs/_template_chip.md` / `_template_chip_io_expander.md` gain unit + conformance checklist items so every *new* chip ships with all three levels from day one.

`TESTING.md` (the human-facing quick-start doc) must be rewritten to describe the three levels, the auto-detection cascade, the `--level` override, and the renamed scripts.

## Implementation Checklist

- [ ] `cpp/src/transport/I2CTransportMock.h/.cpp`
- [ ] `cpp/testconfig_wiring.example` — global `SIGROK_*` defaults + per-chip `case` block (ina226)
- [ ] `cpp/boards/` — directory + one illustrative committed board profile (e.g. `cpp/boards/esp32s3-sensor-devkit.conf`) demonstrating the format
- [ ] `cpp/test_linux.sh` — parse target before sourcing config, source `testconfig_wiring`, add unit + conformance paths, detection cascade, `--level` override
- [ ] `cpp/test_arduino.sh`, `test_zephyr.sh`, `test_espidf.sh`, `test_picosdk.sh` — parse target before sourcing config, source `testconfig_wiring`, add `--board` support, conformance path + detection
- [ ] `cpp/tests/power/ina226_test_unit/ina226_test_unit.cpp`
- [ ] `python/periph/transport/i2c_mock.py`
- [ ] `python/testconfig.example` — add `SIGROK_*` globals + per-chip `case` block (ina226) directly (python has one shared testconfig)
- [ ] `python/boards/` — directory + one illustrative committed board profile
- [ ] `python/test_linux.sh` — parse target before sourcing config, unit + conformance paths, detection cascade, `--level`
- [ ] `python/test_mp.sh`, `test_cp.sh` — parse target before sourcing config, `--board` support, conformance path + detection
- [ ] `python/tests/power/ina226_test_unit.py`
- [ ] `nodejs/packages/periph/src/transport/i2c_mock.js`
- [ ] `nodejs/testconfig.example` — add `SIGROK_*` globals + per-chip `case` block (ina226) directly (nodejs has one platform)
- [ ] `nodejs/test.sh` → renamed `nodejs/test_linux.sh` — parse target before sourcing config, unit + conformance paths, detection cascade, `--level`
- [ ] `nodejs/tests/power/ina226_test_unit.js`
- [ ] `rust/periph/Cargo.toml` — add `embedded-hal-mock` dev-dependency
- [ ] `rust/periph/src/chips/power/ina226.rs` — `#[cfg(test)]` unit tests
- [ ] `rust/testconfig_wiring.example` — global `SIGROK_*` defaults + per-chip `case` block (ina226)
- [ ] `rust/boards/` — directory + one illustrative committed board profile
- [ ] `rust/test_linux.sh` — parse target before sourcing config, source `testconfig_wiring`, unit (wraps `cargo test`) + conformance paths, detection cascade, `--level`
- [ ] `rust/test_esp32s3.sh` — parse target before sourcing config, source `testconfig_wiring`, `--board` support, conformance path + detection
- [ ] `go/periph/chips/power/ina226_test.go`
- [ ] `go/testconfig_wiring.example` — global `SIGROK_*` defaults + per-chip `case` block (ina226)
- [ ] `go/boards/` — directory + one illustrative committed board profile
- [ ] `go/test_linux.sh` — parse target before sourcing config, source `testconfig_wiring`, unit (wraps `go test`) + conformance paths, detection cascade, `--level`
- [ ] `go/test_tinygo.sh` — parse target before sourcing config, source `testconfig_wiring`, `--board` support, conformance path + detection
- [ ] `jvm/periph-transport` test-scope `MockTransport`
- [ ] `jvm/periph-java/src/test/java/it/uhde/periph/chips/power/Ina226Test.java`
- [ ] `jvm/periph-kotlin/src/test/kotlin/.../Ina226Test.kt`
- [ ] `jvm/periph-groovy/src/test/groovy/.../Ina226Test.groovy`
- [ ] `jvm/testconfig.example` — add `SIGROK_*` globals + per-chip `case` block (ina226) directly (jvm has one platform, split by language)
- [ ] `jvm/test.sh` → split into `jvm/test_linux_java.sh`, `jvm/test_linux_kotlin.sh`, `jvm/test_linux_groovy.sh` — parse target before sourcing config, unit + conformance paths, detection cascade, `--level`
- [ ] `conformance/power/ina226_conformance.py`
- [ ] `specs/power/ina226_timing.conf`
- [ ] `specs/_template_chip.md`, `_template_chip_io_expander.md` — add unit + conformance checklist items; require named start/end annotations for any timing constraint
- [ ] `TESTING.md` — rewrite for three levels, detection cascade, `--level` override, renamed scripts, per-chip wiring/sigrok config, `--board` profiles vs free-wire bench
