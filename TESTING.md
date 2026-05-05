# TESTING.md

Hardware tests for each chip run on all supported platforms and produce identical output — one `PASS`/`FAIL` line per check and a final `===DONE: N passed, N failed===` line. The runners exit 0 on full pass, 1 on any failure, 2 if the test did not complete.

## Quick start

1. Copy the testconfig example for the platform(s) you want to test:
   ```
   cp cpp/testconfig.example         cpp/testconfig
   cp cpp/testconfig_zephyr.example  cpp/testconfig_zephyr
   cp python/testconfig.example      python/testconfig
   cp nodejs/testconfig.example      nodejs/testconfig
   cp rust/testconfig.example            rust/testconfig
   cp rust/testconfig_esp32s3.example    rust/testconfig_esp32s3
   ```
2. Fill in your board's values (pins, port, bus number).
3. Run the relevant runner:
   ```
   cpp/test_arduino.sh    power/ina226
   cpp/test_linux.sh      power/ina226
   cpp/test_zephyr.sh     power/ina226
   python/test_mp.sh      power/ina226
   python/test_cp.sh      power/ina226
   python/test_linux.sh   power/ina226
   nodejs/test.sh         power/ina226
   rust/test_linux.sh     power/ina226
   rust/test_esp32s3.sh   power/ina226
   ```

`testconfig` files are gitignored — never commit them.

---

## Platform reference

### Arduino (`cpp/test_arduino.sh`)

**Prerequisites:** `arduino-cli`, `pyserial` (`pip install pyserial`)

**Config:** `cpp/testconfig`

| Variable | Description |
|----------|-------------|
| `FQBN` | Full board FQBN, e.g. `esp32:esp32:esp32s3:CDCOnBoot=cdc` |
| `PORT` | Serial port, e.g. `/dev/ttyACM0` |
| `I2C_SDA` / `I2C_SCL` | GPIO pin numbers |
| `I2C_FREQ` | I²C clock in Hz (default 400000) |
| `LINUX_I2C_BUS` | Bus number for `test_linux.sh` (see below) |

**ESP32-S3 note:** Add `:CDCOnBoot=cdc` to the FQBN so `Serial` maps to the USB CDC port, otherwise the serial reader will time out.

The runner compiles, uploads, and reads serial output. Use `--compile-only` to verify builds without hardware:
```
cpp/test_arduino.sh --compile-only power/ina226
```

---

### Linux GCC (`cpp/test_linux.sh`)

**Prerequisites:** `g++` (C++17), `linux/i2c-dev.h` (kernel headers)

**Config:** `cpp/testconfig` — only `LINUX_I2C_BUS` and `I2C_ADDR` are used.

Builds a native binary in a temp directory and runs it directly on the host. No board required. Supports `--compile-only`.

---

### MicroPython (`python/test_mp.sh`)

**Prerequisites:** `mpremote` (`pip install mpremote`)

**Config:** `python/testconfig`

| Variable | Description |
|----------|-------------|
| `MP_PORT` | Serial port or `auto` |
| `MP_I2C_ID` | `machine.I2C` bus ID |
| `MP_SDA` / `MP_SCL` | GPIO pin numbers |
| `MP_I2C_FREQ` | I²C clock in Hz |
| `I2C_ADDR` | Device I²C address |

Uses `mpremote mount` — the `periph` library is imported directly from the host filesystem. Nothing is written to the board.

---

### CircuitPython (`python/test_cp.sh`)

**Prerequisites:** `pyserial` (`pip install pyserial`), CIRCUITPY USB drive mounted

**Config:** `python/testconfig`

| Variable | Description |
|----------|-------------|
| `CP_PORT` | Serial port or `auto` |
| `CP_SDA` / `CP_SCL` | Pin expressions, e.g. `board.IO1` |
| `I2C_ADDR` | Device I²C address |

The runner copies `periph/` to `<CIRCUITPY>/lib/periph` and a generated `_testconfig.py` to the drive root, executes the test via raw REPL (`cp_runner.py`), then removes the files.

**Note:** `ampy` and `mpremote` are not compatible with CircuitPython 10+ because the status bar injects OSC escape sequences that break the raw REPL handshake. `cp_runner.py` handles this by entering raw REPL without triggering a soft reset.

---

### Linux kernel / Python (`python/test_linux.sh`)

**Prerequisites:** `smbus2` (`pip install smbus2`)

**Config:** `python/testconfig` — only `LINUX_I2C_BUS` and `I2C_ADDR` are used.

Runs directly on the host. No board required.

---

### Node.js (`nodejs/test.sh`)

**Prerequisites:** Node.js, `npm install` run from `nodejs/`

**Config:** `nodejs/testconfig`

| Variable | Description |
|----------|-------------|
| `I2C_BUS` | `/dev/i2c-N` bus number |
| `I2C_ADDR` | Device I²C address (hex) |

Runs directly on the host. No board required.

---

### Zephyr RTOS (`cpp/test_zephyr.sh`)

