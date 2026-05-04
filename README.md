# Periph

A multi-language library of drivers for peripheral chips — sensors, actuators, and other ICs connected via SPI, I²C, SMBus, or other transports.

## Implementations

| Language | Platforms | Status |
|----------|-----------|--------|
| Python | MicroPython, CircuitPython, Linux kernel (`/dev/i2c-N` via `smbus2`) | Active |
| C++ | Arduino | Active |
| Node.js / Node-RED | Linux, any Node.js host | Active |

## Supported transports

- I²C
- SPI
- SMBus

## Structure

Each chip driver is implemented in two stages:

- **Minimal** — covers the primary use case with sensible defaults; works out of the box with just a transport
- **Full** — complete chip functionality, extends Minimal

Drivers are platform-agnostic — they depend only on the transport abstraction. Choose the transport for your target:

**Python**
```python
from periph.transport.i2c_micropython import I2CTransport   # machine.I2C
from periph.transport.i2c_circuitpython import I2CTransport # busio.I2C
from periph.transport.i2c_linux import I2CTransport         # /dev/i2c-N
```

**Node.js**
```js
const { I2CTransport } = require('periph/src/transport/i2c');  // /dev/i2c-N via i2c-bus
```

## Supported chips

| Chip | Category | Python | C++ | Node.js | Node-RED |
|------|----------|--------|-----|---------|----------|
| INA226 | Power monitor | ✓ | ✓ | ✓ | ✓ |

## Node-RED

Per-category Node-RED packages are available under `nodejs/packages/node-red-contrib-periph-<category>`. To use locally, add the package directory to `nodesDir` in your Node-RED `settings.js`.

## Architecture and workflow

See [CLAUDE.md](CLAUDE.md) for repo layout, category structure, implementation stages, and the AI workflow.

## AI-implemented

This project is implemented entirely by AI — every line of code, spec, and configuration is generated without human authoring. It serves as an experiment in AI-driven open source library development at scale.
