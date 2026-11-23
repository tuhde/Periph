# node-red-contrib-periph-rfid

Node-RED nodes for RFID and NFC peripheral chips: MFRC522 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (1):** MFRC522.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-rfid/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-rfid`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-rfid
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `mfrc522-device` | config | Bus configuration for a MFRC522 |
| `mfrc522` | input | Detects ISO/IEC 14443 Type A cards in the field of an NXP MFRC522 13.56 MHz RFID/NFC reader and reads their UID. The node polls the chip on the configured interval and only emits a message when the card state changes (card inserted, removed, or a different card presented). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
