# node-red-contrib-periph-gyroscope

Node-RED nodes for gyroscope chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-gyroscope`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-gyroscope
```

## Nodes

### `periph-l3g4200d`

Reads angular rate on the X, Y, and Z axes from an ST L3G4200D MEMS
gyroscope. Configure the device in a companion `l3g4200d-device` config
node — I²C bus number, I²C address (0x68 or 0x69), ODR (100/200/400/800 Hz),
LPF2 bandwidth code, and full-scale range (±250 / ±500 / ±2000 dps).

Output `msg.payload` is `{ x, y, z }` in rad/s.

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
