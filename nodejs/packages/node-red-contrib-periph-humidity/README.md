# node-red-contrib-periph-humidity

Node-RED nodes for humidity sensor chips: DHT11, HDC1008, HDC1080, HS30XX, HS40XX, HTS221, HTU21DF, HTU31D, SHT31, SHTC3, SI7021 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (11):** DHT11, HDC1008, HDC1080, HS30XX, HS40XX, HTS221, HTU21DF, HTU31D, SHT31, SHTC3, SI7021.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-humidity/examples).

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
| `hdc1008-device` | config | I²C bus and address for a HDC1008 |
| `hdc1008` | input | Reads temperature and relative humidity from a Texas Instruments HDC1008 over I²C (addresses 0x40–0x43) with one sequence measurement (temperature then humidity) per message. |
| `hdc1080-device` | config | I²C bus and address for a HDC1080 |
| `hdc1080` | input | Reads temperature and relative humidity from a Texas Instruments HDC1080 over I²C (address 0x40) with one sequence measurement (temperature then humidity) per message. |
| `hs30xx-device` | config | I²C bus and address for a HS30XX |
| `hs30xx` | input | Reads temperature and relative humidity from a Renesas HS3001, HS3002 or HS3003 (the HS30xx family, one protocol) over I²C (fixed address 0x44) with one measurement per message. |
| `hs40xx-device` | config | I²C bus and address for a HS40XX |
| `hs40xx` | input | Reads temperature and relative humidity from a Renesas HS4001 or HS4003 (the HS40xx family, one protocol) over I²C (fixed address 0x54) with one no-hold measurement per message. HS4002 and the HS401x variants are physically different versions of the same part and are not offered separately. |
| `hts221-device` | config | I²C bus and address for a HTS221 |
| `hts221` | input | Reads temperature and relative humidity from an STMicroelectronics HTS221 over I²C (address 0x5F) with one factory-calibrated one-shot measurement per message. |
| `htu21df-device` | config | I²C bus and address for a HTU21DF |
| `htu21df` | input | Reads temperature and relative humidity from a Measurement Specialties HTU21D-F over I²C (fixed address 0x40) with one temperature and one humidity measurement per message. The CRC of both results is verified. |
| `htu31d-device` | config | I²C bus and address for a HTU31D |
| `htu31d` | input | Reads temperature and relative humidity from a TE Connectivity HTU31D over I²C (address 0x40 or 0x41) with one CRC-checked conversion per message. |
| `sht31-device` | config | I²C bus and address for a SHT31 |
| `sht31` | input | Reads temperature and relative humidity from a Sensirion SHT31 over I²C (0x44 with ADDR low, 0x45 with ADDR high) with one single-shot measurement per message. Both CRC bytes are verified. |
| `shtc3-device` | config | I²C bus and address for a SHTC3 |
| `shtc3` | input | Reads temperature and relative humidity from a Sensirion SHTC3 over I²C (address 0x70). Each message wakes the sensor, runs one CRC-checked measurement and puts it back to sleep. |
| `si7021-device` | config | I²C bus and address for a SI7021 |
| `si7021` | input | Reads relative humidity and temperature from a Silicon Labs Si7021 over I²C (fixed address 0x40) with one relative-humidity measurement per message; the temperature comes from the same conversion. The CRC of the humidity result is verified. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
