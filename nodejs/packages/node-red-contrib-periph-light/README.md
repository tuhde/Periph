# node-red-contrib-periph-light

Node-RED nodes for light sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-light`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-light
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `apds9960-device` | config | I²C bus and address for an APDS9960 |
| `apds9960` | input | Reads ambient light and color (RGBC) from an APDS-9960 sensor over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
