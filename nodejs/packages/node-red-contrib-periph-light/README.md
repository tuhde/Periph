# node-red-contrib-periph-light

Node-RED nodes for light sensor chips: APDS-9930, APDS9250, APDS9960, BH1750, MAX44009, SI1132 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (6):** APDS9930, APDS9250, APDS9960, BH1750, MAX44009, SI1132.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-light/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-light`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-light
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `apds9930-device` | config | I²C bus and address for an APDS9930 |
| `apds9930` | input | Reads ambient illuminance and proximity from an APDS-9930 sensor over I²C. |
| `apds9250-device` | config | Bus configuration for an APDS9250 |
| `apds9250` | input | Reads the red, green, blue and infrared channels of an APDS-9250 digital RGB, IR and ambient light sensor (I²C 0x52, 13–20 bit). |
| `apds9960-device` | config | I²C bus and address for an APDS9960 |
| `apds9960` | input | Reads ambient light and color (RGBC) from an APDS-9960 sensor over I²C. |
| `bh1750-device` | config | I²C bus and address for a BH1750 |
| `bh1750` | input | Reads the illuminance from a ROHM BH1750FVI ambient light sensor over I²C (address 0x23 or 0x5C) with one one-time measurement per message. |
| `max44009-device` | config | I²C bus and address for a MAX44009 |
| `max44009` | input | Reads the ambient illuminance of a MAX44009 ultra-low-power light sensor (I²C, 0.045 lx to 188 006.4 lx) and arms its threshold interrupt. |
| `si1132-device` | config | I²C bus and address for a SI1132 |
| `si1132` | input | Reads the UV index and the raw ambient-light counts from a Silicon Labs Si1132 over I²C (address 0x60) with one forced measurement per message. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
