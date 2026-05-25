# periph

JavaScript drivers for peripheral chips (sensors, actuators, and other ICs) connected via I²C, SPI, or UART. Runs on Linux and any Node.js host.

## Install

```sh
npm install periph
```

## Usage

Drivers are accessed by path — require the chip module directly:

```js
const { INA226Minimal } = require('periph/src/chips/power/ina226');
const { I2CTransport }  = require('periph/src/transport/i2c');

const bus    = await I2CTransport.open(1, 0x40);  // I2C bus 1, address 0x40
const sensor = new INA226Minimal(bus);
console.log(await sensor.power());  // watts
```

Each chip exposes two classes:

- `*Minimal` — primary use case, works out of the box with sensible defaults
- `*Full` — complete chip functionality, extends Minimal

## Supported chips

| Chip | Category | Require path |
|------|----------|-------------|
| AS5600 | Magnetometer | `periph/src/chips/magnetometer/as5600` |
| BMP180 | Pressure | `periph/src/chips/pressure/bmp180` |
| BMP280 | Pressure | `periph/src/chips/pressure/bmp280` |
| INA219 | Power monitor | `periph/src/chips/power/ina219` |
| INA226 | Power monitor | `periph/src/chips/power/ina226` |
| INA3221 | Power monitor (3-ch) | `periph/src/chips/power/ina3221` |
| MCP23017 | IO expander (16-bit) | `periph/src/chips/io_expander/mcp23017` |
| MCP4725 | 12-bit DAC | `periph/src/chips/adc_dac/mcp4725` |
| PCF8574 | IO expander (8-bit) | `periph/src/chips/io_expander/pcf8574` |
| PCF8575 | IO expander (16-bit) | `periph/src/chips/io_expander/pcf8575` |
| SK6812RGBW | LED (addressable RGBW) | `periph/src/chips/led/sk6812rgbw` |
| WS2812B | LED (addressable RGB) | `periph/src/chips/led/ws2812b` |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [Node-RED nodes](https://www.npmjs.com/search?q=node-red-contrib-periph)
