# Examples Restructure (C++, Go, Rust, Python)

## Target Layout

```
cpp/examples/
  arduino/
    <category>/
      <Chip>/
        minimal/       # dir name must match .ino filename
          minimal.ino
        complete/
          complete.ino
        demo/
          demo.ino
  zephyr/
    <category>/
      <Chip>/
        minimal/       # standalone Zephyr app
          main.cpp
          CMakeLists.txt
          prj.conf
        complete/
          main.cpp
          CMakeLists.txt
          prj.conf
        demo/
          main.cpp
          CMakeLists.txt
          prj.conf
```

27 chips × 3 tiers × 2 platforms = 162 examples to move. File content is unchanged except `CMakeLists.txt` (path depth changes and `src/main.cpp` → `main.cpp`).

---

## Implementation Steps

### 1. Move Arduino examples

For each of the 27 chips, `git mv` three `.ino` files into the new path. The file name changes from `<Chip>_<Tier>.ino` to `minimal.ino` / `complete.ino` / `demo.ino`.

Pattern (AHT21, category `environmental`):
```bash
mkdir -p cpp/examples/arduino/environmental/AHT21/minimal
mkdir -p cpp/examples/arduino/environmental/AHT21/complete
mkdir -p cpp/examples/arduino/environmental/AHT21/demo
git mv cpp/examples/AHT21_Minimal/AHT21_Minimal.ino cpp/examples/arduino/environmental/AHT21/minimal/minimal.ino
git mv cpp/examples/AHT21_Complete/AHT21_Complete.ino cpp/examples/arduino/environmental/AHT21/complete/complete.ino
git mv cpp/examples/AHT21_Demo/AHT21_Demo.ino cpp/examples/arduino/environmental/AHT21/demo/demo.ino
git rm -rf cpp/examples/AHT21_Minimal cpp/examples/AHT21_Complete cpp/examples/AHT21_Demo
```

All 27 chips:

| Category | Chips |
|----------|-------|
| adc_dac | HX711, MCP4725, MCP4728, PCF8591 |
| comms | Rda5807m |
| display | PCF8576 |
| environmental | AHT21, BME280, BME680 |
| gas | ENS160 |
| gnss | NEO6 |
| humidity | DHT11 |
| imu | Mpu6050 |
| io_expander | MCP23017, PCF8574, PCF8575 |
| led | SK6812RGBW, WS2812B |
| light | APDS9960 |
| magnetometer | AS5600 |
| memory | 24AA02UID |
| power | INA219, INA226, INA3221 |
| pressure | BMP180, BMP280 |
| rfid | MFRC522 |

### 2. Move Zephyr examples

For each chip+tier, `git mv` `src/main.cpp` and `prj.conf` into the new per-tier subdirectory. Old `CMakeLists.txt` files are deleted (rewritten in step 3).

Pattern (AHT21 minimal):
```bash
mkdir -p cpp/examples/zephyr/environmental/AHT21/minimal
git mv cpp/examples/AHT21_Minimal_Zephyr/src/main.cpp cpp/examples/zephyr/environmental/AHT21/minimal/main.cpp
git mv cpp/examples/AHT21_Minimal_Zephyr/prj.conf cpp/examples/zephyr/environmental/AHT21/minimal/prj.conf
git rm cpp/examples/AHT21_Minimal_Zephyr/CMakeLists.txt
git rm -rf cpp/examples/AHT21_Minimal_Zephyr
```

Repeat for complete and demo. HX711 and PCF8575 are missing `CMakeLists.txt`/`prj.conf` in the current tree — create fresh in step 3.

### 3. Write new Zephyr CMakeLists.txt (81 files)

From `cpp/examples/zephyr/<category>/<Chip>/<tier>/` to `cpp/` is 5 levels up: `../../../../../`.

Standard template (chips with a `.cpp` source):
```cmake
cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(<chip_lower>_<tier>_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

target_sources(app PRIVATE
    main.cpp
    ${CPP_DIR}/src/chips/<category>/<Chip>.cpp
)
target_include_directories(app PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/<category>
)
```

