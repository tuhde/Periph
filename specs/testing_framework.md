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

1. **Sigrok present** — `sigrok-cli --scan` finds a device, or `testconfig` sets `SIGROK_DEVICE`/`SIGROK_DRIVER` — **and** hardware present (see 2) → run **conformance**.
2. **Hardware present** — the configured bus responds at the configured address (I²C quick-write probe, or the configured serial/UF2 port exists for flash platforms) → run **HIL**.
3. **Neither** → run **unit**.

An explicit `--level unit|hil|conformance` flag overrides detection (e.g. force `unit` on a machine with a stale `/dev/i2c-1` node, or skip conformance deliberately even with the analyzer attached).

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

`testconfig` keeps its existing per-language file(s) (`testconfig`, `testconfig_zephyr`, `testconfig_espidf`, `testconfig_picosdk`, `testconfig_esp32s3`, `testconfig_tinygo`). New optional keys: `SIGROK_DEVICE`, `SIGROK_DRIVER`.

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

One Python checker per chip: `conformance/<category>/<chip>_conformance.py`. It:

1. Starts a `sigrok-cli` capture on the configured `SIGROK_DEVICE`/`SIGROK_DRIVER`.
2. Invokes the already-running HIL test binary/process (passed in by the calling platform script) to trigger the transaction(s) under test.
3. Stops the capture, decodes it with `sigrok/<chip>/pd.py` (the chip's existing decoder), and reads named annotation timestamps.
4. Compares timestamp deltas against `specs/<category>/<chip>_timing.conf` (see below) and prints the same `PASS`/`FAIL ... ===DONE: N passed, N failed===` convention as every other level, so output stays uniform across levels and languages.

`specs/<category>/<chip>_timing.conf` — one small sidecar per chip, machine-readable mirror of the spec's prose Timing Constraints section:

```
# ina226 timing constraints
conversion_time_max_ms=1.1
poweron_reset_min_ms=2
```

Key convention: `<check_name>_min_<unit>` / `<check_name>_max_<unit>`. `<check_name>` must match an annotation pair the chip's sigrok decoder emits (e.g. `conversion_start` / `conversion_done`), so the checker can locate both timestamps. This means: **any chip spec with a timing constraint must name the corresponding start/end annotations in its Sigrok Decoder section** — add this requirement to `specs/_template_chip.md` and `specs/_template_chip_io_expander.md`.

Every platform script's conformance path is the same two-liner: start its own transport/process as it would for `hil`, then shell out to `conformance/<category>/<chip>_conformance.py --lang <language>` (the checker only needs to know which language's HIL binary/process to drive, since triggering the transaction is language-specific but decoding/measuring is not).

## Rollout Scope

This spec covers the **framework** plus a full reference implementation for **INA226** (already the convention for "new pattern" chips in this repo) across all 6 languages and all 3 levels. Backfilling unit/conformance tests for every other existing chip is out of scope for this issue — track it separately, incrementally, chip by chip. Going forward, `specs/_template_chip.md` / `_template_chip_io_expander.md` gain unit + conformance checklist items so every *new* chip ships with all three levels from day one.

`TESTING.md` (the human-facing quick-start doc) must be rewritten to describe the three levels, the auto-detection cascade, the `--level` override, and the renamed scripts.

## Implementation Checklist

- [ ] `cpp/src/transport/I2CTransportMock.h/.cpp`
- [ ] `cpp/test_linux.sh` — add unit + conformance paths, detection cascade, `--level` override
- [ ] `cpp/test_arduino.sh`, `test_zephyr.sh`, `test_espidf.sh`, `test_picosdk.sh` — add conformance path + detection
- [ ] `cpp/tests/power/ina226_test_unit/ina226_test_unit.cpp`
- [ ] `python/periph/transport/i2c_mock.py`
- [ ] `python/test_linux.sh` — unit + conformance paths, detection cascade, `--level`
- [ ] `python/test_mp.sh`, `test_cp.sh` — conformance path + detection
- [ ] `python/tests/power/ina226_test_unit.py`
- [ ] `nodejs/packages/periph/src/transport/i2c_mock.js`
- [ ] `nodejs/test.sh` → renamed `nodejs/test_linux.sh` — unit + conformance paths, detection cascade, `--level`
- [ ] `nodejs/tests/power/ina226_test_unit.js`
- [ ] `rust/periph/Cargo.toml` — add `embedded-hal-mock` dev-dependency
- [ ] `rust/periph/src/chips/power/ina226.rs` — `#[cfg(test)]` unit tests
- [ ] `rust/test_linux.sh` — unit (wraps `cargo test`) + conformance paths, detection cascade, `--level`
- [ ] `rust/test_esp32s3.sh` — conformance path + detection
- [ ] `go/periph/chips/power/ina226_test.go`
- [ ] `go/test_linux.sh` — unit (wraps `go test`) + conformance paths, detection cascade, `--level`
- [ ] `go/test_tinygo.sh` — conformance path + detection
- [ ] `jvm/periph-transport` test-scope `MockTransport`
- [ ] `jvm/periph-java/src/test/java/it/uhde/periph/chips/power/Ina226Test.java`
- [ ] `jvm/periph-kotlin/src/test/kotlin/.../Ina226Test.kt`
- [ ] `jvm/periph-groovy/src/test/groovy/.../Ina226Test.groovy`
- [ ] `jvm/test.sh` → split into `jvm/test_linux_java.sh`, `jvm/test_linux_kotlin.sh`, `jvm/test_linux_groovy.sh` — unit + conformance paths, detection cascade, `--level`
- [ ] `conformance/power/ina226_conformance.py`
- [ ] `specs/power/ina226_timing.conf`
- [ ] `specs/_template_chip.md`, `_template_chip_io_expander.md` — add unit + conformance checklist items; require named start/end annotations for any timing constraint
- [ ] `TESTING.md` — rewrite for three levels, detection cascade, `--level` override, renamed scripts
