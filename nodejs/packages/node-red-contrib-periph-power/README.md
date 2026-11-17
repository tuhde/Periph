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
| `periph-ade7953` | input | Reads bus voltage, load current, active power, and accumulated active energy from an ADE7953 single-phase multifunction metering IC. |
| `axp2101-device` | config | I²C bus and address for an AXP2101 |
| `axp2101` | input | Reads battery, charger and power-input state from an AXP2101 single-cell power-management IC over I²C. |
| `ina219-device` | config | I²C bus and address for an INA219 |
| `ina219` | input | Reads voltage, current, power, and shunt voltage from an INA219 power monitor over I²C. |
| `ina226-device` | config | I²C bus and address for an INA226 |
| `ina226` | input | Reads voltage, current, power, and shunt voltage from an INA226 power monitor over I²C. |
| `ina260-device` | config | I²C bus and address for an INA260 |
| `ina260` | input | Reads bus voltage, current, and power from an INA260 power monitor (integrated 2&nbsp;mΩ shunt) over I²C. |
| `ina3221-device` | config | I²C bus and address for an INA3221 |
| `periph-ina3221` | input | Reads bus voltage, shunt voltage, current, and power from an INA3221 three-channel power monitor over I²C. |
| `lc709203f-device` | config | I²C bus and address for a LC709203F |
| `lc709203f` | input | Reads cell voltage, state of charge and temperature from an LC709203F 1-cell Li-ion fuel gauge over I²C. |
| `periph-lm25066-device` | config | I²C bus and address for a LM25066 |
| `periph-lm25066` | input | Reads input and output voltage, input current, input power and MOSFET temperature from an LM25066 hot-swap controller over I²C/PMBus. |
| `periph-ltc4151-device` | config | I²C bus and address for a LTC4151 |
| `periph-ltc4151` | input | Reads supply voltage, load current and power from an LTC4151 7&ndash;80&nbsp;V high-side power monitor over I²C. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
