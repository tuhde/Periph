# Transport Spec: <TransportName>

**Protocol:** <e.g. SPI, I²C, UART>  
**Reference:** <standard or datasheet section>

## Overview

<!-- What this transport is and when chip drivers should use it. -->

## Interface Contract

All transport implementations must provide these operations:

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | config | — | Configure and open the bus |
| `write` | bytes | — | Send bytes to the device |
| `read` | length | bytes | Receive N bytes from the device |
| `write_read` | bytes, length | bytes | Write then read (repeated start / CS held) |
| `close` | — | — | Release the bus |

## Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `clock_hz` | int | | Bus clock frequency |
| ... | | | |

## Error Handling

<!-- What errors implementations must raise/return and under what conditions. -->

## Platform Notes

### MicroPython

<!-- MicroPython-specific implementation notes. -->

### Arduino

<!-- Arduino-specific implementation notes. -->

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] `python/periph/transport/<transport>_micropython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/<transport>_circuitpython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/<transport>_linux.py` — Google-style docstring on class and every public method
- [ ] Tests (MicroPython)
- [ ] Tests (CircuitPython)
- [ ] Tests (Linux)

### C++
- [ ] `cpp/src/transport/<Transport>Transport.h` — Doxygen `/** @brief */` on class and every public method
- [ ] `cpp/src/transport/<Transport>Transport.cpp`
- [ ] `cpp/src/transport/<Transport>TransportLinux.h` — Doxygen
- [ ] `cpp/src/transport/<Transport>TransportLinux.cpp`
- [ ] `cpp/src/transport/<Transport>TransportZephyr.h` — Doxygen (header-only)
- [ ] Tests (Arduino)
- [ ] Tests (Linux GCC)
- [ ] Tests (Zephyr)

### Node.js
- [ ] `nodejs/packages/periph/src/transport/<transport>.js` — JSDoc on class and every exported method
- [ ] Tests

### Rust
- [ ] `rust/periph/src/transport/<transport>.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Tests (Linux)
- [ ] Tests (ESP32-S3)
