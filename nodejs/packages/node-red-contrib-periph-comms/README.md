# node-red-contrib-periph-comms

Node-RED nodes for communications peripheral chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-comms`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-comms
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-mcp2515` | input | Sends and receives CAN 2.0B frames with the Microchip MCP2515 stand-alone CAN controller over SPI. Supports standard (11-bit) and extended (29-bit) identifiers and 0–8 byte payloads. |
| `periph-rda5807m` | input | Controls an RDA5807M single-chip FM stereo radio tuner over I&sup2;C: tune, seek, volume, and mute, with frequency/signal status on every trigger. |
| `periph-rfm9x` | input | Sends and receives LoRa packets with the HopeRF RFM95/96/97/98W transceiver module over SPI. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
