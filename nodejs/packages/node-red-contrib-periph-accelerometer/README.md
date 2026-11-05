# node-red-contrib-periph-accelerometer

Node-RED nodes for accelerometer chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-accelerometer`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-accelerometer
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `adxl345-device` | config | I²C bus and address for an ADXL345 |
| `adxl345` | input | Reads 3-axis acceleration from an ADXL345 3-axis accelerometer over I²C. |
| `adxl362-device` | config | Bus configuration for an ADXL362 |
| `adxl362` | input | Reads 3-axis acceleration from an ADXL362 3-axis ultralow-power accelerometer over SPI. |
| `bma150-device` | config | I²C bus and address for a BMA150 |
| `bma150` | input | Reads 3-axis acceleration and on-chip temperature from a BMA150 3-axis accelerometer over I²C. |
| `bma180-device` | config | I²C bus and address for a BMA180 |
| `bma180` | input | Reads 3-axis acceleration and on-chip temperature from a BMA180 3-axis accelerometer over I²C. |
| `fxos8700-device` | config | I²C bus and address for a FXOS8700 |
| `periph-fxos8700` | input | Reads 3-axis acceleration, 3-axis magnetic flux density, the tilt-compensated compass heading and the die temperature from an NXP FXOS8700CQ 6-axis e-compass over I²C. The chip runs in hybrid mode, so both sensors are read with one 12-byte burst. |
| `lis3dh-device` | config | I²C bus and address for a LIS3DH |
| `periph-lis3dh` | input | Reads 3-axis acceleration and the relative on-chip temperature from a LIS3DH 3-axis accelerometer over I²C. |
| `lsm303-device` | config | Bus configuration for a LSM303 |
| `lsm303` | input | Reads 3-axis acceleration, 3-axis magnetic flux density and the compass heading from an LSM303 3-axis accelerometer + magnetometer e-compass over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
