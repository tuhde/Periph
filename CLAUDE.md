# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A multi-language library for peripheral chips (sensors, actuators, etc.) connected via SPI, I²C, or other transports.

Implementations:
- **Python** — targeting MicroPython (primary); CircuitPython may work but is mostly untested
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
1. Claude Code obtains the datasheet from the issue (download PDF attachment or fetch URL) and commits it to `datasheets/<category>/<chipname>.pdf`
2. Claude Code reads the datasheet and produces a spec in `specs/<category>/` using the appropriate template
3. Claude Code posts a **"Ready for implementation"** comment on the issue containing the spec path, branch name, and stages — this is what OpenCode uses to find its work
4. OpenCode implements against the spec on the feature branch

The comment format:
```
## Ready for implementation

- **Spec:** `specs/<category>/<chip>.md`
- **Branch:** `feature/<chip>`
- **Stages:** Minimal, Full

See `AGENTS.md` for implementation guidance.
```

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
specs/                  # Chip and transport specs (produced by Claude Code, consumed by OpenCode)
  <category>/           # One subdirectory per category (see below)
  _template_chip.md
  _template_transport.md
datasheets/
  <category>/           # Mirrors specs/ category structure
python/
  periph/
    transport/          # Abstract base + SPI/I2C implementations
    chips/
      <category>/       # One module per chip, grouped by category
  tests/
cpp/
  src/
    transport/          # Pure virtual Transport interface + SPI/I2C implementations
    chips/
      <category>/       # One header+source per chip, grouped by category
  examples/
  library.properties    # Arduino library metadata
```

Each chip driver depends only on the transport abstraction, never on a concrete bus.

### Chip categories

Categories are shared across `specs/`, `datasheets/`, `python/periph/chips/`, and `cpp/src/chips/`:

| Directory | Covers |
|-----------|--------|
| `accelerometer` | Standalone accelerometers |
| `adc_dac` | ADC and DAC converters |
| `color` | Color sensors |
| `display` | Display drivers |
| `environmental` | Combined temperature + humidity + pressure |
| `gas` | Gas sensors |
| `gpio` | GPIO expanders |
| `gyroscope` | Standalone gyroscopes |
| `humidity` | Standalone humidity sensors |
| `imu` | Combined accelerometer + gyroscope |
| `led` | LED drivers |
| `light` | Light and UV sensors |
| `magnetometer` | Magnetometers |
| `motor` | Motor drivers |
| `power` | Power management |
| `pressure` | Standalone pressure sensors |
| `rtc` | Real-time clocks |
| `temperature` | Standalone temperature sensors |
| `tof` | Time-of-flight / distance sensors |
| `other` | Anything that doesn't fit above |

## Status

No build system configured yet. Update this file with build, lint, and test commands once established.
