# TESTING.md

Hardware tests for each chip run on all supported platforms and produce identical output — one `PASS`/`FAIL` line per check and a final `===DONE: N passed, N failed===` line. The runners exit 0 on full pass, 1 on any failure, 2 if the test did not complete.

## Three levels

Every platform script auto-detects the deepest level the environment actually supports, cascading:

| Level | What it needs | What it checks |
|-------|----------------|-----------------|
| **unit** | Nothing — a mock `Connection` records writes and replays canned reads | Pure driver logic: bit-packing, scale conversions, CRC/checksum math, register defaults |
| **hil** (hardware-in-loop) | Real hardware, real bus, real chip | Value correctness — the existing check every test always ran |
| **conformance** | HIL plus a sigrok logic analyzer | Everything HIL checks, plus timing: a sigrok capture decoded with the chip's own decoder, compared against its datasheet timing constraints |

Detection: sigrok configured **and** hardware present → conformance; hardware present → hil; neither → unit. Override with `--level unit|hil|conformance` on any script that supports it — e.g. force `unit` on a machine with a stale `/dev/i2c-1` node, or skip conformance deliberately even with the analyzer attached.

**Unit only exists on each language's native host script** (`test_linux.sh`, or the JVM's `test_linux_<lang>.sh`). Arduino/Zephyr/ESP-IDF/Pico SDK/TinyGo/CircuitPython/MicroPython/Rust-ESP32-S3 run the exact same chip-driver source as their language's host platform, so a mocked run there would duplicate the host script's unit coverage with zero added signal — those scripts support `hil` and `conformance` only, and error out asking for a board if neither is present.

**Unit + conformance are not implemented for every chip yet.** Rollout is incremental, chip by chip (see `specs/testing_framework.md`, "Rollout Scope") — ENS160 and AHT21 are the reference implementations as of this writing. A chip without a unit test file falls straight through to `hil`, behaving exactly as it always did. A chip without a `conformance/<category>/<chip>_conformance.py` fails fast with a clear "conformance checker not found" error if `--level conformance` is forced or auto-detected.

Full design rationale lives in `specs/testing_framework.md`; this file is the day-to-day quick-start.

## Quick start

1. Copy the testconfig example for the platform(s) you want to test:
   ```
   cp cpp/testconfig.example         cpp/testconfig
   cp cpp/testconfig_zephyr.example  cpp/testconfig_zephyr
   cp cpp/testconfig_espidf.example  cpp/testconfig_espidf
   cp cpp/testconfig_picosdk.example cpp/testconfig_picosdk
   cp cpp/testconfig_wiring.example  cpp/testconfig_wiring   # optional - multi-chip bench / sigrok only

   cp python/testconfig.example      python/testconfig

   cp nodejs/testconfig.example      nodejs/testconfig

   cp rust/testconfig.example            rust/testconfig
   cp rust/testconfig_esp32s3.example    rust/testconfig_esp32s3
   cp rust/testconfig_wiring.example     rust/testconfig_wiring   # optional

   cp go/testconfig.example              go/testconfig
   cp go/testconfig_tinygo.example       go/testconfig_tinygo
   cp go/testconfig_wiring.example       go/testconfig_wiring     # optional

   cp jvm/testconfig.example             jvm/testconfig
   ```
2. Fill in your board's values (pins, port, bus number). On a bare breadboard you rewire between chips, that's all you need — leave `testconfig_wiring` absent.
3. Run the relevant runner. Every script auto-detects unit/hil/conformance; add `--level` to force one:
   ```
   cpp/test_arduino.sh    power/ina226
   cpp/test_linux.sh      power/ina226
   cpp/test_zephyr.sh     power/ina226
   cpp/test_espidf.sh     power/ina226
   cpp/test_picosdk.sh    power/ina226

   python/test_mp.sh      power/ina226
   python/test_cp.sh      power/ina226
   python/test_linux.sh   power/ina226

   nodejs/test_linux.sh   power/ina226

   rust/test_linux.sh     power/ina226
   rust/test_esp32s3.sh   power/ina226

   go/test_linux.sh       power/ina226
   go/test_tinygo.sh      power/ina226

   jvm/test_linux_java.sh    power/ina226
   jvm/test_linux_kotlin.sh  power/ina226
   jvm/test_linux_groovy.sh  power/ina226
   ```

