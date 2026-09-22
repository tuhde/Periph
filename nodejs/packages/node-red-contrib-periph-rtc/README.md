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
| `periph-ds3231` | input | Reads and sets the calendar clock of a DS3231 extremely accurate I²C RTC, and reports its on-chip temperature reading. Fixed address 0x68 . |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
