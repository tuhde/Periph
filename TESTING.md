# TESTING.md

Hardware tests for each chip run on all supported platforms and produce identical output — one `PASS`/`FAIL` line per check and a final `===DONE: N passed, N failed===` line. The runners exit 0 on full pass, 1 on any failure, 2 if the test did not complete.

## Quick start

1. Copy the testconfig example for the platform(s) you want to test:
   ```
   cp cpp/testconfig.example         cpp/testconfig
   cp cpp/testconfig_zephyr.example  cpp/testconfig_zephyr
   cp cpp/testconfig_espidf.example  cpp/testconfig_espidf

   cp cpp/testconfig_picosdk.example cpp/testconfig_picosdk
   cp python/testconfig.example      python/testconfig
   cp nodejs/testconfig.example      nodejs/testconfig
   cp rust/testconfig.example            rust/testconfig
   cp rust/testconfig_esp32s3.example    rust/testconfig_esp32s3
   cp go/testconfig.example              go/testconfig
   cp go/testconfig_tinygo.example       go/testconfig_tinygo
   ```
2. Fill in your board's values (pins, port, bus number).
3. Run the relevant runner:
   ```
   cpp/test_arduino.sh    power/ina226
   cpp/test_linux.sh      power/ina226
   cpp/test_zephyr.sh     power/ina226
   cpp/test_espidf.sh     power/ina226

   cpp/test_picosdk.sh    power/ina226
   python/test_mp.sh      power/ina226
   python/test_cp.sh      power/ina226
   python/test_linux.sh   power/ina226
   nodejs/test.sh         power/ina226
   rust/test_linux.sh     power/ina226
   rust/test_esp32s3.sh   power/ina226
   go/test_linux.sh       power/ina226
   go/test_tinygo.sh      power/ina226
   I2C_BUS=1 I2C_ADDR=0x40 jbang jvm/tests/power/ina226/Ina226Test.java
   ```

`testconfig` files are gitignored — never commit them.

The JVM test requires no config file — pass `I2C_BUS` and `I2C_ADDR` as environment variables directly on the command line. The sigrok decoder has no automated runner; see the [Sigrok decoders](#sigrok-decoders-pulseview) section below.

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

### ESP-IDF (`cpp/test_espidf.sh`)

**Prerequisites:** ESP-IDF ≥5.1 with `IDF_PATH` exported (or its `export.sh` sourced), `idf.py` on `PATH`, `pyserial` (`pip install pyserial`)

**Config:** `cpp/testconfig_espidf` (copy from `testconfig_espidf.example`)

| Variable | Description |
|----------|-------------|
| `IDF_TARGET` | Target chip — `esp32` (default), `esp32s2`, `esp32s3`, `esp32c3`, `esp32c6`, `esp32h2` |
| `ESPIDF_PORT` | USB-CDC serial port, e.g. `/dev/ttyUSB0` (Linux), `/dev/tty.usbserial-*` (macOS) |
| `I2C_ADDR` | Device I²C address (hex) |
| `SPI_CS` | SPI chip-select GPIO pin (for SPI-mode tests) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

The runner builds each test as a standalone ESP-IDF project (mirroring how every Zephyr example is a separate `west build` app), flashes via `idf.py -p <port> flash`, and reads the USB-CDC serial output. Supports `--compile-only` (skips flash and serial):
```
cpp/test_espidf.sh --compile-only power/ina226
```

Pins: each chip's test app hard-codes its default I²C pins (`GPIO21` SDA / `GPIO22` SCL on `I2C_NUM_0`) and SPI pins (`MOSI=GPIO13`, `SCK=GPIO14` on `SPI2_HOST` for NeoPixel). To override, edit the per-chip `main/CMakeLists.txt` or the bus-config block at the top of `main.cpp`.

**Note on the I²C frequency:** the generated tests configure `I2C_NUM_0` at 400 kHz (fast-mode, the rate every chip in this repo supports). Drop to 100 kHz by editing the per-test `scl_speed_hz` field if your device is standard-mode only.

### Raspberry Pi Pico SDK (`cpp/test_picosdk.sh`)

**Prerequisites:** `pico-sdk` (`PICO_SDK_PATH` set or `~/pico-sdk` discovered by `pico_sdk_init.cmake`), `picotool` on `PATH`, `pyserial` (`pip install pyserial`)

**Config:** `cpp/testconfig_picosdk` (copy from `testconfig_picosdk.example`)

| Variable | Description |
|----------|-------------|
| `PICO_SDK_PATH` | Path to your local pico-sdk checkout (auto-discovered if unset) |
| `PICO_BOARD` | Board identifier — `pico` (RP2040, default), `picow`, `pico2`, `pico2_w` |
| `PICOSDK_PORT` | USB-CDC serial port, e.g. `/dev/ttyACM0` (Linux), `/dev/tty.usbmodem*` (macOS) |
| `I2C_ADDR` | Device I²C address (hex) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default 20) |