HX711 is header-only — omit the `.cpp` line from `target_sources`.

This also fixes an existing inconsistency: some old `CMakeLists.txt` files used `../../..` (Periph root) instead of `../..` (cpp root). The new uniform `../../../../../` is always correct.

### 4. Update CLAUDE.md

Replace the `cpp/examples/` block in the repository layout section with the new structure above.

### 5. Update AGENTS.md

- Update "Where things go" table rows for Arduino and Zephyr C++ examples
- Update the Zephyr examples section: new path pattern, `src/main.cpp` → `main.cpp`, updated CMakeLists.txt template

### 6. Update EXAMPLES.md

- Arduino section: update file layout block and `arduino-cli` example commands to new paths
- Zephyr section: update layout block and `west build` example (from `cd cpp/examples/BMP280_Minimal_Zephyr` → `cd cpp/examples/zephyr/pressure/BMP280/minimal`)

### 7. Check release pipeline

Check `release.yml` and `release.sh` for any arduino-branch sync logic that copies from `cpp/examples/`. Update to source from `cpp/examples/arduino/` and exclude `cpp/examples/zephyr/`.

---

## Files Touched

- `cpp/examples/**` — 162 file moves + 81 new CMakeLists.txt
- `CLAUDE.md` — repository layout section
- `AGENTS.md` — Zephyr examples section + paths table
- `EXAMPLES.md` — Arduino and Zephyr sections
- `.github/workflows/release.yml` — if arduino example sync exists
- `release.sh` — if arduino example sync exists

## Verification (C++)

1. Spot-check: `cpp/examples/arduino/environmental/AHT21/minimal/minimal.ino` exists, content unchanged.
2. CMakeLists.txt path: trace `../../../../../` from a tier dir confirms it lands at `cpp/`.
3. Zephyr build smoke test: `cd cpp/examples/zephyr/environmental/AHT21/minimal && west build -b <board>`
4. Arduino CLI: `arduino-cli compile --fqbn arduino:avr:uno cpp/examples/arduino/environmental/AHT21/minimal/minimal.ino`

---

# Go Examples Restructure

## Target Layout

```
go/examples/
  linux/
    <category>/
      <chip>/
        minimal/minimal.go
        complete/complete.go
        demo/demo.go
  tinygo/
    <category>/
      <chip>/
        minimal/minimal.go
        complete/complete.go
        demo/demo.go
```

28 chips × 3 tiers × 2 platforms = 168 examples to move. File content is unchanged; only paths and filenames change (`main.go` → `minimal.go` / `complete.go` / `demo.go`).

## Why per-tier subdirectories are required

Go requires each `main` package to be in its own directory. Placing `minimal.go`, `complete.go`, and `demo.go` in the same directory would break `go build ./...` (multiple `func main()` in one package). Per-tier subdirectories are the only layout that is both `go build`-compatible and allows descriptive filenames.

## Why a single file can't target both platforms

- The `machine` package (pins, I2C config) is TinyGo-only and won't compile under standard Go
- Transport constructors have different signatures: Linux returns `(*I2CTransport, error)`, TinyGo returns `*I2CTransport`
- Linux uses `os.Stderr`, `strconv`; TinyGo doesn't have those

## Implementation Steps

### 1. Move Linux examples

For each chip+tier, `git mv` and rename `main.go` → `<tier>.go` in the new path.

Pattern (aht21, category `environmental`):
```bash
mkdir -p go/examples/linux/environmental/aht21/minimal
mkdir -p go/examples/linux/environmental/aht21/complete
mkdir -p go/examples/linux/environmental/aht21/demo
git mv go/examples/environmental/aht21/minimal/main.go   go/examples/linux/environmental/aht21/minimal/minimal.go
git mv go/examples/environmental/aht21/complete/main.go  go/examples/linux/environmental/aht21/complete/complete.go
git mv go/examples/environmental/aht21/demo/main.go      go/examples/linux/environmental/aht21/demo/demo.go
git rm -rf go/examples/environmental/aht21/minimal go/examples/environmental/aht21/complete go/examples/environmental/aht21/demo
```

