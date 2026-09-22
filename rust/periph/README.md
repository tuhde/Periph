# periph

Multi-language peripheral driver library for SPI, I2C, and other transports

- **`no_std` compatible** — runs on bare-metal targets (ESP32-S3, STM32, …) and Linux
- **Generic over [`embedded-hal`](https://crates.io/crates/embedded-hal) 1.0** — bring your own connection
- **Two-tier API** — `*Minimal` for the primary use case, `*Full` for complete chip functionality

## Install

```sh
cargo add periph
```

Or in `Cargo.toml`:

```toml
[dependencies]
periph = "1.2.0"
```

## Example

```rust
use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina226Minimal;

fn main() {
    let i2c = I2cdev::new("/dev/i2c-1").unwrap();
    // addr=0x40, 0.1 Ω shunt, 2.0 A max expected current
    let mut sensor = Ina226Minimal::new(i2c, 0x40, 0.1, 2.0).unwrap();
    println!("{:.3} W", sensor.power().unwrap());
}
```

Each chip exposes two structs:

- `*Minimal` — primary use case, works out of the box with sensible defaults
- `*Full` — complete chip functionality, extends Minimal

## Supported chips

| Chip | Category | Description |
|------|----------|-------------|
| 24AA02UID | Memory | 2 Kbit I²C EEPROM with 32-bit unique serial number. |
| AD7705 | ADC/DAC | 2-channel, 16-bit sigma-delta ADC driver (Analog Devices). |
| AD7706 | ADC/DAC | 3-channel, 16-bit sigma-delta ADC driver (Analog Devices). |
| ADE7953 | Power monitor | Single-phase multifunction metering IC (Analog Devices). |
| ADXL345 | Accelerometer | 3-axis MEMS accelerometer (Analog Devices). |
| ADXL362 | Accelerometer | 3-axis MEMS accelerometer (Analog Devices). |
| AHT21 | Environmental sensor | Temperature and humidity sensor (ASAIR). |
| APA102 | LED driver | Addressable RGB LED strip driver. |
| APDS-9930 | Light sensor | Digital ambient light and proximity sensor (Broadcom/Avago). |
| APDS9960 | Light sensor | Digital proximity, ambient light, RGB and gesture sensor (Broadcom/Avago). |
| AS5600 | Magnetometer | 12-bit programmable contactless rotary position sensor (AMS OSRAM). |
| BME280 | Environmental sensor | Combined humidity + pressure + temperature sensor (Bosch Sensortec). |
| BME680 | Environmental sensor | 4-in-1 environmental sensor: temperature, pressure, humidity, gas resistance (Bosch Sensortec). |
| BMP085 | Pressure sensor | Piezo-resistive pressure + temperature sensor (Bosch Sensortec). |
| BMP180 | Pressure sensor | Piezo-resistive pressure + temperature sensor (Bosch Sensortec). |
| BMP280 | Pressure sensor | Piezo-resistive pressure + temperature sensor (Bosch Sensortec). |
| BMP384 | Pressure sensor | High-precision barometric pressure and temperature sensor (Bosch Sensortec). |
| BMP581 | Pressure sensor | MEMS barometric pressure + temperature sensor (Bosch Sensortec). |
| DHT11 | Humidity sensor | Combined temperature and humidity sensor (ASAIR / Aosong). |
| ENS160 | Gas sensor | Digital multi-gas sensor driver. |
| HMC5883L | Magnetometer | 3-axis anisotropic magnetoresistive magnetometer (Honeywell). |
| HX710A | ADC/DAC | 24-bit ADC (Avia Semiconductor). |
| HX710B | ADC/DAC | 24-bit ADC (Avia Semiconductor). |
| HX711 | ADC/DAC | 24-bit ADC (Avia Semiconductor). |
| INA219 | Power monitor | 26 V, 12-bit current/voltage/power monitor (Texas Instruments). |
| INA226 | Power monitor | 36 V, 16-bit current/voltage/power monitor (Texas Instruments). |
| INA3221 | Power monitor | Three-channel 26 V current/voltage/power monitor (Texas Instruments). |
| L3G4200D | Gyroscope | Three-axis MEMS gyroscope (STMicroelectronics). |
| L3GD20H | Gyroscope | Three-axis MEMS gyroscope (STMicroelectronics). |
| LPS22DF | Pressure sensor | Absolute pressure and temperature sensor (STMicroelectronics). |
| LPS28DFW | Pressure sensor | Dual full-scale digital barometer (STMicroelectronics). |
| LPS33HW | Pressure sensor | Water-resistant MEMS absolute pressure sensor (STMicroelectronics). |
| MCP23017 | IO expander | 16-bit bidirectional I/O port expander (Microchip). |
| MCP2515 | Comms | Stand-alone CAN 2.0B controller (Microchip). |
| MCP4725 | ADC/DAC | Single-channel 12-bit voltage-output DAC (Microchip). |
| MCP4728 | ADC/DAC | Quad-channel 12-bit voltage-output DAC (Microchip). |
| MFRC522 | RFID/NFC | 13.56 MHz contactless reader/writer (NXP). |
| MPR121 | Other | Proximity capacitive touch sensor controller (Freescale/NXP). |
| MPU-6050 | IMU | 6-axis MotionTracking device (accelerometer + gyroscope). |
| MPU-9250 | IMU | 9-axis MotionTracking device (accelerometer + gyroscope + magnetometer). |
| MPU-9255 | IMU | 9-axis MotionTracking device (accelerometer + gyroscope + magnetometer). |
| NEO-6 | GNSS/GPS | U-blox 6 GNSS/GPS receiver. |
| PCF8574 | IO expander | 8-bit quasi-bidirectional I/O port expander (Texas Instruments). |
| PCF8575 | IO expander | 16-bit quasi-bidirectional I/O port expander (NXP). |
| PCF8576 | Display driver | 40x4 universal LCD segment driver (NXP). |
| PCF8591 | ADC/DAC | 8-bit quad ADC + DAC (NXP). |
| RDA5807M | Comms | Single-chip FM stereo radio tuner (RDA Microelectronics). |
| RFM9x | Comms | LoRa transceiver modules (HopeRF). |
| SK6812RGBW | LED driver | Addressable RGBW LED strip driver. |
| TPIC6B595 | IO expander | 8-bit power SIPO shift register driver. |
| WS2812B | LED driver | Addressable RGB LED strip driver (Worldsemi). |
| WS2814 | LED driver | Addressable RGBW LED strip driver (Super Lighting LED). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [Docs](https://docs.rs/periph)