The runner builds each test as a standalone pico-sdk CMake project (mirroring how every Zephyr example is a separate `west build` app), flashes the resulting UF2 via `picotool load -x`, and reads the USB-CDC serial output. Supports `--compile-only` (skips flash and serial):
```
cpp/test_picosdk.sh --compile-only power/ina226
```

Pins: each chip's test app hard-codes its default I²C pins (`GP4` SDA / `GP5` SCL on `i2c0`) and SPI pins (`GP3` MOSI on `spi0`). To override, edit the per-chip `CMakeLists.txt` or wire a board-specific overlay.

**Note on `i2c_init` frequency:** the generated tests configure `i2c0` at 100 kHz (the standard-mode rate that every chip in this repo supports). Bump to 400 kHz by editing the per-test `i2c_init(...)` call if your device supports fast-mode.

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

### JVM — Java/Kotlin/Groovy (`jbang`)

**Prerequisites:** JBang (`sdk install jbang` or `curl -Ls https://sh.jbang.dev | bash`), Java 22+

**Config:** none — pass variables inline as environment variables.

| Variable | Description |
|----------|-------------|
| `I2C_BUS` | I²C bus number (default `1`) |
| `I2C_ADDR` | Device I²C address in hex (default `0x40`) |

JVM tests run directly on Linux hardware. The JBang shebang line makes the script self-executing; the `--enable-native-access=ALL-UNNAMED` flag is required for the FFM (Foreign Function & Memory) API. No build step is needed — JBang resolves dependencies from Maven Central on first run.

Run a test:
```
I2C_BUS=1 I2C_ADDR=0x40 jbang jvm/tests/power/ina226/Ina226Test.java
```

There is no `--compile-only` flag. To verify the script compiles without hardware, omit `I2C_BUS`/`I2C_ADDR` — the defaults will be used and the test will fail if no device is present, but the JVM will still type-check the source.

---

### Go Linux (`go/test_linux.sh`)

**Prerequisites:** Go ≥ 1.24, `linux/i2c-dev.h` kernel driver (`modprobe i2c-dev`)

**Config:** `go/testconfig`

| Variable | Description |
|----------|-------------|
| `I2C_BUS` | Bus number for `/dev/i2c-N` (default `1`) |
| `I2C_ADDR` | Device I²C address in hex (optional — falls back to `chip_defaults`) |

Builds a native binary and runs it directly on the host. No board required. Supports `--compile-only`:
```
go/test_linux.sh --compile-only power/ina226
```

Run a test:
```
go/test_linux.sh power/ina226
```

---

### Go TinyGo / Pico W (`go/test_tinygo.sh`)

**Prerequisites:**
- TinyGo ≥ 0.41 (`tinygo` on PATH)
- Raspberry Pi Pico W in BOOTSEL mode (UF2 mount visible)
- `pyserial` (`pip install pyserial`)

**Config:** `go/testconfig_tinygo`

| Variable | Description |
|----------|-------------|
| `UF2_MOUNT` | Path to the Pico W UF2 drive (default `/media/$USER/RPI-RP2`) |
| `SERIAL_PORT` | Serial port for output (default `/dev/ttyACM0`) |
| `SERIAL_TIMEOUT` | Seconds to wait for output (default `20`) |

The runner builds a UF2 with `tinygo build -target=pico-w`, copies it to the UF2 mount, and reads serial output via `go/read_serial_tinygo.py`. Supports `--compile-only` (skips flash and serial):
```
go/test_tinygo.sh --compile-only power/ina226
```

Run a test (Pico W must be in BOOTSEL mode):
```
go/test_tinygo.sh power/ina226
```

