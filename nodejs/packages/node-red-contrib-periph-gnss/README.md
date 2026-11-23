# node-red-contrib-periph-gnss

Node-RED nodes for GNSS/GPS peripheral chips: NEO6 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (1):** NEO6.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-gnss/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-gnss`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-gnss
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `neo6-device` | config | Bus configuration for a NEO6 |
| `neo6` | input | Reads NMEA position, altitude, and fix status from a u-blox NEO-6 GNSS receiver over UART, I&sup2;C (DDC), or SPI. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