### 2. Move TinyGo examples

Same pattern, sourcing from `<chip>/minimal_tinygo/`, `complete_tinygo/`, `demo_tinygo/`.

Pattern (aht21):
```bash
mkdir -p go/examples/tinygo/environmental/aht21/minimal
mkdir -p go/examples/tinygo/environmental/aht21/complete
mkdir -p go/examples/tinygo/environmental/aht21/demo
git mv go/examples/environmental/aht21/minimal_tinygo/main.go  go/examples/tinygo/environmental/aht21/minimal/minimal.go
git mv go/examples/environmental/aht21/complete_tinygo/main.go go/examples/tinygo/environmental/aht21/complete/complete.go
git mv go/examples/environmental/aht21/demo_tinygo/main.go     go/examples/tinygo/environmental/aht21/demo/demo.go
git rm -rf go/examples/environmental/aht21
```

Repeat for all chips. Once all chips under a category are moved, `git rm -rf go/examples/<category>` cleans up the old category dir.

### 3. Update CLAUDE.md

Replace the `go/examples/` block in the repository layout section:

**Old:**
```
  examples/
    <category>/
      <chip>/
        minimal/main.go          # go build                     (Linux host)
        minimal_tinygo/main.go   # tinygo build -target=pico-w  (embedded)
        complete/main.go
        complete_tinygo/main.go
        demo/main.go
        demo_tinygo/main.go
```

**New:**
```
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
```

### 4. Update AGENTS.md

- Update "Where things go" table rows for Go Linux and TinyGo examples
- Update any `go build` / `tinygo build` command examples referencing old paths

### 5. Update EXAMPLES.md

- Update Go section file layout block and example `go run` / `tinygo build` commands

## Files Touched

- `go/examples/**` — 168 file moves (rename `main.go` → `<tier>.go`)
- `CLAUDE.md` — repository layout section
- `AGENTS.md` — Go examples section + paths table
- `EXAMPLES.md` — Go section

---

# Rust Examples Restructure

## Target Layout

```
rust/examples/
  linux/
    <category>/
      <chip>/
        minimal/
          Cargo.toml
          src/main.rs
        complete/
          Cargo.toml
          src/main.rs
        demo/
          Cargo.toml
          src/main.rs
  embedded/
    esp32s3/         ← current; rp2040/, stm32f4/, etc. added here as needed
      <category>/
        <chip>/
          minimal/
            Cargo.toml
            src/main.rs
            rust-toolchain.toml
            .cargo/config.toml
          complete/
            ...
          demo/
            ...
```

Linux examples (`linux-embedded-hal`, std) and embedded examples (`no_std`, MCU-specific HAL) differ fundamentally in entry point, HAL, and build config, so a platform split is required. Each MCU under `embedded/` gets its own subdirectory. Chip driver calls are identical across all MCUs — only the setup block differs.

**Workspace membership:** Linux examples are included via a glob; all `embedded/` crates are excluded (MCU-specific toolchains are incompatible with the standard workspace build, same as the existing `_test_esp32s3` crates).

## Implementation Steps

### 1. Move existing Linux examples

Pattern (aht21, category `environmental`):
```bash
mkdir -p rust/examples/linux/environmental/aht21/minimal/src
mkdir -p rust/examples/linux/environmental/aht21/complete/src
mkdir -p rust/examples/linux/environmental/aht21/demo/src
git mv rust/examples/aht21_minimal/Cargo.toml   rust/examples/linux/environmental/aht21/minimal/Cargo.toml
git mv rust/examples/aht21_minimal/src/main.rs  rust/examples/linux/environmental/aht21/minimal/src/main.rs
git mv rust/examples/aht21_complete/Cargo.toml  rust/examples/linux/environmental/aht21/complete/Cargo.toml
git mv rust/examples/aht21_complete/src/main.rs rust/examples/linux/environmental/aht21/complete/src/main.rs
git mv rust/examples/aht21_demo/Cargo.toml      rust/examples/linux/environmental/aht21/demo/Cargo.toml
git mv rust/examples/aht21_demo/src/main.rs     rust/examples/linux/environmental/aht21/demo/src/main.rs
git rm -rf rust/examples/aht21_minimal rust/examples/aht21_complete rust/examples/aht21_demo
```

