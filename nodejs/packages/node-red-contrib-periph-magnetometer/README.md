# node-red-contrib-periph-magnetometer

Node-RED nodes for magnetometer chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-magnetometer`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-magnetometer
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `as5600-device` | config | I²C bus and address for an AS5600 |
| `periph-as5600` | input | Reads the absolute angle, raw count, and magnet detection status from an AS5600 contactless rotary position sensor over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
