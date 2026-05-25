# node-red-contrib-periph-pressure

Node-RED nodes for pressure sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-pressure`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-pressure
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `bmp180-device` | config | I²C bus for a BMP180 |
| `periph-bmp180` | input | Read temperature and pressure from a BMP180 |
| `bmp280-device` | config | I²C bus for a BMP280 |
| `periph-bmp280` | input | Read temperature and pressure from a BMP280 |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
