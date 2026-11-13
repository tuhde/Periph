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
| `apds9930-device` | config | I²C bus and address for an APDS9930 |
| `apds9930` | input | Reads ambient illuminance and proximity from an APDS-9930 sensor over I²C. |
| `apds9960-device` | config | I²C bus and address for an APDS9960 |
| `apds9960` | input | Reads ambient light and color (RGBC) from an APDS-9960 sensor over I²C. |
| `periph-max44009` | input | Reads the ambient illuminance of a MAX44009 ultra-low-power light sensor (I²C, 0.045 lx to 188 006.4 lx) and arms its threshold interrupt. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
