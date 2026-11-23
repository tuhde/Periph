# node-red-contrib-periph-pressure

Node-RED nodes for pressure sensor chips: BMP085, BMP180, BMP280, BMP384, BMP581, LPS22DF, LPS22HB, LPS28DFW, LPS33HW, MPL115A2 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (10):** BMP085, BMP180, BMP280, BMP384, BMP581, LPS22DF, LPS22HB, LPS28DFW, LPS33HW, MPL115A2.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-pressure/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-pressure`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-pressure
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `bmp085-device` | config | Bus configuration for a BMP085 |
| `bmp085` | input | Reads temperature and pressure from a BMP085 barometric pressure sensor over I²C. |
| `bmp180-device` | config | Bus configuration for a BMP180 |
| `bmp180` | input | Reads temperature and pressure from a BMP180 barometric pressure sensor over I²C. |
| `bmp280-device` | config | Bus configuration for a BMP280 |
| `bmp280` | input | Reads temperature, pressure, and altitude from a BMP280 barometric pressure sensor. |
| `bmp384-device` | config | Bus configuration for a BMP384 |
| `bmp384` | input | Reads temperature, pressure, and altitude from a BMP384 barometric pressure sensor. |
| `bmp581-device` | config | Bus configuration for a BMP581 |
| `bmp581` | input | Reads pressure, temperature, and altitude from a BMP581 barometric pressure sensor. |
| `lps22df-device` | config | Bus configuration for a LPS22DF |
| `lps22df` | input | Reads pressure, temperature, and altitude from an LPS22DF absolute pressure sensor. |
| `lps22hb-device` | config | Bus configuration for a LPS22HB |
| `lps22hb` | input | Reads pressure and temperature from an LPS22HB MEMS nano absolute pressure sensor. |
| `lps28dfw-device` | config | Bus configuration for a LPS28DFW |
| `lps28dfw` | input | Reads pressure, temperature, and altitude from an LPS28DFW dual full-scale digital barometer. |
| `lps33hw-device` | config | Bus configuration for a LPS33HW |
| `lps33hw` | input | Reads pressure and temperature from an LPS33HW water-resistant MEMS absolute pressure sensor. |
| `mpl115a2-device` | config | Bus configuration for a MPL115A2 |
| `mpl115a2` | input | Reads the barometric pressure (50–115 kPa) and an approximate temperature from an NXP MPL115A2 over I²C (address 0x60). |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
