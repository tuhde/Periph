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
| `mcp23008-device` | config | I²C bus and address for a MCP23008 |
| `mcp23008` | input | Controls and reads an 8-bit MCP23008 (I²C) or MCP23S08 (SPI) I/O expander. Both chips share one register map and one node. |
| `mcp23017-device` | config | I²C bus and address for a MCP23017 |
| `mcp23017` | input | Controls and reads a 16-bit MCP23017 I/O expander over I²C. |
| `pca6408a-device` | config | I²C bus and address for a PCA6408A |
| `pca6408a` | input | Controls and reads an 8-bit PCA6408A I/O expander (I²C / SMBus, NXP). The chip has no internal pull-ups: inputs need external pull-up resistors. |
| `pca6416a-device` | config | I²C bus and address for a PCA6416A |
| `pca6416a` | input | Controls and reads a 16-bit PCA6416A I/O expander (I²C / SMBus, NXP). The chip has no internal pull-ups: inputs need external pull-up resistors. |
| `pca9536-device` | config | Bus configuration for a PCA9536 |
| `pca9536` | input | Controls and reads a 4-bit PCA9536 I/O expander (I²C / SMBus, TI). The chip has the fixed address 0x41 , no interrupt pin, and a weak (about 100 kΩ) pull-up that is always on each pin, so an open input reads 1. |
| `pca9539-device` | config | I²C bus and address for a PCA9539 |
| `pca9539` | input | Controls and reads a 16-bit PCA9539 I/O expander (I²C / SMBus, NXP). The chip has no internal pull-ups: inputs need external pull-up resistors. |
| `pcf8574-device` | config | I²C bus and address for a PCF8574 |
| `pcf8574` | input | Controls and reads an 8-bit PCF8574 I/O expander over I²C. |
| `pcf8575-device` | config | I²C bus and address for a PCF8575 |
| `pcf8575` | input | Controls and reads a 16-bit PCF8575 I/O expander over I²C. |
| `tca9554-device` | config | I²C bus and address for a TCA9554 |
| `tca9554` | input | Controls and reads an 8-bit TCA9554 I/O expander (I²C / SMBus, TI). Every input has a fixed internal 100 kΩ pull-up (always on). |
| `tpic6b595-device` | config | Bus configuration for a TPIC6B595 |
| `tpic6b595` | input | Drives a Texas Instruments TPIC6B595 8-bit power SIPO shift register (or multiple cascaded devices) through a SiPo (serial-in/parallel-out) connection. SER IN / SRCK may be either an SPI bus (hardware mode) or two plain GPIO lines (software / bit-bang mode); RCK is always a plain GPIO. Optional SRCLR and G lines add shift-register-clear and global output enable (PWM-style dimming). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
