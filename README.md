# Periph

A multi-language library of drivers for peripheral chips — sensors, actuators, and other ICs connected via SPI, I²C, SMBus, or other transports.

## Implementations

| Language | Platforms | Status |
|----------|-----------|--------|
| Python | MicroPython, CircuitPython, Linux kernel (`/dev/i2c-N` via `smbus2`) | Active |
| C++ | Arduino, Linux GCC, Zephyr RTOS | Active |
| Node.js / Node-RED | Linux, any Node.js host | Active |
| Rust | Linux (`linux-embedded-hal`), any `embedded-hal` target | Active |
| Java / Kotlin / Groovy | Linux host via `/dev/i2c-N` (FFM, no native libraries; JVM 17+, JBang examples) | Active |
| Sigrok | PulseView, sigrok-cli (protocol decoders in `sigrok/`) | Active |

## Supported transports

| Transport | Status |
|-----------|--------|
| I²C | Implemented |
| SPI | Implemented |
| SMBus | Implemented |
| NeoPixel (WS2812B / single-wire NZR) | Implemented |
| UART (+ RS-485 variant) | Implemented |
| HX711 (2-wire bit-bang) | Implemented |
| DHTxx (single-wire bit-bang) | Implemented |

## Structure

Each chip driver is implemented in two stages:

- **Minimal** — covers the primary use case with sensible defaults; works out of the box with just a transport
- **Full** — complete chip functionality, extends Minimal

Drivers are platform-agnostic — they depend only on the transport abstraction. Choose the transport for your target:


**Python**
```python
from periph.transport.i2c_micropython import I2CTransport   # machine.I2C
from periph.transport.i2c_circuitpython import I2CTransport # busio.I2C
from periph.transport.i2c_linux import I2CTransport         # /dev/i2c-N
```

**C++**
```cpp
#include "I2CTransport.h"         // Arduino Wire
#include "I2CTransportLinux.h"    // Linux /dev/i2c-N via ioctl
#include "I2CTransportZephyr.h"   // Zephyr RTOS I2C subsystem
```

**Node.js**
```js
const { I2CTransport } = require('periph/src/transport/i2c');  // /dev/i2c-N via i2c-bus
```

**Rust**
```rust
use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina226Minimal, Ina226Full};
```

**Java / Kotlin / Groovy**
```java
// Java
import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina226Minimal;
```
```kotlin
// Kotlin
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Minimal
```
```groovy
// Groovy
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Minimal
```

## Supported chips

| Chip | Category | Python | C++ | Node.js | Node-RED | Rust | JVM | Sigrok |
|------|----------|--------|-----|---------|----------|------|-----|--------|
| 24AA02UID | 2 Kbit EEPROM with unique ID | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AHT21 | Temperature/humidity | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| APDS9960 | Proximity/ALS/gesture | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AS5600 | Magnetometer | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMP180 | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BME280 | Environmental (T/P/H) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BME680 | Environmental (T/P/H/gas) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMP280 | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| DHT11 | Temperature/humidity (single-wire) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ENS160 | Gas (multi-gas AQI) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| HX711 | 24-bit ADC (load cell) | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| INA219 | Power monitor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| INA226 | Power monitor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| INA3221 | Power monitor (3-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP23017 | IO expander (16-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP4725 | 12-bit DAC | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP4728 | Quad 12-bit DAC | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MPU-6050 | IMU (6-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| NEO-6 | GNSS / GPS receiver | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8574 | IO expander (8-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8575 | IO expander (16-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8576 | LCD segment driver (40×4) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| RDA5807M | FM stereo radio tuner | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SK6812RGBW | LED (addressable RGBW) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| WS2812B | LED (addressable RGB) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

More chips are in progress — see the [open issues](../../issues) for what's being specced and implemented.

## Examples

Each chip has three examples per language:

| Tier | Purpose |
|------|---------|
| `minimal` | Simplest usage — construct, read primary values in a loop |
| `complete` | Every method in the API exercised |
| `demo` | A real-world scenario with why-comments per logical block |

## Documentation

The spec (`specs/<category>/<chip>.md`) is the reference documentation — register maps, API tables, conversion formulas. No separate docs directory; the examples serve as usage documentation.

## Node-RED

Per-category Node-RED packages are available under `nodejs/packages/node-red-contrib-periph-<category>`. To use locally, add the package directory to `nodesDir` in your Node-RED `settings.js`.

## Sigrok decoders

Protocol decoders for [sigrok](https://sigrok.org) (PulseView, sigrok-cli) are in `sigrok/<chip>/`. Each decoder annotates I²C or SPI captures with register names, bit fields, and computed values for the chip it targets.

To use a decoder, point sigrok at the `sigrok/` directory:

```sh
sigrok-cli --input-file capture.sr \
  --protocol-decoder-path sigrok/ \
  --protocol-decoder pcf8574
```

In PulseView, add the `sigrok/` path under *Preferences → Decoders* and then stack the chip decoder on top of the I²C decoder.

## Testing

Each chip has hardware tests for all platforms. Copy the relevant `testconfig.example` to `testconfig` and fill in your values, then run:

| Platform | Runner | Notes |
|----------|--------|-------|
| Arduino | `cpp/test_arduino.sh power/ina226` | Compiles, uploads, reads serial |
| Linux GCC | `cpp/test_linux.sh power/ina226` | Builds with g++, runs on host |
| MicroPython | `python/test_mp.sh power/ina226` | `mpremote` mount — nothing written to board |
| CircuitPython | `python/test_cp.sh power/ina226` | Copies via CIRCUITPY drive, runs via raw REPL |
| Linux kernel (Python) | `python/test_linux.sh power/ina226` | Runs on host via `smbus2` |
| Node.js | `nodejs/test.sh power/ina226` | Runs on host via `i2c-bus` |
| Zephyr RTOS | `cpp/test_zephyr.sh power/ina226` | Builds with west, flashes, reads serial |
| Rust (Linux) | `rust/test_linux.sh power/ina226` | Builds with cargo, runs on host |
| Rust (ESP32-S3) | `rust/test_esp32s3.sh power/ina226` | Builds with esp toolchain, flashes, reads serial |
| JVM (Pi hardware) | `jvm/test.sh power/ina226 [--lang kotlin\|groovy]` | Runs via JBang on Raspberry Pi |

All runners produce `PASS`/`FAIL` lines and a final `===DONE: N passed, N failed===` line.
`--compile-only` is supported by the Arduino, Linux GCC, and Zephyr runners.

## Architecture and workflow

See [CLAUDE.md](CLAUDE.md) for repo layout, category structure, implementation stages, and the AI workflow.

## AI-implemented

This project is implemented entirely by AI — every line of code, spec, and configuration is generated without human authoring. It serves as an experiment in AI-driven open source library development at scale.
