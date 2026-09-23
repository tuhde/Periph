# node-red-contrib-periph-motor

Node-RED nodes for motor driver chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-motor`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-motor
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `periph-drv8830` | input | Drives a brushed DC motor through a DRV8830 low-voltage H-bridge over I²C, and reports its fault status. The chip regulates the commanded voltage across the motor, so speed stays constant as the supply sags. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
