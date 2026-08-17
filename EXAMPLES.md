# EXAMPLES.md

This file describes the examples structure and explains how to run them for each language and platform.

Every chip has three examples per language, named by tier: **minimal**, **complete**, and **demo**. The tiers are additive — each one builds on the one below it in both code and commenting style.

## Tiers

| Tier | File | Class | Content |
|------|------|-------|---------|
| Minimal | `minimal` / `Minimal` | `*Minimal` | Construct the driver with defaults, read the primary value(s) in a loop. The smallest possible working program. |
| Complete | `complete` / `Complete` | `*Full` | Every public method called once. Configuration, alerts, low-power modes, IDs — nothing is skipped. |
| Demo | `demo` / `Demo` | `*Full` | A real-world scenario from the spec's Demo section. Illustrates a specific use case end-to-end. |

### Comment system

The tiers use an additive comment system. Each tier includes everything from the tier below it.

**Tier-1 — signature comment** (all three tiers). Trailing comment on every call:

```python
t = sensor.temperature()   # Read temperature, () → float °C
sensor.configure(osrs=3)   # Set oversampling, (osrs=1–5) → None
```

**Tier-2 — what-it-does line** (complete adds). One extra line immediately below each call:

```python
t = sensor.temperature()   # Read temperature, () → float °C
                           # applies Bosch compensation formula to raw ADC
```

**Tier-3 — context block** (demo adds). Multi-line block at each logical section boundary; the per-call what-it-does line (Tier-2) is dropped:

```python
# --- Configure for indoor navigation ---
# ×16 oversampling and IIR filter coefficient 16 suppress pressure spikes
# caused by door slams, giving stable altitude readings at 1 Hz.
sensor.configure(osrs_t=5, osrs_p=5, mode=MODE_NORMAL, filter=FILTER_16)  # Configure all params, (...) → None
```

The comment character changes per language (`#` Python, `//` C++/JS/Rust/Java/Kotlin, `//` Groovy), but the content format is the same.

---

## Python

**File layout:**
```
python/examples/<category>/<chip>/minimal.py
python/examples/<category>/<chip>/complete.py
python/examples/<category>/<chip>/demo.py
```

All three files are written for **MicroPython** (the primary target). The connection import is the only line that differs across the three Python targets.

### MicroPython

```python
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.<category>.<chip> import <Chip>Minimal
```

Run with `mpremote` (the `periph` library is served from the host — nothing is written to the board):

```
mpremote mount python run python/examples/<category>/<chip>/minimal.py
```

The `I2C` constructor arguments (`id`, `sda`, `scl`, `freq`) vary by board. Examples use representative pin numbers; adjust them to match your hardware.

### CircuitPython

Change the connection import and bus construction:

```python
import busio, board
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.<category>.<chip> import <Chip>Minimal

i2c = busio.I2C(board.SCL, board.SDA)    # SCL first
connection = I2CConnection(i2c, 0x40)
```

The driver and all application logic are unchanged. Copy the example to CIRCUITPY or run via raw REPL.

### Linux

Change the connection import and drop the `machine` dependency:

```python
from periph.connection.i2c_linux import I2CConnection
from periph.chips.<category>.<chip> import <Chip>Minimal

connection = I2CConnection(1, 0x40)   # bus number, device address
```

Run directly:

```
python3 python/examples/<category>/<chip>/minimal.py
```

Or with a non-default bus/address:

```
I2C_BUS=1 I2C_ADDR=0x40 python3 python/examples/<category>/<chip>/minimal.py
```

(The Linux examples themselves use hardcoded defaults; set the variables in the file or pass them via the environment after editing.)

---

## C++

The chip driver (`cpp/src/chips/<category>/<Chip>.h` / `.cpp`) is shared across all C++ platforms. Each platform has its own connection header and example entry point.

### Linux GCC

**File layout:**
```
cpp/examples/linux/<category>/<Chip>/minimal/main.cpp
cpp/examples/linux/<category>/<Chip>/complete/main.cpp
cpp/examples/linux/<category>/<Chip>/demo/main.cpp
```

Each example is a single standalone `.cpp` file. Compile and run directly:

