# node-red-contrib-periph-magnetometer

Node-RED nodes for magnetometer chips: AS5600, HMC5883L — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (2):** AS5600, HMC5883L.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-magnetometer/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-magnetometer`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-magnetometer
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `as5600-device` | config | I²C bus and address for an AS5600 |
| `as5600` | input | Reads the absolute angle, raw count, and magnet detection status from an AS5600 contactless rotary position sensor over I²C. |
| `hmc5883l-device` | config | I²C bus and address for a HMC5883L |
| `hmc5883l` | input | Reads the magnetic field on three axes from an HMC5883L 3-axis magnetometer over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
