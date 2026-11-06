# node-red-contrib-periph-imu

Node-RED nodes for IMU chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-imu`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-imu
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-icm20948` | input | Reads acceleration, angular rate, die temperature and (optionally) the magnetic field from a TDK InvenSense ICM-20948 9-axis MotionTracking device over I²C. The AK09916 magnetometer is read through the chip's own I²C master, so only one I²C address is needed. VDDIO must be 1.71–1.95 V. |
| `mpu6050-device` | config | I²C bus and address for a MPU6050 |
| `mpu6050` | input | Reads 3-axis acceleration and 3-axis angular rate from an MPU6050 IMU over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