```
g++ -std=c++17 \
    -Icpp/src/connection -Icpp/src/chips/pressure \
    cpp/examples/linux/pressure/BMP280/minimal/main.cpp \
    cpp/src/chips/pressure/BMP280.cpp \
    cpp/src/connection/I2CConnectionLinux.cpp \
    -o bmp280_minimal
./bmp280_minimal
```

Override the default bus or address with environment variables:

```
I2C_BUS=0 I2C_ADDR=0x76 ./bmp280_minimal
```

Link the connection source that matches the chip:

| Chip family | Connection source | Extra flags |
|-------------|-----------------|-------------|
| I²C (most chips) | `I2CConnectionLinux.cpp` | — |
| SPI (`MFRC522`) | `SPIConnectionLinux.cpp` | — |
| NeoPixel (`WS2812B`, `SK6812RGBW`) | `NeoPixelConnectionLinux.cpp` | — |
| GPIO bit-bang (`HX711`, `DHT11`) | `HX711ConnectionLinux.cpp` / `DHTxxConnectionLinux.cpp` | `-lgpiod` |
| UART (`NEO6`) | `UARTConnectionLinux.cpp` | — |

The test suite (`cpp/tests/`) provides the same chip coverage with pass/fail assertions; run them via `cpp/test_linux.sh` — see [TESTING.md](TESTING.md).

### Arduino

**File layout:**
```
cpp/examples/arduino/<category>/<Chip>/minimal/minimal.ino
cpp/examples/arduino/<category>/<Chip>/complete/complete.ino
cpp/examples/arduino/<category>/<Chip>/demo/demo.ino
```

The directory name must exactly match the `.ino` filename — this is an Arduino IDE requirement.

Open in the Arduino IDE, or compile and upload with `arduino-cli`:

```
arduino-cli compile --fqbn esp32:esp32:esp32s3 cpp/examples/arduino/pressure/BMP280/minimal/minimal.ino
arduino-cli upload  --fqbn esp32:esp32:esp32s3 --port /dev/ttyACM0 cpp/examples/arduino/pressure/BMP280/minimal/minimal.ino
```

The library path is passed via `arduino-cli`'s `--library` flag or by symlinking `cpp/src` into the Arduino libraries directory.

Serial output appears at 115200 baud. Open the serial monitor with:

```
arduino-cli monitor --port /dev/ttyACM0 --config baudrate=115200
```

Pin numbers (`Wire.begin(SDA, SCL)`) are hardcoded in the sketch; edit them to match your board.

### Zephyr RTOS

**File layout:**
```
cpp/examples/zephyr/<category>/<Chip>/minimal/main.cpp
cpp/examples/zephyr/<category>/<Chip>/minimal/CMakeLists.txt
cpp/examples/zephyr/<category>/<Chip>/minimal/prj.conf
```

Each Zephyr example is a standalone application. Build and flash with `west`:

```
cd cpp/examples/zephyr/pressure/BMP280/minimal
west build -b <board>
west flash
```

The example uses `DT_NODELABEL(i2c0)` by default. For boards with a different I²C node label, add a board overlay:

```
cpp/examples/zephyr/pressure/BMP280/minimal/boards/<board>.overlay
```

Monitor serial output:

```
west espressif monitor     # ESP32-S3
minicom -D /dev/ttyACM0 -b 115200
```

### ESP-IDF (ESP32)

**File layout:**
```
cpp/examples/espidf/<category>/<Chip>/minimal/CMakeLists.txt
cpp/examples/espidf/<category>/<Chip>/minimal/main/CMakeLists.txt
cpp/examples/espidf/<category>/<Chip>/minimal/main/main.cpp
cpp/examples/espidf/<category>/<Chip>/minimal/sdkconfig.defaults
```

Each ESP-IDF example is a standalone application. Build and flash with `idf.py`:

```
cd cpp/examples/espidf/pressure/BMP280/minimal
idf.py build
idf.py -p /dev/ttyUSB0 flash
```

The default `sdkconfig.defaults` targets `esp32`. For other ESP32 variants (esp32s3, esp32c3, etc.) override the target:

```
idf.py set-target esp32s3
idf.py build
```

Monitor serial output:

```
idf.py -p /dev/ttyUSB0 monitor
```

Pin numbers (`I2C_MASTER_SDA_IO`, `I2C_MASTER_SCL_IO`) are defined as constants at the top of `main/main.cpp`; edit them to match your board.

