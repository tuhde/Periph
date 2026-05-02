# Chip Spec: <ChipName>

**Manufacturer:** <Manufacturer>  
**Datasheet:** `datasheets/<category>/<filename>`  
**Category:** <category directory, e.g. environmental, imu, temperature>  
**Transports:** <SPI | I²C | both>

## Overview

<!-- One paragraph: what the chip does and why you'd use it. -->

## Transport Configuration

### I²C
- **Address:** `0x??` (default) — `0x??` (alternate, if applicable)
- **Max clock:** <e.g. 400 kHz>

### SPI
- **Mode:** CPOL=? CPHA=? (Mode ?)
- **Max clock:** <e.g. 10 MHz>
- **Bit order:** MSB first
- **CS active:** low

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00`  | NAME | R   | `0x00`| |

### Bit Fields

#### `REGISTER_NAME` (`0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4  | FIELD_A | |
| 3:0  | FIELD_B | |

## Initialization Sequence

1. <step>
2. <step>
3. Wait <N> ms for <reason>

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: expose the chip's primary use case with a simple, user-friendly interface. No configuration required beyond the transport.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | Runs initialization sequence with sensible defaults |
| `read_<value>` | — | float | Unit: <e.g. °C> |

**Sensible defaults:** <!-- List the register settings baked in for Minimal -->

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `configure` | ... | — | Exposes all relevant settings |
| `read_<value>` | — | float | |

**Additional configuration options:** <!-- List all settings exposed in Full -->

## Data Conversion

<!-- Formulas mapping raw register values to real-world units. -->

```
value = raw * <scale> + <offset>
```

## Node-RED

Node name: `periph-<chip>`  
Package: `node-red-contrib-periph-<category>`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message   | `{ <key>: <value>, ... }`   | <!-- List the readings exposed --> |

<!-- Describe the config panel fields (I2C address, bus number, etc.) -->

## Examples

### Demo

<!-- Describe the scenario the demo example should implement. Be specific:
     what it measures, what it prints/does, what makes it interesting to watch.
     The minimal and complete examples are implied by the API tables above.
     The demo gets one short comment per logical block explaining WHY (not what). -->

## Timing Constraints

<!-- Any delays, startup times, or measurement durations the driver must respect. -->

## Implementation Notes

<!-- Quirks, errata, or non-obvious datasheet behavior. -->
