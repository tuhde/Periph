# node-red-contrib-periph-other

Node-RED nodes for miscellaneous peripheral chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-other`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-other
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `mpr121-device` | config | I²C bus and address for a MPR121 |
| `mpr121` | input | Reads the 12-electrode touch bitmask from an MPR121 capacitive touch sensor over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
