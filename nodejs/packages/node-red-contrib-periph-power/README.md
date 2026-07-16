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
| `ina219` | input | Reads voltage, current, power, and shunt voltage from an INA219 power monitor over I²C. |
| `ina226-device` | config | I²C bus and address for an INA226 |
| `ina226` | input | Reads voltage, current, power, and shunt voltage from an INA226 power monitor over I²C. |
| `ina3221-device` | config | I²C bus and address for an INA3221 |
| `periph-ina3221` | input | Reads bus voltage, shunt voltage, current, and power from an INA3221 three-channel power monitor over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
