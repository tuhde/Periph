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
| `periph-drv2605` | input | Plays haptic effects on an ERM or LRA vibration motor through a DRV2605 haptic driver over I²C (fixed address 0x5A). The EN pin must be high. |
| `periph-drv8830` | input | Drives a brushed DC motor through a DRV8830 low-voltage H-bridge over I²C, and reports its fault status. The chip regulates the commanded voltage across the motor, so speed stays constant as the supply sags. |
| `periph-tmc5160` | input | Drives a two-phase bipolar stepper motor through a Trinamic TMC5160 over SPI (Mode 3) or single-wire UART. The chip's internal SixPoint ramp generator moves the motor; positions are in full steps, speeds in full steps/s and accelerations in full steps/s². |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