`testconfig`/`testconfig_wiring` files are gitignored — never commit them. The sigrok decoder has no automated runner outside the conformance checker; see [Sigrok decoders](#sigrok-decoders-pulseview) below for the manual PulseView workflow.

## Two ways to wire a chip up

Two different people reach for these scripts, with two different sources of wiring truth:

- **Free-wire bench** (default): you wire whatever chip you're developing to whatever pins/bus you like. Wiring lives in your private `testconfig`/`testconfig_wiring` — gitignored, per-chip overrides go in a `case "$CATEGORY/$CHIP"` block (see the `.example` files). This is what every command above uses with no extra flags.
- **Fixed board**: a specific, known MCU + peripheral combination, e.g. an ESP32-S3 devkit with AHT21 and ENS160 permanently wired. Select it with `--board <name>`; wiring comes from a **committed** `<lang>/boards/<name>.conf` instead of your private config, because it documents public hardware, not a private rig:
  ```
  cpp/test_espidf.sh --board esp32s3-sensor-devkit                 # self-test every chip on that board
  cpp/test_espidf.sh --board esp32s3-sensor-devkit gas/ens160       # just one chip on it
  ```
  With no `<category>/<chip>` argument, `--board` tests every chip listed in that board's `BOARD_CHIPS`. `--board` is supported on every flashable platform script (Arduino, Zephyr, ESP-IDF, Pico SDK, MicroPython, CircuitPython, Rust ESP32-S3, TinyGo) — not on the host-only scripts (`test_linux.sh`, Node.js, JVM), where a fixed-peripheral scenario doesn't come up in practice.

See `specs/testing_framework.md`, "Test Scenarios: Fixed Board vs Free-Wire Bench" for the full design, and `cpp/boards/esp32s3-sensor-devkit.conf` for a worked example.

## Chip wiring & sigrok configuration

For a bench with several chips wired at once, each needing its own logic-analyzer channel mapping, add a per-chip override block to your `testconfig`/`testconfig_wiring`:

```bash
# Rig-wide (one logic analyzer on the bench)
SIGROK_DRIVER=fx2lafw
SIGROK_CONN=
SIGROK_SAMPLERATE=24m

# Per-chip overrides
case "${CATEGORY:-}/${CHIP:-}" in
    gas/ens160)
        I2C_ADDR=0x53
        SIGROK_CHANNELS="D0=SCL,D1=SDA"
        ;;
    environmental/aht21)
        I2C_ADDR=0x38
        SIGROK_CHANNELS="D2=SCL,D3=SDA"
        ;;
esac
```

Languages with more than one config file (cpp, rust, go) put this block in a shared `<lang>/testconfig_wiring` sourced by every platform script; languages with one config file (python, nodejs, jvm) put it directly in `testconfig`. Precedence: `--board` profile → per-chip case block → flat `testconfig` globals → `chip_defaults` (address-only fallback).

---

## Platform reference

### Arduino (`cpp/test_arduino.sh`)

**Prerequisites:** `arduino-cli`, `pyserial` (`pip install pyserial`)

**Config:** `cpp/testconfig` (or `--board`)

| Variable | Description |
|----------|-------------|
| `FQBN` | Full board FQBN, e.g. `esp32:esp32:esp32s3:CDCOnBoot=cdc` |
| `PORT` | Serial port, e.g. `/dev/ttyACM0` |
| `I2C_SDA` / `I2C_SCL` | GPIO pin numbers |
| `I2C_FREQ` | I²C clock in Hz (default 400000) |

**ESP32-S3 note:** Add `:CDCOnBoot=cdc` to the FQBN so `Serial` maps to the USB CDC port, otherwise the serial reader will time out.

Levels: **hil, conformance** (no unit — see "Three levels" above). Auto-detects: board present at `PORT` → hil, plus sigrok configured → conformance. Use `--compile-only` to verify builds without hardware:
```
cpp/test_arduino.sh --compile-only power/ina226
cpp/test_arduino.sh --board esp32s3-sensor-devkit --level hil
```

---

### Linux GCC (`cpp/test_linux.sh`)

**Prerequisites:** `g++` (C++17), `linux/i2c-dev.h` (kernel headers)

**Config:** `cpp/testconfig`/`testconfig_wiring` — `I2C_ADDR`, `LINUX_I2C_BUS`.

Levels: **unit, hil, conformance** (this is cpp's host script — the one with unit coverage). Builds a native binary in a temp directory and runs it directly. No board required for unit or (if you're mocking) hil development. Supports `--compile-only` and `--level`:
```
cpp/test_linux.sh --level unit power/ina226
cpp/test_linux.sh --compile-only power/ina226
```

---

### MicroPython (`python/test_mp.sh`)

**Prerequisites:** `mpremote` (`pip install mpremote`)

**Config:** `python/testconfig` (or `--board`)

| Variable | Description |
|----------|-------------|
| `MP_PORT` | Serial port or `auto` |
| `MP_I2C_ID` | `machine.I2C` bus ID |
| `MP_SDA` / `MP_SCL` | GPIO pin numbers |
| `MP_I2C_FREQ` | I²C clock in Hz |
| `I2C_ADDR` | Device I²C address |

Levels: **hil, conformance**. Uses `mpremote mount` — the `periph` library is imported directly from the host filesystem; nothing is written to the board.

---

### CircuitPython (`python/test_cp.sh`)

**Prerequisites:** `pyserial` (`pip install pyserial`), CIRCUITPY USB drive mounted

**Config:** `python/testconfig` (or `--board`)

| Variable | Description |
|----------|-------------|
| `CP_PORT` | Serial port or `auto` |
| `CP_SDA` / `CP_SCL` | Pin expressions, e.g. `board.IO1` |
| `I2C_ADDR` | Device I²C address |

Levels: **hil, conformance**. Copies `periph/` to `<CIRCUITPY>/lib/periph` and a generated `_testconfig.py` to the drive root, executes the test via raw REPL (`cp_runner.py`), then removes both.

**Note:** `ampy` and `mpremote` are not compatible with CircuitPython 10+ because the status bar injects OSC escape sequences that break the raw REPL handshake. `cp_runner.py` handles this by entering raw REPL without triggering a soft reset.

---

### Linux kernel / Python (`python/test_linux.sh`)

**Prerequisites:** `smbus2` (`pip install smbus2`)

**Config:** `python/testconfig`/`testconfig_wiring` — `LINUX_I2C_BUS`, `I2C_ADDR`.

Levels: **unit, hil, conformance** (python's host script). Runs directly on the host; no board required for unit. Supports `--level`:
```
python/test_linux.sh --level unit power/ina226
```

---

### Node.js (`nodejs/test_linux.sh`)

**Prerequisites:** Node.js, `npm install` run from `nodejs/`

**Config:** `nodejs/testconfig` — `I2C_BUS`, `I2C_ADDR`, plus the `SIGROK_*` keys since Node.js has only one platform file.

Levels: **unit, hil, conformance**. Runs directly on the host; no board required for unit.

---

### Zephyr RTOS (`cpp/test_zephyr.sh`)

**Prerequisites:** `west` and a Zephyr workspace (`ZEPHYR_BASE` set or `west init` done)

**Config:** `cpp/testconfig_zephyr` (or `--board`)

| Variable | Description |
|----------|-------------|
| `ZEPHYR_BOARD` | Board identifier, e.g. `nrf52840dk/nrf52840`, `esp32s3_devkitc/esp32s3/procpu` |
| `ZEPHYR_PORT` | Serial port for reading test output |
| `I2C_ADDR` | Device I²C address (hex) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

Levels: **hil, conformance**. Calls `west build` then `west flash`, and reads serial output using `cpp/read_serial_zephyr.py`. Supports `--compile-only` (skips flash and serial):
```
cpp/test_zephyr.sh --compile-only power/ina226
cpp/test_zephyr.sh --board esp32s3-sensor-devkit
```

**Devicetree:** The test app uses `DT_NODELABEL(i2c0)` by default. If your board uses a different I²C node label, provide a board overlay at `cpp/tests/<category>/<chip>_test_zephyr/boards/<board>.overlay` with the correct alias.

**Zephyr defines `__ZEPHYR__`, never `CONFIG_ZEPHYR`.** If a chip driver's platform-detection `#if` chain checks the latter, its Zephyr branch is dead code — verified with a real `west build`, not just `--compile-only`, since `CONFIG_ZEPHYR`'s absence doesn't fail the build, it just silently falls through to the wrong branch.

**Using as a module (not just testing):** these test apps build against `cpp/src/` directly via manual `target_sources`/`target_include_directories`, same as every `cpp/examples/zephyr/` example. External consumers instead add `cpp/` as a Zephyr module via `ZEPHYR_EXTRA_MODULES` and link `periph` — see [Zephyr module](README.md#zephyr-module) in the README.

---

### ESP-IDF (`cpp/test_espidf.sh`)

**Prerequisites:** ESP-IDF ≥5.2 with `IDF_PATH` exported (or its `export.sh` sourced), `idf.py` on `PATH`, `pyserial` (`pip install pyserial`)

**Config:** `cpp/testconfig_espidf` (or `--board`)

| Variable | Description |
|----------|-------------|
| `IDF_TARGET` | Target chip — `esp32` (default), `esp32s2`, `esp32s3`, `esp32c3`, `esp32c6`, `esp32h2` |
| `ESPIDF_PORT` | USB-CDC serial port, e.g. `/dev/ttyUSB0` (Linux), `/dev/tty.usbserial-*` (macOS) |
| `I2C_ADDR` | Device I²C address (hex) |
| `SPI_CS` | SPI chip-select GPIO pin (for SPI-mode tests) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

Levels: **hil, conformance**. Builds each test as a standalone ESP-IDF project (mirroring how every Zephyr example is a separate `west build` app), flashes via `idf.py -p <port> flash`, and reads the USB-CDC serial output. Supports `--compile-only`:
```
cpp/test_espidf.sh --compile-only power/ina226
cpp/test_espidf.sh --board esp32s3-sensor-devkit
```

Pins: each chip's test app hard-codes its default I²C pins (`GPIO21` SDA / `GPIO22` SCL on `I2C_NUM_0`) and SPI pins (`MOSI=GPIO13`, `SCK=GPIO14` on `SPI2_HOST` for NeoPixel). To override, edit the per-chip `main/CMakeLists.txt` or the bus-config block at the top of `main.cpp`.

**Note on the I²C frequency:** the generated tests configure `I2C_NUM_0` at 400 kHz (fast-mode, the rate every chip in this repo supports). Drop to 100 kHz by editing the per-test `scl_speed_hz` field if your device is standard-mode only.

**ESP-IDF ≥6.0's `esp_driver_i2c` component:** `driver/i2c_master.h` moved out of the catch-all `driver` component into `esp_driver_i2c`. A test app's `main/CMakeLists.txt` needs both in `REQUIRES` (`REQUIRES driver esp_driver_i2c`) or the build fails with "esp_driver_i2c component(s) is not in the requirements list."

### Raspberry Pi Pico SDK (`cpp/test_picosdk.sh`)

**Prerequisites:** `pico-sdk` (`PICO_SDK_PATH` set or `~/pico-sdk` discovered by `pico_sdk_init.cmake`), `picotool` on `PATH`, `pyserial` (`pip install pyserial`)

**Config:** `cpp/testconfig_picosdk` (or `--board`)

| Variable | Description |
|----------|-------------|
| `PICO_SDK_PATH` | Path to your local pico-sdk checkout (auto-discovered if unset) |
| `PICO_BOARD` | Board identifier — `pico` (RP2040, default), `picow`, `pico2`, `pico2_w` |
| `PICOSDK_PORT` | USB-CDC serial port, e.g. `/dev/ttyACM0` (Linux), `/dev/tty.usbmodem*` (macOS) |
| `I2C_ADDR` | Device I²C address (hex) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

Levels: **hil, conformance**. Builds each test as a standalone pico-sdk CMake project, flashes the resulting UF2 via `picotool load -x`, and reads the USB-CDC serial output. Supports `--compile-only`:
```
cpp/test_picosdk.sh --compile-only power/ina226
```

Pins: each chip's test app hard-codes its default I²C pins (`GP4` SDA / `GP5` SCL on `i2c0`) and SPI pins (`GP3` MOSI on `spi0`). To override, edit the per-chip `CMakeLists.txt` or wire a board-specific overlay.

**Note on `i2c_init` frequency:** the generated tests configure `i2c0` at 100 kHz (the standard-mode rate that every chip in this repo supports). Bump to 400 kHz by editing the per-test `i2c_init(...)` call if your device supports fast-mode.

---

### Rust Linux (`rust/test_linux.sh`)

**Prerequisites:** `cargo` (stable), `linux/i2c-dev.h` kernel driver (`i2c-dev` module)

**Config:** `rust/testconfig`/`testconfig_wiring` — `I2C_BUS`, `I2C_ADDR`.

Levels: **unit, hil, conformance** (rust's host script). Unit wraps `cargo test -p periph --features std chips::<category>::<chip>::` (the crate is `no_std` by default — `std` must be enabled explicitly for the host build). hil/conformance build with `cargo build --release` and run the binary directly. Supports `--compile-only` and `--level`:
```
rust/test_linux.sh --level unit environmental/aht21
rust/test_linux.sh --compile-only power/ina226
```

---

### Rust ESP32-S3 (`rust/test_esp32s3.sh`)

**Prerequisites:**
- `rustup` with the `esp` toolchain: `cargo install espup && espup install`
- `cargo-espflash`: `cargo install cargo-espflash`
- `pyserial`: `pip install pyserial`

**Config:** `rust/testconfig_esp32s3` (or `--board`)

| Variable | Description |
|----------|-------------|
| `ESP32S3_PORT` | Serial port, e.g. `/dev/ttyACM0` |
| `I2C_ADDR` | Device I²C address (hex, default `0x40`) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

Levels: **hil, conformance**. Builds with `cargo build --release` using the `esp` toolchain (selected automatically via `rust-toolchain.toml`), flashes with `cargo espflash flash`, and reads serial output using `rust/read_serial_esp32s3.py`. Supports `--compile-only`:
```
rust/test_esp32s3.sh --compile-only power/ina226
```

SDA/SCL pin assignments are constants in `src/main.rs` (default GPIO1/GPIO2). The test crate is standalone — not part of the `rust/` workspace — because it requires the `esp` toolchain.

---

### JVM — Java/Kotlin/Groovy

**Prerequisites:** Maven (for unit tests via `mvn test`), JBang (`sdk install jbang` or `curl -Ls https://sh.jbang.dev | bash`) for hil/conformance, Java 22+

**Config:** `jvm/testconfig` (I²C/SPI vars, plus `SIGROK_*` since JVM has one config file split by language rather than platform).

One script per language — `jvm/test_linux_java.sh`, `jvm/test_linux_kotlin.sh`, `jvm/test_linux_groovy.sh` (the three drivers are separate implementations, not one shared source, so this doesn't fold into a `--lang` flag the way it briefly did). All three run on Linux hardware directly.

Levels: **unit, hil, conformance**, per script. Unit wraps `mvn -pl periph-<lang> -am test -Dtest=<Chip>Test` (java/kotlin) or `-Dtest=<Chip>Spec` (groovy — Spock's own naming convention). hil/conformance run the matching `jvm/tests/<category>/<chip>/<Chip>Test.<ext>` via `jbang`, using the FFM (Foreign Function & Memory) API — no Pi4J, no native libraries, no build step for hil (JBang resolves dependencies from Maven Central on first run).

```
jvm/test_linux_java.sh   --level unit gas/ens160
jvm/test_linux_kotlin.sh environmental/aht21
jvm/test_linux_groovy.sh adc_dac/mcp4725
```

There is no `--compile-only` for the hil/conformance path. To verify a JBang script compiles without hardware, run it anyway — the defaults (`I2C_BUS=1`, `I2C_ADDR=0x40`) will be used and the test will fail if no device is present, but the JVM will still type-check the source first.

---

### Go Linux (`go/test_linux.sh`)

**Prerequisites:** Go ≥ 1.24, `linux/i2c-dev.h` kernel driver (`modprobe i2c-dev`)

**Config:** `go/testconfig`/`testconfig_wiring` — `I2C_BUS`, `I2C_ADDR`.

Levels: **unit, hil, conformance** (go's host script). Unit wraps `go test ./periph/chips/<category>/... -run '(?i)^Test_?<chip>'` (case-insensitive, since Go test function names like `TestENS160FullAPI` don't follow the chip's lowercase package casing). hil/conformance build a native binary and run it. Supports `--compile-only` and `--level`:
```
go/test_linux.sh --level unit gas/ens160
go/test_linux.sh --compile-only power/ina226
```

---

### Go TinyGo / Pico W (`go/test_tinygo.sh`)

**Prerequisites:**
- TinyGo ≥ 0.41 (`tinygo` on PATH)
- Raspberry Pi Pico W in BOOTSEL mode (UF2 mount visible)
- `pyserial` (`pip install pyserial`)

**Config:** `go/testconfig_tinygo` (or `--board`)

| Variable | Description |
|----------|-------------|
| `UF2_MOUNT` | Path to the Pico W UF2 drive (default `/media/$USER/RPI-RP2`) |
| `SERIAL_PORT` | Serial port for output (default `/dev/ttyACM0`) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default `20`) |

Levels: **hil, conformance**. Builds a UF2 with `tinygo build -target=pico-w`, copies it to the UF2 mount, and reads serial output via `go/read_serial_tinygo.py`. Supports `--compile-only`:
```
go/test_tinygo.sh --compile-only power/ina226
go/test_tinygo.sh --board pico-w-sensor-devkit
```

---

### Sigrok decoders (PulseView) {#sigrok-decoders-pulseview}

**Prerequisites:** PulseView with the decoder installed (`sigrok/<chip>/` copied or symlinked into the sigrok protocol-decoder search path)

Manual, exploratory verification — the conformance checker (below) is the automated path for the specific timing bounds a chip spec calls out:

1. Open the `.sr` session file from `sigrok/tests/<chip>/` in PulseView.
2. Add the chip's decoder stacked on the `I2C` decoder.
3. Confirm that annotations match the register values shown in the session.

The `.sr` file is committed alongside the decoder. One session file per chip is sufficient; it should exercise at least one write and one read of the chip's primary registers.

---

### Conformance checker (`conformance/<category>/<chip>_conformance.py`)

Invoked automatically by `--level conformance` (or auto-detection) on every platform script above — not run directly. One checker per chip, shared across every language, built on `conformance/_sigrok_conformance.py`:

1. Reads `specs/<category>/<chip>_timing.conf` (a machine-readable mirror of the chip spec's Timing Constraints section — bound + capture samplerate/duration per named check).
2. For each check, starts a timed `sigrok-cli` capture using `SIGROK_DRIVER`/`SIGROK_CONN`/`SIGROK_CHANNELS`, then drives one real transaction (re-running the platform's own binary/board-reset/test file) so it lands inside the capture window — no hardware trigger syntax needed, since the checker controls the sequencing itself.
3. Decodes the capture with the chip's own `sigrok/<chip>/pd.py`, locates that check's named annotation pair, and compares the timestamp delta against the bound.
4. Prints `PASS <check>: <ms> (bound)` / `FAIL <check>: ...` / `===DONE: N passed, N failed===`, same as every other level.

A chip with no `conformance/<category>/<chip>_conformance.py` yet fails fast with "conformance checker not found" — see `specs/testing_framework.md`, "Rollout Scope".

---

## Writing tests for a new chip

Add one test file per platform following the naming convention:

| Platform | Path |
|----------|------|
| Arduino | `cpp/tests/<category>/<chip>_test/<chip>_test.ino` |
| Linux GCC | `cpp/tests/<category>/<chip>_test_linux/<chip>_test_linux.cpp` |
| Linux GCC unit | `cpp/tests/<category>/<chip>_test_unit/<chip>_test_unit.cpp` |
| Zephyr RTOS | `cpp/tests/<category>/<chip>_test_zephyr/src/main.cpp` + `CMakeLists.txt` + `prj.conf` |
| ESP-IDF | `cpp/tests/<category>/<chip>_test_espidf/main/main.cpp` + `main/CMakeLists.txt` + `CMakeLists.txt` |
| Pico SDK | `cpp/tests/<category>/<chip>_test_picosdk/src/main.cpp` + `CMakeLists.txt` |
| MicroPython | `python/tests/<category>/<chip>_test.py` |
| CircuitPython | `python/tests/<category>/<chip>_test_cp.py` |
| Linux kernel (Python) | `python/tests/<category>/<chip>_test_linux.py` |
| Linux kernel (Python) unit | `python/tests/<category>/<chip>_test_unit.py` |
| Node.js | `nodejs/tests/<category>/<chip>_test.js` |
| Node.js unit | `nodejs/tests/<category>/<chip>_test_unit.js` |
| Rust Linux | `rust/tests/<category>/<chip>_test/src/main.rs` + `Cargo.toml` |
| Rust Linux unit | `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/<category>/<chip>.rs` |
| Rust ESP32-S3 | `rust/tests/<category>/<chip>_test_esp32s3/src/main.rs` + `Cargo.toml` |
| Go Linux | `go/tests/<category>/<chip>_test/main.go` |
| Go Linux unit | `go/periph/chips/<category>/<chip>_test.go` |
| Go TinyGo | `go/tests/<category>/<chip>_test_tinygo/main.go` |
| JVM | `jvm/tests/<category>/<chip>/<Chip>Test.java` |
| JVM unit (Java) | `jvm/periph-java/src/test/java/it/uhde/periph/chips/<category>/<Chip>Test.java` |
| JVM unit (Kotlin) | `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/<category>/<Chip>Test.kt` |
| JVM unit (Groovy) | `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/<category>/<Chip>Spec.groovy` |
| Conformance | `conformance/<category>/<chip>_conformance.py` (one per chip, not per language) |
| Timing config | `specs/<category>/<chip>_timing.conf` |

Use `INA226` as the reference implementation for the hil templates below, and ENS160/AHT21 for unit + conformance (`specs/testing_framework.md`'s reference chips). Every test must:

- Print `PASS <label>` or `FAIL <label>[: detail]` for each check
- Print `===DONE: N passed, N failed===` as the last line
- Exit non-zero if any check failed (host-side platforms only)

### Arduino sketch template

```cpp
#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    // ... checks ...
    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}
void loop() { delay(1000); }
```

The `#ifndef` guards let `test_arduino.sh` inject pin values from `testconfig` via `-DTEST_SDA=...` compiler flags without modifying the sketch.

### Linux GCC test template (hil)

```cpp
#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    // ... checks using printf("PASS %s\n", label) ...
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
```

### Linux GCC unit test template

```cpp
#include "I2CConnectionMock.h"
#include "<Chip>.h"

int main() {
    I2CConnectionMock connection;
    connection.setRegister(<Chip>Full::REG_SOME_ID, 0x42);

    <Chip>Full chip(connection);
    // ... checks against connection.writes / connection.registers ...
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
```

No `testconfig` needed — `test_linux.sh` compiles this directly against `I2CConnectionMock.h/.cpp` and the chip source, with no `-DTEST_*` defines.

### Python (MicroPython) test template

```python
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.<category>.<chip> import <Chip>Full
from machine import I2C, Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
# ... checks using print('PASS', label) ...
print('===DONE: {} passed, {} failed==='.format(passed, failed))
```

`_testconfig.py` is generated from `python/testconfig` (or the `--board` profile) by `test_mp.sh` at run time and is never committed.

### Python (CircuitPython) test template

Same structure as MicroPython, but use:
```python
import busio, _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)  # SCL first
```

Use `time.sleep(0.001)` instead of `time.sleep_ms(1)`.

### Python (Linux) test template (hil)

```python
import os
from periph.connection.i2c_linux import I2CConnection

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x40'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)
# ... checks ...
connection.close()
```

### Python (Linux) unit test template

```python
import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.<category>.<chip> import <Chip>Full

passed = failed = 0

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label); passed += 1
    else:
        print('FAIL', label); failed += 1

connection = I2CConnectionMock()
connection.set_register(<Chip>Full._REG_SOME_ID, 0x42)
chip = <Chip>Full(connection)
# ... checks against connection.writes / connection.registers ...
print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
```

No `testconfig` needed — `test_linux.sh` runs this directly with `PYTHONPATH` set, no bus/address env vars.

### Node.js test template (hil)

```js
'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { <Chip>Full }   = require('../../packages/periph/src/chips/<category>/<chip>');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
// ... checks using console.log('PASS', label) ...
connection.close();
console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
```

### Node.js unit test template

```js
'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { <Chip>Full } = require('../../packages/periph/src/chips/<category>/<chip>');

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnectionMock();
    connection.setRegister(0x00, [0x42]);
    const chip = new <Chip>Full(connection);
    // ... checks against connection.writes / connection.registers ...
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}
main();
```

No `testconfig` needed — `test_linux.sh` runs this directly with plain `node`.

### Zephyr RTOS test template

`CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(<chip>_test)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

target_sources(app PRIVATE
    src/main.cpp
    ${CPP_DIR}/src/chips/<category>/<Chip>.cpp
)

target_include_directories(app PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/<category>
)
```

`prj.conf`:
```
CONFIG_I2C=y
CONFIG_CPP=y
CONFIG_STD_CPP17=y
CONFIG_NEWLIB_LIBC=y
CONFIG_FPU=y
```
Add `CONFIG_REQUIRES_FULL_LIBCPP=y` if the driver uses `<cmath>` (Zephyr's default minimal libc++ doesn't provide it).

`src/main.cpp`:
```cpp
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "<Chip>.h"

#ifndef INA226_I2C_NODE
#define INA226_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef INA226_ADDR
#define INA226_ADDR 0x40
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(INA226_I2C_NODE);
    I2CConnectionZephyr connection(dev, INA226_ADDR);
    <Chip>Full chip(connection);
    // ... checks ...
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
```

The `DT_NODELABEL(i2c0)` default works for most boards, but many boards ship with `i2c0` **disabled** by default upstream — add a board overlay at `cpp/tests/<category>/<chip>_test_zephyr/boards/<board>.overlay` enabling it (and providing a different node label if needed).

**Platform detection in the driver itself:** any `#if`/`#elif` chain selecting a delay/platform implementation must check `defined(__ZEPHYR__)`, never `CONFIG_ZEPHYR` (Zephyr never defines the latter) — and check it **before** `ESP_PLATFORM`, since Zephyr's ESP32 SoC layer also defines `ESP_PLATFORM` for unrelated HAL-integration reasons.

### ESP-IDF test template

Top-level `CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.16)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(<chip>_test_espidf)
```

`sdkconfig.defaults`:
```
CONFIG_IDF_TARGET="esp32"
CONFIG_COMPILER_CXX_EXCEPTIONS=n
CONFIG_COMPILER_CXX_RTTI=n
```

`main/CMakeLists.txt`:
```cmake
set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/<category>/<Chip>.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/<category>
    REQUIRES driver esp_driver_i2c
)
```
`esp_driver_i2c` is required alongside `driver` on ESP-IDF ≥6.0 — `driver/i2c_master.h` moved into its own component.

`main/main.cpp`:
```cpp
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "<Chip>.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x40,
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    <Chip>Full chip(connection);
    // ... checks ...
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
```

Fill in every field of `i2c_master_bus_config_t`/`i2c_device_config_t` explicitly, even ones you're leaving at their default value — omitting any of them still zero-fills them at runtime, but `-Werror -Wmissing-field-initializers` (this repo's build flags) turns the warning into a hard build failure on ESP-IDF ≥6.0, which added several new fields.

The default `GPIO21` SDA / `GPIO22` SCL on `I2C_NUM_0` works on most ESP32 boards. To use a different pin pair or move to `I2C_NUM_1`, edit the bus-config block at the top of `main.cpp`.

### Raspberry Pi Pico SDK test template

`CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(<chip>_test_picosdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

add_executable(<chip>_test_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/<category>/<Chip>.cpp
)

target_include_directories(<chip>_test_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/<category>
)

target_link_libraries(<chip>_test_picosdk PRIVATE
    pico_stdlib
    hardware_i2c   # or hardware_spi / hardware_uart / hardware_gpio
)

pico_enable_stdio_usb(<chip>_test_picosdk 1)
pico_enable_stdio_uart(<chip>_test_picosdk 0)

pico_add_extra_outputs(<chip>_test_picosdk)
```

`src/main.cpp`:
```cpp
#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"   // or SPIConnectionPicoSDK.h / UARTConnectionPicoSDK.h
#include "<Chip>.h"

int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    i2c_init(i2c0, 100 * 1000);          // 100 kHz, standard mode
    gpio_set_function(4, GPIO_FUNC_I2C); // SDA = GP4
    gpio_set_function(5, GPIO_FUNC_I2C); // SCL = GP5
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x40);  // 7-bit address
    <Chip>Full chip(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    // ... checks using chip.<method>() ...
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
```

The default I²C pins (`GP4`/`GP5` on `i2c0`) match pico-sdk's documented defaults — wire your device to those and the test will work without further configuration. Call the chip driver's real API (e.g. `status()`/`read_tvoc()`/`read_eco2()` for ENS160) rather than guessing method names — a test app that never compiles against the real header is easy to leave broken indefinitely.

### Rust Linux test template (hil)

`Cargo.toml`:
```toml
[package]
name = "<chip>_test"
version = "0.1.0"
edition = "2021"

[dependencies]
periph = { workspace = true }
linux-embedded-hal = { workspace = true }
```

`src/main.rs`:
```rust
use linux_embedded_hal::I2cdev;
use periph::chips::<category>::<Chip>Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = <Chip>Full::new(dev, addr, 0.1, 2.0).expect("init <Chip>");

    let mut passed = 0i32;
    let mut failed = 0i32;

    // ... checks ...
    check_true!(true, "example_check", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
```

Also add the new crate to the workspace `members` list in `rust/Cargo.toml`.

### Rust unit test template

Colocated in the driver file itself, using `embedded-hal-mock` (a dev-dependency already in `rust/periph/Cargo.toml`) rather than a hand-rolled mock:

```rust
// at the bottom of rust/periph/src/chips/<category>/<chip>.rs
#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x40;

    #[test]
    fn full_api() {
        let transactions = vec![
            I2cTransaction::write(ADDR, vec![/* ... */]),
            I2cTransaction::write_read(ADDR, vec![/* reg */], vec![/* response bytes */]),
        ];
        let i2c = I2cMock::new(&transactions);
        let mut chip = <Chip>Full::new(i2c, ADDR).expect("init");

        // ... assert_eq!(chip.some_method().unwrap(), expected) ...

        chip.inner.i2c.done();  // verifies every expected transaction happened
    }
}
```

Run via `cargo test -p periph --features std chips::<category>::<chip>::`, wrapped by `test_linux.sh --level unit`.

### JVM test template (hil)

One JBang script per chip. The file name is `<Chip>Test.java` (title-case chip name + `Test`). Use `INA226` as the reference.

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.<category>.<Chip>Full;

public class <Chip>Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static void checkEq(String label, int got, int expected) {
        if (got == expected) { System.out.println("PASS " + label); passed++; }
        else { System.out.println("FAIL " + label + ": got " + got + ", expected " + expected); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new <Chip>Full(connection);

            // --- checks ---
            // checkTrue("description", chip.someMethod() >= 0);

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
```

Run with: `jvm/test_linux_java.sh <category>/<chip>` (or invoke `jbang` directly with `I2C_BUS`/`I2C_ADDR` set for a quick one-off).

Kotlin (`<Chip>Test.kt`) and Groovy (`<Chip>Test.groovy`) hil scripts mirror this structure in their own syntax; all three are separate JBang scripts because the three drivers are separate implementations.

### JVM unit test template

Java (JUnit), reusing `MockConnection` from `periph-connection`'s test scope:

```java
package it.uhde.periph.chips.<category>;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class <Chip>Test {
    @Test
    void fullApi() {
        var connection = new MockConnection();
        connection.setRegister(0x00, 0x42);
        var chip = new <Chip>Full(connection);
        // ... assertEquals(expected, chip.someMethod()) ...
    }
}
```

Kotlin mirrors this in `<Chip>Test.kt` (Kotest/JUnit5). Groovy uses Spock and the `Spec` suffix instead of `Test` (`<Chip>Spec.groovy`) — Spock's own naming convention, already wired into `periph-groovy`'s surefire `<includes>`.

Run via `mvn -pl periph-<lang> -am test -Dtest=<Chip>Test` (or `<Chip>Spec` for groovy), wrapped by `jvm/test_linux_<lang>.sh --level unit`.

### Go Linux test template (hil)

`go/tests/<category>/<chip>_test/main.go`:

```go
//go:build linux && !tinygo

package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/<category>"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)

	tr, err := connection.NewI2CConnection(bus, uint8(addr64))
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err); os.Exit(2)
	}
	defer tr.Close()

	chip, err := <category>.New<Chip>Full(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err); os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond { fmt.Println("PASS", label); passed++ } else { fmt.Println("FAIL", label); failed++ }
	}

	// --- checks ---
	v, err := chip.SomeMethod()
	check("some_method_range", err == nil && v >= 0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 { os.Exit(1) }
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok { return v }
	return def
}
```

The TinyGo test (`<chip>_test_tinygo/main.go`) follows the same structure but uses `//go:build tinygo`, opens the connection via `connection.NewI2CConnection(machine.I2C1, addr)`, and replaces `os.Exit` with `panic` (TinyGo lacks `os.Exit`). Use `INA226` as the reference implementation for both variants.

### Go unit test template

Colocated with the driver, `package <category>` (Go convention — no separate test package):

```go
package <category>

import "testing"

// mockConnection implements connection.Connection - see
// go/periph/chips/gas/ens160_test.go for the full reusable shape
// (registers map, writes log, queueRead FIFO).
type mockConnection struct {
	registers map[byte]byte
	writes    [][]byte
}

func newMockConnection() *mockConnection { return &mockConnection{registers: map[byte]byte{}} }

func Test<Chip>FullAPI(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(0x00, 0x42)

	chip, err := New<Chip>Full(conn)
	if err != nil {
		t.Fatalf("New<Chip>Full: %v", err)
	}
	// ... checks using t.Errorf on mismatch ...
}
```

Run via `go test ./periph/chips/<category>/... -run '(?i)^Test_?<chip>'`, wrapped by `test_linux.sh --level unit`.

### Sigrok decoder test

A sigrok decoder test is a captured session file, not a script. Create `sigrok/tests/<chip>/` and commit one `.sr` file recorded from real hardware:

```
sigrok/tests/<chip>/<Chip>-Test.sr
```

The session must capture at least one complete write and one complete read of the chip's primary registers. Filename convention: title-case chip name, hyphen, `Test`, `.sr` extension (e.g. `INA226-Test.sr`).

There is no runner script for exploratory PulseView verification. Verify by opening the `.sr` file in PulseView with the decoder loaded and confirming the annotations are correct.

**If the chip has a timing constraint you want conformance-checked:** the decoder's annotation names must include the start/end pair for that check (e.g. `conversion_start` / `conversion_done`), matching the check name in `specs/<category>/<chip>_timing.conf` exactly — see `specs/_template_chip.md`'s Sigrok Decoder section and `specs/testing_framework.md`, "Conformance Implementation".
