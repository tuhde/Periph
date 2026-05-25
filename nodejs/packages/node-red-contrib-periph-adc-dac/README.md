# node-red-contrib-periph-adc-dac

Node-RED nodes for ADC and DAC chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-adc-dac`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-adc-dac
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `mcp4725-device` | config | I²C bus and address for an MCP4725 |
| `periph-mcp4725` | output | Write a voltage to the MCP4725 12-bit DAC |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
