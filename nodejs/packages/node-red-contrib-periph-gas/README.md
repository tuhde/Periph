# node-red-contrib-periph-gas

Node-RED nodes for gas sensor chips: CCS811, ENS160 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (2):** CCS811, ENS160.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-gas/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-gas`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-gas
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `ccs811-device` | config | I²C bus and address for a CCS811 |
| `ccs811` | input | Reads equivalent CO₂ (eCO₂) and total VOC (TVOC) from a ScioSense CCS811 over I²C (address 0x5A or 0x5B), one sample per message. |
| `ens160-device` | config | Bus configuration for an ENS160 |
| `ens160` | input | Reads air quality (AQI, TVOC, eCO2) from an ENS160 digital multi-gas sensor. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
