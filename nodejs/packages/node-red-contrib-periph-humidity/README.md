# node-red-contrib-periph-humidity

Node-RED nodes for humidity sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-humidity`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-humidity
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `dht11-device` | config | Bus configuration for a DHT11 |
| `dht11` | input | Reads temperature and humidity from a DHT11 sensor over a single GPIO data line. |
| `periph-htu21df` | input | Reads temperature and relative humidity from a Measurement Specialties HTU21D-F over I²C (fixed address 0x40) with one temperature and one humidity measurement per message. The CRC of both results is verified. |
| `periph-sht31` | input | Reads temperature and relative humidity from a Sensirion SHT31 over I²C (0x44 with ADDR low, 0x45 with ADDR high) with one single-shot measurement per message. Both CRC bytes are verified. |
| `periph-si7021` | input | Reads relative humidity and temperature from a Silicon Labs Si7021 over I²C (fixed address 0x40) with one relative-humidity measurement per message; the temperature comes from the same conversion. The CRC of the humidity result is verified. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
