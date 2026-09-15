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
| `bmp384-device` | config | Bus configuration for a BMP384 |
| `periph-bmp384` | input | Reads temperature, pressure, and altitude from a BMP384 barometric pressure sensor. |
| `bmp581-device` | config | Bus configuration for a BMP581 |
| `periph-bmp581` | input | Reads pressure, temperature, and altitude from a BMP581 barometric pressure sensor. |
| `lps22df-device` | config | Bus configuration for a LPS22DF |
| `periph-lps22df` | input | Reads pressure, temperature, and altitude from an LPS22DF absolute pressure sensor. |
| `lps28dfw-device` | config | Bus configuration for a LPS28DFW |
| `periph-lps28dfw` | input | Reads pressure, temperature, and altitude from an LPS28DFW dual full-scale digital barometer. |
| `lps33hw-device` | config | Bus configuration for a LPS33HW |
| `periph-lps33hw` | input | Reads pressure and temperature from an LPS33HW water-resistant MEMS absolute pressure sensor. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