File content (`src/main.rs`) is unchanged.

### 2. Create ESP32-S3 examples

New content, modelled on the existing `rust/tests/*_test_esp32s3/` crates. Each tier dir needs:

**`Cargo.toml`** — same deps as the test crates (`esp-hal`, `esp-backtrace`, `esp-println`, `esp-bootloader-esp-idf`), no `linux-embedded-hal`.

**`rust-toolchain.toml`:**
```toml
[toolchain]
channel = "esp"
```

**`.cargo/config.toml`:**
```toml
[build]
target = "xtensa-esp32s3-none-elf"

[target.xtensa-esp32s3-none-elf]
linker = "/home/till/.rustup/toolchains/esp/xtensa-esp-elf/esp-15.2.0_20250920/xtensa-esp-elf/bin/xtensa-esp32s3-elf-gcc"
rustflags = ["-C", "link-arg=-Tlinkall.x"]

[unstable]
build-std = ["core"]
```

**`src/main.rs`** — `#![no_std]`, `#![no_main]`, `#[esp_hal::main]` entry, `esp_hal::i2c` for transport, `esp_println::println!`, `loop {}` at end. Chip driver call identical to Linux version.

### 3. Update workspace members glob

In `rust/Cargo.toml`, replace enumerated example entries with a scoped glob that covers only Linux (ESP32 excluded, as with the existing tests):

**Old:** individual entries like `"examples/aht21_minimal"`, `"examples/aht21_complete"`, …

**New:**
```toml
members = [
    "periph",
    "tests/transport/uart_test",
    "examples/linux/*/*/*",
]
```

### 4. Update CLAUDE.md

Replace the `rust/examples/` block:

**Old:**
```
  examples/
    <chip>_minimal/     # Cargo.toml + src/main.rs  (Linux host)
    <chip>_complete/
    <chip>_demo/
```

**New:**
```
  examples/
    linux/
      <category>/
        <chip>/
          minimal/      # Cargo.toml + src/main.rs
          complete/
          demo/
    esp32s3/            # excluded from workspace; build per-crate
      <category>/
        <chip>/
          minimal/      # Cargo.toml + src/main.rs + rust-toolchain.toml + .cargo/config.toml
          complete/
          demo/
```

### 5. Update AGENTS.md and EXAMPLES.md

- Update Rust examples path pattern and `cargo run` / `cargo build` example commands for both platforms

## Files Touched

- `rust/examples/**` — Linux: file moves only; ESP32: new files
- `rust/Cargo.toml` — replace enumerated members with `"examples/linux/*/*/*"` glob
- `CLAUDE.md` — repository layout section
- `AGENTS.md` — Rust examples path pattern
- `EXAMPLES.md` — Rust section

## Verification (Rust)

1. `cargo build --workspace` from `rust/` succeeds (Linux examples included, ESP32 excluded)
2. Spot-check Linux: `rust/examples/linux/environmental/aht21/minimal/src/main.rs` content unchanged
3. ESP32 build: `cd rust/examples/esp32s3/environmental/aht21/minimal && cargo build --release`

---

## Verification (Go)

1. Spot-check: `go/examples/linux/environmental/aht21/minimal/minimal.go` has `//go:build linux && !tinygo` tag, content otherwise unchanged.
2. Linux build: `go build ./go/examples/linux/...`
3. TinyGo build: `tinygo build -target=pico-w ./go/examples/tinygo/environmental/aht21/minimal/`

---

# Python Examples: Make Platform-Agnostic