### Pico SDK (Raspberry Pi Pico)

**File layout:**
```
cpp/examples/picosdk/<category>/<Chip>/minimal/CMakeLists.txt
cpp/examples/picosdk/<category>/<Chip>/minimal/src/main.cpp
```

Each Pico SDK example is a standalone CMake project. Build with CMake:

```
cd cpp/examples/picosdk/pressure/BMP280/minimal
mkdir build && cd build
cmake .. -DPICO_SDK_PATH=/path/to/pico-sdk
make -j4
```

Flash the resulting `.uf2` by holding BOOTSEL while plugging in the Pico, then copying the file to the `RPI-RP2` drive:

```
cp build/bmp280_minimal_picosdk.uf2 /media/$USER/RPI-RP2/
```

Monitor serial output (USB CDC):

```
minicom -D /dev/ttyACM0 -b 115200
```

Pin numbers (I²C SDA/SCL, SPI MOSI/MISO/SCK/CS) are defined as constants at the top of `src/main.cpp`; edit them to match your wiring.

---

## Node.js

**File layout:**
```
nodejs/packages/periph/examples/<category>/<chip>/minimal.js
nodejs/packages/periph/examples/<category>/<chip>/complete.js
nodejs/packages/periph/examples/<category>/<chip>/demo.js
```

Install dependencies first (once):

```
cd nodejs && npm install
```

Run an example from the repo root:

```
node nodejs/packages/periph/examples/<category>/<chip>/minimal.js
```

Or from inside the package directory (where relative `require` paths are resolved):

```
cd nodejs/packages/periph
node examples/<category>/<chip>/minimal.js
```

The connection and address are hardcoded in the file; edit `new I2CConnection(1, 0x40)` to match your hardware.

---

## Node-RED

Each chip has one Node-RED example — a `demo.json` flow:

```
nodejs/packages/node-red-contrib-periph-<category>/examples/<chip>/demo.json
```

To import a flow into Node-RED:

1. Open Node-RED in a browser (typically `http://localhost:1880`).
2. Click the hamburger menu → **Import**.
3. Paste or upload the `demo.json` file.
4. Click **Import**, then **Deploy**.

Before deploying, open the chip's config node (double-click it on the canvas) and set the correct I²C bus number and device address for your hardware.

The flow's tab `info` field describes the scenario, what to observe, and what to adjust — this is the Node-RED equivalent of the Tier-3 context block.

---

## Rust

**File layout:**
```
rust/examples/linux/<category>/<chip>/minimal/Cargo.toml
rust/examples/linux/<category>/<chip>/minimal/src/main.rs
rust/examples/linux/<category>/<chip>/complete/Cargo.toml
rust/examples/linux/<category>/<chip>/complete/src/main.rs
rust/examples/linux/<category>/<chip>/demo/Cargo.toml
rust/examples/linux/<category>/<chip>/demo/src/main.rs

rust/examples/embedded/esp32s3/<category>/<chip>/minimal/Cargo.toml
rust/examples/embedded/esp32s3/<category>/<chip>/minimal/src/main.rs
rust/examples/embedded/esp32s3/<category>/<chip>/minimal/rust-toolchain.toml
rust/examples/embedded/esp32s3/<category>/<chip>/minimal/.cargo/config.toml
(complete/, demo/ follow the same layout)
```

Each Linux example is its own Cargo crate, a member of the `rust/` workspace (`examples/linux/*/*/*` glob). Each ESP32-S3 example is a standalone crate excluded from the workspace — it needs the `esp` Rust toolchain, which is incompatible with the workspace's standard build.

Run on Linux (Raspberry Pi or any Linux host with `/dev/i2c-N`):

```
cargo run -p bmp280_minimal
```

Override the I²C bus or address:

```
I2C_BUS=0 I2C_ADDR=0x76 cargo run -p bmp280_minimal
```

