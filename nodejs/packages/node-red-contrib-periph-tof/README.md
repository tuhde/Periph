# node-red-contrib-periph-tof

Node-RED nodes for time-of-flight sensor chips: VL53L0X, VL53L1X, VL6180X — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (3):** VL53L0X, VL53L1X, VL6180X.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-tof/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-tof`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-tof
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `vl53l0x-device` | config | I²C bus and address for a VL53L0X |
| `vl53l0x` | input | Measures distance with a VL53L0X time-of-flight laser-ranging sensor over I²C (up to ~2 m), and optionally reports distance-threshold events. |
| `vl53l1x-device` | config | I²C bus and address for a VL53L1X |
| `vl53l1x` | input | Measures distance with a VL53L1X long-distance time-of-flight laser-ranging sensor over I²C (up to 4 m), and optionally reports distance-threshold events. |
| `vl6180x-device` | config | I²C bus and address for a VL6180X |
| `vl6180x` | input | Measures distance (0–100 mm) and ambient light with a VL6180X proximity and ambient-light sensor over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
