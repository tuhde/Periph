# node-red-contrib-periph-gyroscope

Node-RED nodes for gyroscope chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-gyroscope`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-gyroscope
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `l3g4200d-device` | config | Bus configuration for a L3G4200D |
| `periph-l3g4200d` | input | Reads angular rate on the X, Y, and Z axes from an L3G4200D MEMS gyroscope. |
| `l3gd20h-device` | config | Bus configuration for a L3GD20H |
| `periph-l3gd20h` | input | Reads angular rate on the X, Y, and Z axes from an L3GD20H (or L3GD20) MEMS gyroscope. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
