# node-red-contrib-periph-gas

Node-RED nodes for gas sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-gas`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-gas
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `ens160-device` | config | Bus configuration for an ENS160 |
| `periph-ens160` | input | Reads air quality (AQI, TVOC, eCO2) from an ENS160 digital multi-gas sensor. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
