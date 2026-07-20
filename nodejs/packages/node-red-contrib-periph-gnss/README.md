# node-red-contrib-periph-gnss

Node-RED nodes for GNSS/GPS peripheral chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-gnss`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-gnss
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-neo6` | input | Reads NMEA position, altitude, and fix status from a u-blox NEO-6 GNSS receiver over UART, I&sup2;C (DDC), or SPI. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
