# Periph

A multi-language library of drivers for peripheral chips — sensors, actuators, and other ICs connected via SPI, I²C, SMBus, or other transports.

## Implementations

| Language | Platforms | Status |
|----------|-----------|--------|
| Python | MicroPython, CircuitPython, Linux kernel (`/dev/i2c-N` via `smbus2`), M5Stack UIFlow 1 + UIFlow 2 (Blockly custom blocks) | Active |
| C++ | Arduino, Linux GCC, Zephyr RTOS, ESP-IDF, Raspberry Pi Pico SDK, STM32Cube | Active |
| Node.js / Node-RED | Linux, any Node.js host | Active |
| Rust | Linux (`linux-embedded-hal`), any `embedded-hal` target | Active |
| Go | Linux (`go build`, `golang.org/x/sys/unix`) and TinyGo (Raspberry Pi Pico W) | Active |
| Java / Kotlin / Groovy | Linux host via `/dev/i2c-N` (FFM, no native libraries; JVM 22+, JBang examples) | Active |
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

- **Minimal** — covers the primary use case with sensible defaults; works out of the box with just a connection
- **Full** — complete chip functionality, extends Minimal

Drivers are platform-agnostic — they depend only on the connection abstraction. Choose the connection for your target:


**Python**
```python
from periph.connection.i2c_micropython import I2CConnection   # machine.I2C
from periph.connection.i2c_circuitpython import I2CConnection # busio.I2C
from periph.connection.i2c_linux import I2CConnection         # /dev/i2c-N
```

**C++**
```cpp
#include <Periph.h>                // Arduino Wire (I2CConnection) — umbrella header, all drivers
#include "I2CConnectionLinux.h"    // Linux /dev/i2c-N via ioctl
#include "I2CConnectionZephyr.h"   // Zephyr RTOS I2C subsystem
#include "I2CConnectionESPIDF.h"   // ESP-IDF driver-ng i2c_master_dev_handle_t
#include "I2CConnectionPicoSDK.h"  // Raspberry Pi Pico SDK hardware_i2c
```

**Node.js**
```js
const { I2CConnection } = require('periph/src/connection/i2c');  // /dev/i2c-N via i2c-bus
```

**Rust**
```rust
use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina226Minimal, Ina226Full};
```

**Go**
```go
// Linux
import (
    "github.com/tuhde/Periph/go/periph/connection"
    "github.com/tuhde/Periph/go/periph/chips/power"
)
tr, _ := connection.NewI2CConnection(1, 0x40)          // /dev/i2c-1, address 0x40
```
```go
// TinyGo (Pico W)
import (
    "machine"
    "github.com/tuhde/Periph/go/periph/connection"
    "github.com/tuhde/Periph/go/periph/chips/power"
)
tr := connection.NewI2CConnection(machine.I2C1, 0x40)  // I2C1 bus, address 0x40
```

**Java / Kotlin / Groovy**
```java
// Java
import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ina226Minimal;
```
```kotlin
// Kotlin
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ina226Minimal
```
```groovy
// Groovy
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ina226Minimal
```

## Supported chips