---

### Sigrok decoders (PulseView) {#sigrok-decoders-pulseview}

**Prerequisites:** PulseView with the decoder installed (`sigrok/<chip>/` copied or symlinked into the sigrok protocol-decoder search path)

**No automated runner.** Verification is manual:

1. Open the `.sr` session file from `sigrok/tests/<chip>/` in PulseView.
2. Add the chip's decoder stacked on the `I2C` decoder.
3. Confirm that annotations match the register values shown in the session.

The `.sr` file is committed alongside the decoder. One session file per chip is sufficient; it should exercise at least one write and one read of the chip's primary registers.

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
| Go Linux | `go/tests/<category>/<chip>_test/main.go` |
| Go TinyGo | `go/tests/<category>/<chip>_test_tinygo/main.go` |
| JVM | `jvm/tests/<category>/<chip>/<Chip>Test.java` |

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
        ${CPP_DIR}/src/transport
        ${CPP_DIR}/src/chips/<category>
    REQUIRES driver
)
```

`main/main.cpp`:
```cpp
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "<Chip>.h"

static int passed = 0, failed = 0;

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
    ${CPP_DIR}/src/transport
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
#include "I2CTransportPicoSDK.h"   // or SPITransportPicoSDK.h / UARTTransportPicoSDK.h
#include "<Chip>.h"

i2c_init(i2c0, 100 * 1000);          // 100 kHz, standard mode
gpio_set_function(4, GPIO_FUNC_I2C); // SDA = GP4
gpio_set_function(5, GPIO_FUNC_I2C); // SCL = GP5
gpio_pull_up(4);
gpio_pull_up(5);

I2CTransportPicoSDK transport(i2c0, 0x40);  // 7-bit address
<Chip>Full chip(transport);

int passed = 0, failed = 0;

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
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x40,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    <Chip>Full chip(transport);
    // ... checks ...
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
```

The default `GPIO21` SDA / `GPIO22` SCL on `I2C_NUM_0` works on most ESP32 boards. To use a different pin pair or move to `I2C_NUM_1`, edit the bus-config block at the top of `main.cpp`.

int main(void) {
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    // ... checks using chip.<method>() ...
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
```

The default I²C pins (`GP4`/`GP5` on `i2c0`) match pico-sdk's documented defaults — wire your device to those and the test will work without further configuration.

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

### JVM test template

One JBang script per chip. The file name is `<Chip>Test.java` (title-case chip name + `Test`). Use `INA226` as the reference.

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
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

        try (var transport = new I2CTransport(bus, addr)) {
            var chip = new <Chip>Full(transport);

            // --- checks ---
            // checkTrue("description", chip.someMethod() >= 0);

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
```

Run with: `I2C_BUS=1 I2C_ADDR=0x40 jbang jvm/tests/<category>/<chip>/<Chip>Test.java`

The JVM test only covers the Java driver. Kotlin and Groovy drivers share the same transport and are exercised by the same hardware paths; separate Kotlin/Groovy test scripts are not required.

### Go Linux test template

`go/tests/<category>/<chip>_test/main.go`:

```go
//go:build linux && !tinygo

package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/<category>"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, _ := strconv.Atoi(envOr("I2C_BUS", "1"))
	addr64, _ := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)

	tr, err := transport.NewI2CTransport(bus, uint8(addr64))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err); os.Exit(2)
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

The TinyGo test (`<chip>_test_tinygo/main.go`) follows the same structure but uses `//go:build tinygo`, opens the transport via `transport.NewI2CTransport(machine.I2C1, addr)`, and replaces `os.Exit` with `panic` (TinyGo lacks `os.Exit`). Use `INA226` as the reference implementation for both variants.

### Sigrok decoder test

A sigrok decoder test is a captured session file, not a script. Create `sigrok/tests/<chip>/` and commit one `.sr` file recorded from real hardware:

```
sigrok/tests/<chip>/<Chip>-Test.sr
```

The session must capture at least one complete write and one complete read of the chip's primary registers. Filename convention: title-case chip name, hyphen, `Test`, `.sr` extension (e.g. `INA226-Test.sr`).

There is no runner script. Verify by opening the `.sr` file in PulseView with the decoder loaded and confirming the annotations are correct.
