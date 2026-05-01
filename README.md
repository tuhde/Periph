# Periph

A multi-language library of drivers for peripheral chips — sensors, actuators, and other ICs connected via SPI, I²C, SMBus, or other transports.

## Implementations

| Platform | Language | Status |
|----------|----------|--------|
| MicroPython | Python | Active |
| Arduino | C++ | Active |

## Supported transports

- I²C
- SPI
- SMBus

## Structure

Each chip driver is implemented in two stages:

- **Minimal** — covers the primary use case with sensible defaults; works out of the box with just a transport
- **Full** — complete chip functionality, extends Minimal

For architecture, workflow, and contribution details see [CLAUDE.md](CLAUDE.md).

## AI-implemented

This project is implemented entirely by AI — every line of code, spec, and configuration is generated without human authoring. It serves as an experiment in AI-driven open source library development at scale.