| 47XXX | EERAM 47L04/47C04/47L16/47C16 (4/16 Kbit SRAM with EEPROM backup) |  |  |  |  |  |  |  |  |
| AM2315 | Temperature/humidity probe (Modbus-style I²C, CRC-16, sleeps between reads) |  |  |  |  |  |  |  |  |
| AT24C02N | I²C EEPROM 2 Kbit (256 B, 8-byte page) |  |  |  |  |  |  |  |  |
| AT24C64 | I²C EEPROM 64 Kbit (8 KiB, 32-byte page) |  |  |  |  |  |  |  |  |
| BME688 | Gas, pressure, temperature and humidity sensor (forced + parallel mode) |  |  |  |  |  |  |  |  |
| BQ32000 | Real-time clock (calendar, backup supply, trickle charger, calibration) |  |  |  |  |  |  |  |  |
| BU9796 | LCD segment driver (low duty, up to 120 segments, EVR contrast) |  |  |  |  |  |  |  |  |
| Chip | Category | Python | C++ | Node.js | Node-RED | Rust | Go | JVM | Sigrok |
|------|----------|--------|-----|---------|----------|------|----|-----|--------|
| 23X1024 | Serial SRAM (1 Mbit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 24AA02UID | 2 Kbit EEPROM with unique ID | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AD5243 | Dual digital potentiometer (256-pos) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AD5248 | Dual digital rheostat (256-pos) |  |  |  |  |  |  |  |  |
| AD5243 | Dual digital potentiometer (256-pos) |  |  |  |  |  |  |  |  |
| AD5248 | Dual digital rheostat (256-pos) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AD7705 | 16-bit sigma-delta ADC (2-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AD7706 | 16-bit sigma-delta ADC (3-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADE7953 | Energy meter (2-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADS1013 | 12-bit ADC (1-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADS1014 | 12-bit ADC (1-ch, PGA) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADS1015 | 12-bit ADC (4-ch) |  |  |  |  |  |  |  |  |
| ADS1113 | 16-bit ADC (1-ch) |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADS1114 | 16-bit ADC (1-ch, PGA) |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADS1115 | 16-bit ADC (4-ch) |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADS7828 | 12-bit ADC (8-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADXL345 | Accelerometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ADXL362 | Accelerometer (3-axis, ultra-low power) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AHT10 | Temperature/humidity |  |  |  |  |  |  |  |  |
| AHT20 | Temperature/humidity |  |  |  |  |  |  |  |  |
| AHT21 | Temperature/humidity | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AM2320 | Temperature/humidity |  |  |  |  |  |  |  |  |
| APA102 | LED (addressable RGB, SPI) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| APDS-9930 | Proximity/ALS | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| APDS9960 | Proximity/ALS/gesture | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AS5600 | Magnetometer | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| AXP2101 | Power management IC (single-cell charger, 15 rails, fuel gauge) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMA150 | Accelerometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMA180 | Accelerometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BME280 | Environmental (T/P/H) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BME680 | Environmental (T/P/H/gas) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMP085 | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMP180 | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMP280 | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMP384 | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| BMP581 | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| CAT5171 | Digital potentiometer (256-pos) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| CS43L22 | Stereo audio DAC with HP/speaker amps | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| DHT11 | Temperature/humidity (single-wire) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| DRV2605 | Haptic driver (ERM/LRA) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| DRV8830 | DC motor driver (H-bridge) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| DS1881 | Digital potentiometer (dual audio taper) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| DS3502 | Digital potentiometer (7-bit, nonvolatile, high-voltage) |  |  |  |  |  |  |  |  |
| DS3231 | Real-time clock (TCXO) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ENS160 | Gas (multi-gas AQI) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ES8311 | Audio codec (mono ADC + DAC, I²C control) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| FXAS21002C | 3-axis gyroscope (±250…±2000/±4000 dps, 800 Hz, FIFO, rate threshold) |  |  |  |  |  |  |  |  |
| FXOS8700 | Accelerometer (3-axis) + magnetometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| HDC1008 | Humidity and temperature sensor (14-bit, heater, DRDYn) |  |  |  |  |  |  |  |  |
| HDC1080 | Humidity and temperature sensor (14-bit, heater, ID registers) |  |  |  |  |  |  |  |  |
| HMC5883L | Magnetometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| HS30xx | Humidity and temperature sensor (14-bit, HS3001/3002/3003) |  |  |  |  |  |  |  |  |
| HS40xx | Humidity and temperature sensor (CRC, periodic mode, alert pin) |  |  |  |  |  |  |  |  |
| HT16K33 | LED matrix driver 16x8 with key scan (dimming, blink, INT) |  |  |  |  |  |  |  |  |
| HX710A | 24-bit ADC (pressure sensor) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| HX710B | 24-bit ADC (pressure sensor, temperature) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| HX711 | 24-bit ADC (load cell) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ |
| HY3131 | DMM analog front end (24-bit ADC) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| IL0373 | E-paper display controller (104×212, B/W) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ICM-20948 | IMU (9-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| INA219 | Power monitor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| INA226 | Power monitor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| INA260 | Power monitor (integrated shunt) |  |  |  |  |  |  |  |  |
| INA3221 | Power monitor (3-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| L3G4200D | Gyroscope (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| L3GD20H (L3GD20) | Gyroscope (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| LC709203F | Battery fuel gauge (1-cell Li-ion, no sense resistor) |  |  |  |  |  |  |  |  |
| LIS3DH | Accelerometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| LM25066 | Hot-swap controller / power monitor (PMBus, VIN/VOUT/IIN/PIN/temperature) |  |  |  |  |  |  |  |  |
| LM75B | Temperature sensor with thermal watchdog (11-bit, 0.125 °C) |  |  |  |  |  |  |  |  |
| LPS22HB | Pressure sensor (MEMS nano, 24-bit, FIFO) |  |  |  |  |  |  |  |  |
| LSM303 | Accelerometer (3-axis) + magnetometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| LPS22DF | Pressure sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| LPS28DFW | Pressure sensor (water-resistant, dual FS) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| LPS33HW | Pressure sensor (water-resistant) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MAG3110 | 3-axis digital magnetometer (±1000 µT, 80 Hz, INT1) |  |  |  |  |  |  |  |  |
| MAX44009 | Ambient light sensor (ultra-low power, lux output) |  |  |  |  |  |  |  |  |
| MB85RC | FRAM (256 Kbit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP2515 | CAN controller (SPI) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP4XXX (MCP453X/454X/455X/456X/463X/464X/465X/466X) | Digital potentiometer / rheostat (7/8-bit, 1/2-ch, volatile and EEPROM) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP3422 | 18-bit ΔΣ ADC (2-ch, PGA, internal reference) |  |  |  |  |  |  |  |  |
| MCP4725 | 12-bit DAC | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP4728 | Quad 12-bit DAC | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP7940N | Battery-backed RTC (2 alarms, SRAM, trim, power-fail time-stamp) |  |  |  |  |  |  |  |  |
| MCP9808 | Temperature sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP23008 | IO expander (8-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MCP23017 | IO expander (16-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MFRC522 | RFID/NFC reader (13.56 MHz) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MMA8451Q | Accelerometer (3-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MPL115A2 | Barometer (50–115 kPa, 10-bit, calibration coefficients) |  |  |  |  |  |  |  |  |
| MPL3115A2 | Pressure/altitude + temperature sensor (20-bit, FIFO, 2 INT pins) |  |  |  |  |  |  |  |  |
| MPR121 | Capacitive touch sensor (12-ch) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MPU-6050 | IMU (6-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MPU-9250 | IMU (9-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MPU-9255 | IMU (9-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MX25L12845E | NOR flash (128 Mbit, SPI) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| NEO-6 | GNSS / GPS receiver | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCA6408A | IO expander (8-bit, low-voltage) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCA6416A | IO expander (16-bit, low-voltage) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCA9536 | IO expander (4-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCA9539 | IO expander (16-bit) |  |  |  |  |  |  |  |  |
| PCA9541 | I²C master selector (2-to-1, dual-master failover) |  |  |  |  |  |  |  |  |
| PCF2116 | LCD controller/driver (character, 24x2 / 12x4, PCF2116/PCF2114 family) |  |  |  |  |  |  |  |  |
| PCF8523 | Real-time clock | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF85063A | Real-time clock (alarm, timer) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8563 | Real-time clock/calendar (alarm, timer, CLKOUT, voltage-low flag) |  |  |  |  |  |  |  |  |
| PCF8566 | LCD segment driver (24 segments, static to 1:4 mux, write-only) |  |  |  |  |  |  |  |  |
| PCF8574 | IO expander (8-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8575 | IO expander (16-bit) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8576 | LCD segment driver (40×4) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8577C | LCD driver (32 segments direct / 64 duplex, write-only) |  |  |  |  |  |  |  |  |
| PCF8578 | LCD dot-matrix row/column driver | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8579 | LCD dot-matrix column driver |  |  |  |  |  |  |  |  |
| PCF8578 | LCD dot-matrix row/column driver |  |  |  |  |  |  |  |  |
| PCF8579 | LCD dot-matrix column driver | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PCF8591 | 8-bit ADC (4-ch) + DAC | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| QMI8658A | IMU (6-axis) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| RDA5807M | FM stereo radio tuner | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| RFM9x (RFM95/96/97/98W) | LoRa transceiver | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SAA1064 | 4-digit LED driver (7-segment, multiplexed, 21 mA sinks) |  |  |  |  |  |  |  |  |
| SD card (SPI mode) | SD / SDHC / microSD block storage | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SH1106 | OLED display (132×64) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SHTC3 | Humidity and temperature sensor (±2 %RH, ±0.2 °C, sleep mode) |  |  |  |  |  |  |  |  |
| SSD1305 | OLED display (132×64) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SSD1306 | OLED display (128×64) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SK6812RGBW | LED (addressable RGBW) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SSD1331 | OLED display (96×64, RGB) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| SSD1683 | E-paper display controller (400×300, B/W/R) |  |  |  |  |  |  |  |  |
| ST7036 | LCD character display controller (16×2, DOG series) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TMC2130 | Stepper motor driver (Step/Dir, SPI, StallGuard2) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TCA9554 | IO expander (8-bit, internal pull-ups) |  |  |  |  |  |  |  |  |
| TMC2208 | Stepper motor driver (Step/Dir, UART; TMC2202/2208/2224) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TMC2209 | Stepper motor driver (Step/Dir, UART, StallGuard4) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TMC5160 | Stepper motor controller/driver (ramp generator, SPI/UART, StallGuard2) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TMP007 | IR thermopile non-contact temperature sensor with math engine (object + die) |  |  |  |  |  |  |  |  |
| TMP117 | Temperature sensor (high-accuracy) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TPIC6B595 | Power shift register (8-bit, open-drain) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TSL2561 | Ambient light sensor (two photodiodes, lux, interrupt) |  |  |  |  |  |  |  |  |
| UC1611s | LCD graphic display controller (240×128, B/W, EA DOGXL240-7) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| UC8159 | E-paper display controller (600×448, 7-colour) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| UC8276C | E-paper display controller (400×300, B/W/R) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VEML7700 | Ambient light sensor (16-bit, 0.0042 lx/step, white channel) |  |  |  |  |  |  |  |  |
| VL53L0X | Time-of-flight distance sensor | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| VL53L1X | Time-of-flight distance sensor (long range) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| WS2812B | LED (addressable RGB) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| WS2814 | LED (addressable RGBW) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

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

## UIFlow

Blockly custom blocks wrap 27 of the MicroPython chip drivers for M5Stack's UIFlow app — see
`python/uiflow1/<category>/<chip>/`. Each chip's `<chip>.m5b` is committed and ready to import via
**Extension → Import**; no local tooling required. Details, the full chip list, and how to regenerate
after an edit: [python/uiflow1/README.md](python/uiflow1/README.md).

This targets **UIFlow 1**'s `.m5b` format. UIFlow 1 and the current
[UIFlow 2](https://uiflow2.m5stack.com/) web IDE don't interoperate — a `.m5b` doesn't import into UIFlow 2
and vice versa — so every chip also needs a native UIFlow 2 `.m5b2`, hand-built in the UIFlow 2 Block
Designer (no generator exists for this format). Every chip has a `.m5b2`, but only AHT21 has
actually been round-tripped through the real Block Designer — the rest extend that verified template
to new parameter combinations and are unverified until checked; see
[python/uiflow2/README.md](python/uiflow2/README.md) for the chip list, verification status, and
[python/uiflow2/UIFLOW2_BLOCKS.md](python/uiflow2/UIFLOW2_BLOCKS.md) for the format and workflow.

## Node-RED

Per-category Node-RED packages are available under `nodejs/packages/node-red-contrib-periph-<category>`. To use locally, add the package directory to `nodesDir` in your Node-RED `settings.js`.

## Zephyr module

The C++ implementation doubles as a Zephyr module (`cpp/zephyr/module.yml` + `cpp/CMakeLists.txt`), exposing every chip driver as a `periph` CMake library. It isn't discovered via a `west.yml` manifest entry — Periph is a multi-language monorepo, so west's normal project-root scan won't find `cpp/zephyr/module.yml`. Instead, point `ZEPHYR_EXTRA_MODULES` at the `cpp/` directory of a checkout:

```cmake
list(APPEND ZEPHYR_EXTRA_MODULES /path/to/Periph/cpp)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(my_app)

target_sources(app PRIVATE src/main.cpp)
target_link_libraries(app PRIVATE periph)
```

Then include chip and connection headers by bare filename, same as every example under `cpp/examples/zephyr/`:

```cpp
#include "I2CConnectionZephyr.h"
#include "INA226.h"
```

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
| ESP-IDF | `cpp/test_espidf.sh power/ina226` | Builds with idf.py, flashes, reads serial |
| Raspberry Pi Pico SDK | `cpp/test_picosdk.sh power/ina226` | Builds with CMake, flashes UF2, reads serial |
| STM32Cube (NUCLEO-F411RE) | `cpp/test_stm32cube.sh accelerometer/adxl345` | Builds with CMake, flashes via `st-flash`, reads ST-LINK serial |
| Rust (Linux) | `rust/test_linux.sh power/ina226` | Builds with cargo, runs on host |
| Rust (ESP32-S3) | `rust/test_esp32s3.sh power/ina226` | Builds with esp toolchain, flashes, reads serial |
| Go (Linux) | `go/test_linux.sh power/ina226` | Builds with go, runs on host |
| Go (TinyGo / Pico W) | `go/test_tinygo.sh power/ina226` | Builds with tinygo, flashes to Pico W, reads serial |
| JVM (Linux) | `jvm/test.sh power/ina226 [--lang kotlin\|groovy]` | Runs via JBang on Linux host |

All runners produce `PASS`/`FAIL` lines and a final `===DONE: N passed, N failed===` line.
`--compile-only` is supported by the Arduino, Linux GCC, Zephyr, ESP-IDF, Pico SDK, and STM32Cube runners.

## Architecture and workflow

See [CLAUDE.md](CLAUDE.md) for repo layout, category structure, implementation stages, and the AI workflow.

## AI-implemented

This project is implemented entirely by AI — every line of code, spec, and configuration is generated without human authoring. It serves as an experiment in AI-driven open source library development at scale.
