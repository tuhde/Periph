# node-red-contrib-periph-accelerometer

Node-RED nodes for accelerometer chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-accelerometer`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-accelerometer
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `adxl345-device` | config | I²C bus and address for an ADXL345 |
| `adxl345` | input | Reads 3-axis acceleration from an ADXL345 3-axis accelerometer over I²C. |
| `adxl362-device` | config | Bus configuration for an ADXL362 |
| `adxl362` | input | Reads 3-axis acceleration from an ADXL362 3-axis ultralow-power accelerometer over SPI. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
