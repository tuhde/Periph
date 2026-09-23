# node-red-contrib-periph-temperature

Node-RED nodes for temperature sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-temperature`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-temperature
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-mcp9808` | input | Reads the ambient temperature from an MCP9808 ±0.5 °C digital temperature sensor over I²C, and optionally reports its TUPPER/TLOWER/TCRIT boundary alerts. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
