# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A multi-language library for peripheral chips (sensors, actuators, etc.) connected via SPI, I²C, or other transports.

Implementations:
- **Python** — targeting MicroPython
- **C++** — targeting Arduino
- More implementations planned

## Workflow

**Claude Code** handles orchestration: planning, speccing, coordinating work, and deciding what to build.  
**OpenCode** handles implementation: writing the actual source code.

### Orchestration entry points

| Method | When to use |
|---|---|
| GitHub Issue | Formal request; creates a traceable record |
| `backlog.md` | Offline or batch work; Claude Code processes pending items |
| Direct conversation | Ad-hoc exploration or one-off requests |

### Flow (all entry points)
1. Claude Code reads the datasheet from `datasheets/` and produces a spec in `specs/` using the appropriate template
2. OpenCode implements against that spec in the appropriate language directories

### Spec templates
- `specs/_template_chip.md` — for new chip drivers
- `specs/_template_transport.md` — for new transport implementations

Completed specs are named `specs/<chipname>.md` or `specs/transport_<name>.md`.

## Implementation stages

Every chip driver is implemented in two stages:

| Stage | Goal | Class relationship |
|-------|------|--------------------|
| **Minimal** | Primary use case only; works out of the box with sensible defaults; no configuration required beyond the transport | Base class |
| **Full** | Complete chip functionality | Extends Minimal |

The Full class inherits Minimal and adds the rest — it never duplicates. Specs define both APIs explicitly, including which register defaults are baked into Minimal.

## Repository layout

```
specs/        # Chip and transport specs (produced by Claude Code, consumed by OpenCode)
datasheets/   # Raw manufacturer datasheets
python/
  periph/
    transport/  # Abstract base + SPI/I2C implementations
    chips/      # One module per chip
  tests/
cpp/
  src/
    transport/  # Pure virtual Transport interface + SPI/I2C implementations
    chips/      # One header+source per chip
  examples/
  library.properties  # Arduino library metadata
```

Each chip driver depends only on the transport abstraction, never on a concrete bus.

## Status

No build system configured yet. Update this file with build, lint, and test commands once established.
