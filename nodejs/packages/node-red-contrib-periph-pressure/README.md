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
| `bmp180-device` | config | Bus configuration for a BMP180 |
| `periph-bmp180` | input | Reads temperature and pressure from a BMP180 barometric pressure sensor over I²C. |
| `bmp280-device` | config | Bus configuration for a BMP280 |
| `periph-bmp280` | input | Reads temperature, pressure, and altitude from a BMP280 barometric pressure sensor. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
