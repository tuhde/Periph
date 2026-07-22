# node-red-contrib-periph-environmental

Node-RED nodes for environmental sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-environmental`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-environmental
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `aht21-device` | config | I²C bus and address for an AHT21 |
| `aht21` | input | Reads temperature and humidity from an AHT21 environmental sensor over I²C. |
| `bme280-device` | config | Bus configuration for a BME280 |
| `periph-bme280` | input | Reads temperature, pressure, humidity, altitude, and dew point from a BME280 combined humidity + pressure + temperature sensor. |
| `bme680-device` | config | Bus configuration for a BME680 |
| `periph-bme680` | input | Reads temperature, pressure, humidity, gas resistance, altitude, and dew point from a BME680 4-in-1 environmental sensor. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
