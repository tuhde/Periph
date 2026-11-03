# node-red-contrib-periph-memory

Node-RED nodes for memory chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-memory`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-memory
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `24aa02uid-device` | config | I²C bus and address for a 24AA02UID |
| `24aa02uid` | input | Reads the factory-programmed 32-bit unique serial number from a 24AA02UID EEPROM, or reads / writes user EEPROM bytes, over I²C. |
| `mb85rc-device` | config | I²C bus and address (0x50–0x57) for an MB85RC FRAM |
| `periph-mb85rc` | input | Reads, writes or fills the 32 KiB MB85RC256V FRAM over I²C (no write delay). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