## Current State

Directory structure is correct (`python/examples/<category>/<chip>/[minimal,complete,demo].py`, single file per tier). No platform split needed — `i2c_auto` handles MicroPython/CircuitPython/Linux detection at runtime.

However, 69 of 78 example files are hardcoded to MicroPython via platform-specific imports.

## Audit Results

| Status | Count | Import used |
|--------|-------|-------------|
| ✓ Correct | 9 | `i2c_auto` |
| ✗ Broken | 45 | `i2c_micropython` — auto variant exists, import just needs fixing |
| ✗ Broken | 9 | `neopixel_micropython` — no auto variant yet |
| ✗ Broken | 9 | `i2c_micropython` in io_expander (same fix as above) |
| ✗ Broken | 3 | `hx711_micropython` — no auto variant yet |
| ✗ Broken | 3 | `uart_micropython` — no auto variant yet |
| ✗ Broken | 3 | `dhtxx_micropython` — no auto variant yet |
| ✗ Broken | 3 | `spi_micropython` — no auto variant yet |

Chips still using `i2c_micropython` (need import fix only): mcp4725, mcp4728, pcf8591, rda5807m, pcf8576, bme280, bme680, mpu6050, mcp23017, pcf8574, pcf8575, apds9960, as5600, ina219, ina226, ina3221, bmp180, bmp280.

## Implementation Steps

### 1. Create missing `_auto` transport wrappers

Create five new files in `python/periph/transport/`, each following the same try/except pattern as `i2c_auto.py` (MicroPython → CircuitPython → Linux fallback):

- `hx711_auto.py` — wraps `hx711_micropython`, `hx711_circuitpython`, `hx711_linux`
- `uart_auto.py` — wraps `uart_micropython`, `uart_circuitpython`, `uart_linux`
- `dhtxx_auto.py` — wraps `dhtxx_micropython`, `dhtxx_circuitpython`, `dhtxx_linux`
- `neopixel_auto.py` — wraps `neopixel_micropython`, `neopixel_circuitpython`, `neopixel_linux`
- `spi_auto.py` — wraps `spi_micropython`, `spi_circuitpython`, `spi_linux`

### 2. Fix all broken example imports

Replace every platform-specific import with its auto equivalent:

| Old import | New import | Files affected |
|------------|------------|----------------|
| `from periph.transport.i2c_micropython import I2CTransport` | `from periph.transport.i2c_auto import I2CTransport` | 45 files |
| `from periph.transport.hx711_micropython import HX711Transport` | `from periph.transport.hx711_auto import HX711Transport` | 3 files |
| `from periph.transport.uart_micropython import UARTTransport` | `from periph.transport.uart_auto import UARTTransport` | 3 files |
| `from periph.transport.dhtxx_micropython import DHTxxTransport` | `from periph.transport.dhtxx_auto import DHTxxTransport` | 3 files |
| `from periph.transport.neopixel_micropython import NeoPixelTransport` | `from periph.transport.neopixel_auto import NeoPixelTransport` | 6 files |
| `from periph.transport.spi_micropython import SPITransport` | `from periph.transport.spi_auto import SPITransport` | 3 files |

Also update the commented-out alternative transport imports in `gnss/neo6` examples (currently reference `i2c_micropython` and `spi_micropython`).

## Files Touched

- `python/periph/transport/hx711_auto.py` — new
- `python/periph/transport/uart_auto.py` — new
- `python/periph/transport/dhtxx_auto.py` — new
- `python/periph/transport/neopixel_auto.py` — new
- `python/periph/transport/spi_auto.py` — new
- `python/examples/**` — 69 import line fixes (no structural changes)

## Verification (Python)

1. `grep -r "_micropython" python/examples/` returns no results.
2. Run `python python/examples/environmental/aht21/minimal.py` on Linux — succeeds via auto-detected `i2c_linux`.
3. Spot-check: `python/examples/rfid/mfrc522/minimal.py` imports `spi_auto`.
