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

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
