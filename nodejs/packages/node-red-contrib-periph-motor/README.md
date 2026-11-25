# node-red-contrib-periph-motor

Node-RED nodes for motor driver chips: DRV2605, DRV8830, TMC2130, TMC2208, TMC2209, TMC5160 — part of the [Periph](https://github.com/tuhde/Periph) library.

**Supported chips (6):** DRV2605, DRV8830, TMC2130, TMC2208, TMC2209, TMC5160.

Each chip has a sensor/actuator node plus a shared `-device` config node that holds the bus settings. Importable example flows are in [`examples/`](https://github.com/tuhde/Periph/tree/main/nodejs/packages/node-red-contrib-periph-motor/examples).

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-motor`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-motor
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `drv2605-device` | config | Bus configuration for a DRV2605 |
| `drv2605` | input | Plays haptic effects on an ERM or LRA vibration motor through a DRV2605 haptic driver over I²C (fixed address 0x5A). The EN pin must be high. |
| `drv8830-device` | config | I²C bus and address for a DRV8830 |
| `drv8830` | input | Drives a brushed DC motor through a DRV8830 low-voltage H-bridge over I²C, and reports its fault status. The chip regulates the commanded voltage across the motor, so speed stays constant as the supply sags. |
| `tmc2130-device` | config | Bus configuration for a TMC2130 |
| `tmc2130` | input | Drives a two-phase bipolar stepper motor through a Trinamic TMC2130 over SPI (Mode 3). The chip has no internal step generator, so the node pulses the STEP and DIR GPIOs; moves are blocking. |
| `tmc2208-device` | config | Bus configuration for a TMC2208 |
| `tmc2208` | input | Drives a two-phase bipolar stepper motor through a Trinamic TMC2202 / TMC2208 / TMC2224 over its single-wire UART. The chip's internal step generator moves the motor, so no STEP pulses are needed. |
| `tmc2209-device` | config | I²C bus and address for a TMC2209 |
| `tmc2209` | input | Drives a two-phase bipolar stepper motor through a Trinamic TMC2209 over its single-wire UART. The chip's internal step generator moves the motor, so no STEP pulses are needed. |
| `tmc5160-device` | config | I²C bus and address for a TMC5160 |
| `tmc5160` | input | Drives a two-phase bipolar stepper motor through a Trinamic TMC5160 over SPI (Mode 3) or single-wire UART. The chip's internal SixPoint ramp generator moves the motor; positions are in full steps, speeds in full steps/s and accelerations in full steps/s². |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
