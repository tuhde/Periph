# node-red-contrib-periph-io-expander

Node-RED nodes for IO expander chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-io-expander`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-io-expander
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-mcp23017` | input | Controls and reads a 16-bit MCP23017 I/O expander over I²C. |
| `periph-pcf8574` | input | Controls and reads an 8-bit PCF8574 I/O expander over I²C. |
| `periph-pcf8575` | input | Controls and reads a 16-bit PCF8575 I/O expander over I²C. |
| `periph-tpic6b595` | input | Drives a Texas Instruments TPIC6B595 8-bit power SIPO shift register (or multiple cascaded devices) through a SiPo (serial-in/parallel-out) connection. SER IN / SRCK may be either an SPI bus (hardware mode) or two plain GPIO lines (software / bit-bang mode); RCK is always a plain GPIO. Optional SRCLR and G lines add shift-register-clear and global output enable (PWM-style dimming). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
