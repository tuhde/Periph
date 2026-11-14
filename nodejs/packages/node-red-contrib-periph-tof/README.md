# node-red-contrib-periph-tof

Node-RED nodes for time-of-flight sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-tof`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-tof
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-vl53l0x` | input | Measures distance with a VL53L0X time-of-flight laser-ranging sensor over I²C (up to ~2 m), and optionally reports distance-threshold events. |
| `periph-vl53l1x` | input | Measures distance with a VL53L1X long-distance time-of-flight laser-ranging sensor over I²C (up to 4 m), and optionally reports distance-threshold events. |
| `periph-vl6180x` | input | Measures distance (0–100 mm) and ambient light with a VL6180X proximity and ambient-light sensor over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
