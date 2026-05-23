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

All three files are written for **MicroPython** (the primary target). The transport import is the only line that differs across the three Python targets.

### MicroPython

```python
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.<category>.<chip> import <Chip>Minimal
```

Run with `mpremote` (the `periph` library is served from the host — nothing is written to the board):

```
mpremote mount python run python/examples/<category>/<chip>/minimal.py
```

The `I2C` constructor arguments (`id`, `sda`, `scl`, `freq`) vary by board. Examples use representative pin numbers; adjust them to match your hardware.

### CircuitPython

Change the transport import and bus construction:

```python
import busio, board
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.<category>.<chip> import <Chip>Minimal

i2c = busio.I2C(board.SCL, board.SDA)    # SCL first
transport = I2CTransport(i2c, 0x40)
```

The driver and all application logic are unchanged. Copy the example to CIRCUITPY or run via raw REPL.

### Linux

Change the transport import and drop the `machine` dependency:

```python
from periph.transport.i2c_linux import I2CTransport
from periph.chips.<category>.<chip> import <Chip>Minimal

transport = I2CTransport(1, 0x40)   # bus number, device address
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

The chip driver (`cpp/src/chips/<category>/<Chip>.h` / `.cpp`) is shared across Arduino, Linux GCC, and Zephyr. Each platform has its own transport and example entry point.

### Arduino

**File layout:**
```
cpp/examples/<Chip>_Minimal/<Chip>_Minimal.ino
cpp/examples/<Chip>_Complete/<Chip>_Complete.ino
cpp/examples/<Chip>_Demo/<Chip>_Demo.ino
```

The directory name must exactly match the `.ino` filename — this is an Arduino IDE requirement.

Open in the Arduino IDE, or compile and upload with `arduino-cli`:

```
arduino-cli compile --fqbn esp32:esp32:esp32s3 cpp/examples/BMP280_Minimal
arduino-cli upload  --fqbn esp32:esp32:esp32s3 --port /dev/ttyACM0 cpp/examples/BMP280_Minimal
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
cpp/examples/<Chip>_Minimal_Zephyr/src/main.cpp
cpp/examples/<Chip>_Minimal_Zephyr/CMakeLists.txt
cpp/examples/<Chip>_Minimal_Zephyr/prj.conf
```

Each Zephyr example is a standalone application. Build and flash with `west`:

```
cd cpp/examples/BMP280_Minimal_Zephyr
west build -b <board>
west flash
```

The example uses `DT_NODELABEL(i2c0)` by default. For boards with a different I²C node label, add a board overlay:

```
cpp/examples/BMP280_Minimal_Zephyr/boards/<board>.overlay
```

Monitor serial output:

```
west espressif monitor     # ESP32-S3
minicom -D /dev/ttyACM0 -b 115200
```

### Linux GCC

The C++ driver also compiles natively on Linux. There are no standalone Linux GCC example programs — the Linux path is exercised through the test suite (`cpp/tests/`). To use the driver in a custom Linux program, include the driver header and link against `I2CTransportLinux`:

```cpp
#include "I2CTransportLinux.h"
#include "BMP280.h"

I2CTransportLinux transport(1, 0x76);   // bus 1, address 0x76
BMP280Minimal bmp(transport);
```

Compile with:

```
g++ -std=c++17 -Icpp/src/transport -Icpp/src/chips/pressure your_program.cpp \
    cpp/src/chips/pressure/BMP280.cpp cpp/src/transport/I2CTransportLinux.cpp \
    -o your_program
```

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

The transport and address are hardcoded in the file; edit `new I2CTransport(1, 0x40)` to match your hardware.

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
rust/examples/<chip>_minimal/Cargo.toml
rust/examples/<chip>_minimal/src/main.rs
rust/examples/<chip>_complete/Cargo.toml
rust/examples/<chip>_complete/src/main.rs
rust/examples/<chip>_demo/Cargo.toml
rust/examples/<chip>_demo/src/main.rs
```

Each example is its own Cargo crate, a member of the `rust/` workspace.

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

There are no Rust ESP32-S3 *example* crates — only an ESP32-S3 *test* crate per chip. Embedded Rust development uses the test crate as the entry point; see [TESTING.md](TESTING.md).

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
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT        ← or periph-kotlin / periph-groovy
```

**Prerequisites:** JBang installed, Java 22+, Pi4J native libraries present on the Raspberry Pi. On first run, JBang downloads the Maven dependencies automatically.

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

### Transport and address

The I²C bus number and device address are hardcoded in each file (`new I2CTransport(1, 0x40)`). Edit these values to match your hardware before running.

### Resource management

Each language closes the transport differently:

- **Java:** `try (var transport = new I2CTransport(1, 0x40)) { ... }` — try-with-resources
- **Kotlin:** `I2CTransport(1, 0x40).use { transport -> ... }` — `Closeable.use { }`
- **Groovy:** `try { ... } finally { transport.close() }` — explicit `finally`

All three guarantee the I²C file descriptor is closed on exit, including on exception.
