# node-red-contrib-periph-other

Node-RED nodes for miscellaneous peripheral chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-other`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-other
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `mpr121-device` | config | I²C bus and address for a MPR121 |
| `mpr121` | input | Reads the 12-electrode touch bitmask from an MPR121 capacitive touch sensor over I²C. |
| `periph-pca9541` | input | Controls the 2-to-1 I²C-bus master selector PCA9541 from one of two redundant masters: take, give up and check ownership of the shared downstream bus. There is no arbitration: whichever master writes last before a STOP wins. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
