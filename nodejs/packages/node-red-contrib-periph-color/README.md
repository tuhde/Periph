# node-red-contrib-periph-color

Node-RED nodes for color sensor chips: AS7262, ISL29125 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (2):** AS7262, ISL29125.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-color/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-color`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-color
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `as7262-device` | config | I²C bus and address for an AS7262 |
| `as7262` | input | Reads the six visible spectral channels (violet 450 nm, blue 500 nm, green 550 nm, yellow 570 nm, orange 600 nm, red 650 nm) and the chip temperature from an ams OSRAM AS7262 over I²C (fixed address 0x49) with one one-shot conversion per message. |
| `isl29125-device` | config | Bus configuration for an ISL29125 |
| `isl29125` | input | Reads the red, green and blue channels of an ISL29125 digital RGB colour light sensor with IR-blocking filter (I²C 0x44, 12/16 bit). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
