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
| `periph-hx711` | input | Reads weight from an HX711 24-bit ADC connected to a load cell via two GPIO pins. |
| `mcp4725-device` | config | I²C bus and address for a MCP4725 |
| `periph-mcp4725` | output | Sets the output voltage of an MCP4725 12-bit DAC over I²C. |
| `mcp4728-device` | config | I²C bus and address for a MCP4728 |
| `periph-mcp4728` | output | Sets the output voltage of one channel (A–D) of an MCP4728 quad-channel 12-bit DAC over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
