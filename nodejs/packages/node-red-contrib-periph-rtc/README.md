# node-red-contrib-periph-rtc

Node-RED nodes for real-time clock chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-rtc`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-rtc
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `ds3231-device` | config | Bus configuration for a DS3231 |
| `ds3231` | input | Reads and sets the calendar clock of a DS3231 extremely accurate I²C RTC, and reports its on-chip temperature reading. Fixed address 0x68 . |
| `pcf85063a-device` | config | Bus configuration for a PCF85063A |
| `pcf85063a` | input | Reads and sets the calendar clock of a PCF85063A low-power I²C RTC and reports its oscillator-stop flag. Fixed address 0x51 . |
| `pcf8523-device` | config | Bus configuration for a PCF8523 |
| `pcf8523` | input | Reads and sets the calendar clock of a PCF8523 low-power I²C RTC, and reports its battery backup status. Fixed address 0x68 . |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