Both variables default to sensible values (`I2C_BUS=1`, the chip's primary address) if not set.

Build without running:

```
cargo build -p bmp280_minimal
cargo build -p bmp280_minimal --release
```

Build an ESP32-S3 example (requires `espup` — see [TESTING.md](TESTING.md) for toolchain setup):

```
cd rust/examples/embedded/esp32s3/pressure/bmp280/minimal
cargo build --release
espflash flash --monitor target/xtensa-esp32s3-none-elf/release/bmp280_minimal_esp32s3
```

---

## JVM — Java, Kotlin, Groovy

**File layout:**
```
jvm/examples/java/<category>/<chip>/Minimal.java
jvm/examples/java/<category>/<chip>/Complete.java
jvm/examples/java/<category>/<chip>/Demo.java

jvm/examples/kotlin/<category>/<chip>/Minimal.kt
jvm/examples/kotlin/<category>/<chip>/Complete.kt
jvm/examples/kotlin/<category>/<chip>/Demo.kt

jvm/examples/groovy/<category>/<chip>/Minimal.groovy
jvm/examples/groovy/<category>/<chip>/Complete.groovy
jvm/examples/groovy/<category>/<chip>/Demo.groovy
```

All JVM examples are self-contained JBang scripts. The shebang line makes them directly executable:

```
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT        ← or periph-kotlin / periph-groovy
```

**Prerequisites:** JBang installed, Java 22+. On first run, JBang downloads the Maven dependencies automatically.

### Running

Java:
```
jbang jvm/examples/java/<category>/<chip>/Minimal.java
```

Kotlin:
```
jbang jvm/examples/kotlin/<category>/<chip>/Minimal.kt
```

Groovy:
```
jbang jvm/examples/groovy/<category>/<chip>/Minimal.groovy
```

Or use the shebang directly (after `chmod +x`):
```
./jvm/examples/java/<category>/<chip>/Minimal.java
```

### Connection and address

The I²C bus number and device address are hardcoded in each file (`new I2CConnection(1, 0x40)`). Edit these values to match your hardware before running.

### Resource management

Each language closes the connection differently:

- **Java:** `try (var connection = new I2CConnection(1, 0x40)) { ... }` — try-with-resources
- **Kotlin:** `I2CConnection(1, 0x40).use { connection -> ... }` — `Closeable.use { }`
- **Groovy:** `try { ... } finally { connection.close() }` — explicit `finally`

All three guarantee the I²C file descriptor is closed on exit, including on exception.

---

## Go

**File layout:**
```
go/examples/linux/<category>/<chip>/minimal/minimal.go
go/examples/linux/<category>/<chip>/complete/complete.go
go/examples/linux/<category>/<chip>/demo/demo.go

go/examples/tinygo/<category>/<chip>/minimal/minimal.go
go/examples/tinygo/<category>/<chip>/complete/complete.go
go/examples/tinygo/<category>/<chip>/demo/demo.go
```

Each example is its own `main` package (Go requires one per directory). Linux examples carry the build tag `//go:build linux && !tinygo`; TinyGo examples carry `//go:build tinygo`.

**Prerequisites (Linux):** Go ≥ 1.24, `/dev/i2c-N` kernel driver (`modprobe i2c-dev`).

**Prerequisites (TinyGo):** TinyGo ≥ 0.41, `tinygo` on PATH, Raspberry Pi Pico W.

### Linux

Run directly from the repo root:

```
go run ./go/examples/linux/<category>/<chip>/minimal
```

Override the default I²C bus or address:

```
I2C_BUS=0 I2C_ADDR=0x40 go run ./go/examples/linux/<category>/<chip>/minimal
```

Build a binary:

```
go build -o minimal ./go/examples/linux/<category>/<chip>/minimal
```

### TinyGo (Pico W)

Build a UF2 image and flash it (Pico W must be in BOOTSEL mode):

```
tinygo build -target=pico-w -o out.uf2 ./go/examples/tinygo/<category>/<chip>/minimal
cp out.uf2 /media/$USER/RPI-RP2/
```

Pin assignments (SDA = GP4, SCL = GP5 on I2C1) are hardcoded in each file. Edit `machine.GP4` / `machine.GP5` to match your wiring.

Serial output appears at 115200 baud:

```
minicom -D /dev/ttyACM0 -b 115200
```

### Connection and address

**Linux:** The bus number and address are read from `I2C_BUS` / `I2C_ADDR` environment variables, with sensible chip-specific defaults baked in. `I2C_ADDR` accepts both decimal and `0x`-prefixed hex.

**TinyGo:** Bus and address are hardcoded (`machine.I2C1`, `0x40`). Edit them in the file before building.
