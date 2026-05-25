# node-red-contrib-periph-power

Node-RED nodes for power monitor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-power`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-power
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `ina219-device` | config | I²C bus and address for an INA219 |
| `ina219` | input | Read voltage, current, and power from an INA219 |
| `ina226-device` | config | I²C bus and address for an INA226 |
| `ina226` | input | Read voltage, current, and power from an INA226 |
| `ina3221-device` | config | I²C bus and address for an INA3221 |
| `periph-ina3221` | input | Read 3-channel voltage and current from an INA3221 |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
