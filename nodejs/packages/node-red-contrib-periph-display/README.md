# node-red-contrib-periph-display

Node-RED nodes for display driver chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-display`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-display
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `pcf8576-device` | config | I²C bus and address for a PCF8576 |
| `periph-pcf8576` | input | Writes 7-segment digits or raw data to a PCF8576 40x4 universal LCD segment driver over I²C. The chip is write-only; any incoming message triggers a single write to display RAM. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
