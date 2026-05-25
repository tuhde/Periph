# periph

Peripheral chip drivers for embedded systems — sensors, actuators, and other ICs connected via I²C, SPI, or UART.

- **`no_std` compatible** — runs on bare-metal targets (ESP32-S3, STM32, …) and Linux
- **Generic over [`embedded-hal`](https://crates.io/crates/embedded-hal) 1.0** — bring your own transport
- **Two-tier API** — `*Minimal` for the primary use case, `*Full` for complete chip functionality

## Install

```sh
cargo add periph
```

Or in `Cargo.toml`:

```toml
[dependencies]
periph = "0.1.0"
```

## Example

```rust
use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina226Minimal;

fn main() {
    let i2c = I2cdev::new("/dev/i2c-1").unwrap();
    let mut sensor = Ina226Minimal::new(i2c, 0x40);
    println!("{:.3} W", sensor.power().unwrap());
}
```

## Supported chips

| Chip | Category |
|------|----------|
| AS5600 | Magnetometer |
| BMP180 | Pressure sensor |
| BMP280 | Pressure sensor |
| INA219 | Power monitor |
| INA226 | Power monitor |
| INA3221 | Power monitor (3-ch) |
| MCP23017 | IO expander (16-bit) |
| MCP4725 | 12-bit DAC |
| PCF8574 | IO expander (8-bit) |
| PCF8575 | IO expander (16-bit) |
| SK6812RGBW | LED (addressable RGBW) |
| WS2812B | LED (addressable RGB) |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [Docs](https://docs.rs/periph)