**Prerequisites:** `west` and a Zephyr workspace (`ZEPHYR_BASE` set or `west init` done)

**Config:** `cpp/testconfig_zephyr`

| Variable | Description |
|----------|-------------|
| `ZEPHYR_BOARD` | Board identifier, e.g. `nrf52840dk/nrf52840`, `rpi_pico` |
| `ZEPHYR_PORT` | Serial port for reading test output |
| `I2C_ADDR` | Device I²C address (hex) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

The runner calls `west build` then `west flash`, and reads serial output using `cpp/read_serial_zephyr.py`. Supports `--compile-only` (skips flash and serial):
```
cpp/test_zephyr.sh --compile-only power/ina226
```

**Devicetree:** The test app uses `DT_NODELABEL(i2c0)` by default. If your board uses a different I²C node label, provide a board overlay at `cpp/tests/<category>/<chip>_test_zephyr/boards/<board>.overlay` with the correct alias.

---

### Rust Linux (`rust/test_linux.sh`)

**Prerequisites:** `cargo` (stable), `linux/i2c-dev.h` kernel driver (`i2c-dev` module)

**Config:** `rust/testconfig`

| Variable | Description |
|----------|-------------|
| `I2C_BUS` | Bus number for `/dev/i2c-N` |
| `I2C_ADDR` | Device I²C address (hex) |

Builds with `cargo build --release` and runs the binary directly. Supports `--compile-only`:
```
rust/test_linux.sh --compile-only power/ina226
```

---

### Rust ESP32-S3 (`rust/test_esp32s3.sh`)

**Prerequisites:**
- `rustup` with the `esp` toolchain: `cargo install espup && espup install`
- `cargo-espflash`: `cargo install cargo-espflash`
- `pyserial`: `pip install pyserial`

**Config:** `rust/testconfig_esp32s3`

| Variable | Description |
|----------|-------------|
| `ESP32S3_PORT` | Serial port, e.g. `/dev/ttyACM0` |
| `I2C_ADDR` | Device I²C address (hex, default `0x40`) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

The runner builds with `cargo build --release` using the `esp` toolchain (selected automatically via `rust-toolchain.toml`), flashes with `cargo espflash flash`, and reads serial output using `rust/read_serial_esp32s3.py`. Supports `--compile-only`:
```
rust/test_esp32s3.sh --compile-only power/ina226
```

SDA/SCL pin assignments are constants in `src/main.rs` (default GPIO1/GPIO2). The test crate is standalone — not part of the `rust/` workspace — because it requires the `esp` toolchain.

---

## Writing tests for a new chip

Add one test file per platform following the naming convention:

| Platform | Path |
|----------|------|
| Arduino | `cpp/tests/<category>/<chip>_test/<chip>_test.ino` |
| Linux GCC | `cpp/tests/<category>/<chip>_test_linux/<chip>_test_linux.cpp` |
| MicroPython | `python/tests/<category>/<chip>_test.py` |
| CircuitPython | `python/tests/<category>/<chip>_test_cp.py` |
| Linux kernel | `python/tests/<category>/<chip>_test_linux.py` |
| Node.js | `nodejs/tests/<category>/<chip>_test.js` |
| Zephyr RTOS | `cpp/tests/<category>/<chip>_test_zephyr/src/main.cpp` + `CMakeLists.txt` + `prj.conf` |
| Rust Linux | `rust/tests/<category>/<chip>_test/src/main.rs` + `Cargo.toml` |

Use `INA226` as the reference implementation. Every test must:

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

### Linux GCC test template

```cpp
#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    // ... checks using printf("PASS %s\n", label) ...
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
```

### Python (MicroPython) test template

```python
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.<category>.<chip> import <Chip>Full
from machine import I2C, Pin

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
# ... checks using print('PASS', label) ...
print('===DONE: {} passed, {} failed==='.format(passed, failed))
```

`_testconfig.py` is generated from `python/testconfig` by `test_mp.sh` at run time and is never committed.

### Python (CircuitPython) test template

Same structure as MicroPython, but use:
```python
import busio, _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport

i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)  # SCL first
```

Use `time.sleep(0.001)` instead of `time.sleep_ms(1)`.

### Python (Linux) test template

```python
import os
from periph.transport.i2c_linux import I2CTransport

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x40'), 16)

transport = I2CTransport(I2C_BUS, I2C_ADDR)
# ... checks ...
transport.close()
```

### Node.js test template

```js
'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { <Chip>Full }   = require('../../packages/periph/src/chips/<category>/<chip>');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
// ... checks using console.log('PASS', label) ...
transport.close();
console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
```

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
    ${CPP_DIR}/src/transport
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

`src/main.cpp`:
```cpp
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
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
    I2CTransportZephyr transport(dev, INA226_ADDR);
    <Chip>Full chip(transport);
    // ... checks ...
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
```

The `DT_NODELABEL(i2c0)` default works for most boards. For boards that use a different I²C node label, add a board overlay at `cpp/tests/<category>/<chip>_test_zephyr/boards/<board>.overlay`.

### Rust Linux test template

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
