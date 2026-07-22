# Transport Spec: SMBus

**Protocol:** SMBus 3.0 (System Management Bus)  
**Reference:** SMBus Specification 3.0, SBS Implementers Forum

## Overview

SMBus is a strict subset of I²C with additional constraints and optional error checking. Implemented as a wrapper over `machine.I2C` (MicroPython) and `Wire` (Arduino) — no separate hardware is required. Use SMBus instead of I²C when a chip datasheet specifies SMBus compliance and you want address validation or PEC error checking.

Key additions over the I²C transport:
- **7-bit address validation** — rejects reserved addresses (0x00–0x07, 0x78–0x7F)
- **PEC (Packet Error Code)** — optional CRC-8 appended to writes and verified on reads

## Interface Contract

Same three operations as all transports.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `write` | `data: bytes` | — | Appends PEC byte if enabled |
| `read` | `n: int` | `bytes` | Reads n+1 bytes and verifies PEC if enabled |
| `write_read` | `data: bytes, n: int` | `bytes` | No PEC on write phase; PEC on read phase covers full transaction if enabled |

## Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `bus` | platform object | — | `machine.I2C` (MicroPython) or `TwoWire&` (Arduino) |
| `addr` | int | — | 7-bit device address (0x08–0x77); raises error if out of range |
| `pec` | bool | `False` | Enable Packet Error Code checking |

## PEC Computation

CRC-8 using polynomial `x⁸ + x² + x + 1` (0x07), initial value 0x00.

| Operation | Bytes covered by CRC |
|-----------|----------------------|
| `write` | `(addr << 1)` + data |
| `read` | `(addr << 1) \| 1` + received data |
| `write_read` | `(addr << 1)` + write data + `(addr << 1) \| 1` + received data |

On a PEC mismatch, raise `OSError("SMBus PEC error")` (MicroPython) or return `false` from a `valid()` check (Arduino — exceptions not available).

## Platform Notes

### MicroPython

Wraps `machine.I2C` or `machine.SoftI2C`. Constructor signature:
`SMBusTransport(bus, addr, pec=False)`

### Arduino

Wraps `TwoWire`. Constructor signature:
`SMBusTransport(TwoWire& bus, uint8_t addr, bool pec = false)`

PEC errors set an internal error flag readable via `bool valid()` after each operation.

### JVM (Linux)

Wraps `I2CTransport` (FFM-based, same approach as the Linux I²C transport) and adds address validation plus software PEC. Constructor signature: `SMBusTransport(int bus, int address, boolean pec)`.

`I2CTransport.writeRead` performs a stop-then-start rather than a true repeated start, and `SMBusTransport` inherits that limitation.

On a PEC mismatch, `read` and `writeRead` throw `IOException("SMBus PEC error")`.

### Go

Wraps any `Transport` (typically an `I2CTransport`, whichever of `i2c_linux.go`/`i2c_tinygo.go` the build selected) and adds 7-bit address validation plus software PEC — same approach as the JVM transport. Because it only depends on the `Transport` interface rather than a concrete I²C type, this is the one transport in the Go implementation that needs **no build tag and no separate Linux/TinyGo file** — `SMBusTransport` itself is platform-agnostic.

Constructor: `NewSMBusTransport(t Transport, addr uint8, pec bool) (*SMBusTransport, error)` — returns a non-nil error immediately if `addr` falls in the reserved 0x00–0x07 / 0x78–0x7F range.

On a PEC mismatch, `Read` and `WriteRead` return an error wrapping `"smbus: PEC error"`.

File: `go/periph/transport/smbus.go`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] `python/periph/transport/smbus_micropython.py` — Google-style docstring on class and every public method  (was `smbus.py`; renamed)
- [x] `python/periph/transport/smbus_circuitpython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/smbus_linux.py` — Google-style docstring on class and every public method
- [x] Tests (MicroPython)
- [x] Tests (CircuitPython)
- [x] Tests (Linux)

### C++
- [x] `cpp/src/transport/SMBusTransport.h` — Doxygen `/** @brief */` on class and every public method
- [x] `cpp/src/transport/SMBusTransport.cpp`
- [x] `cpp/src/transport/SMBusTransportLinux.h` — Doxygen
- [x] `cpp/src/transport/SMBusTransportLinux.cpp`
- [x] `cpp/src/transport/SMBusTransportZephyr.h` — Doxygen (header-only)
- [x] Tests (Arduino)
- [x] Tests (Linux GCC)
- [x] Tests (Zephyr)

### Node.js
- [x] `nodejs/packages/periph/src/transport/smbus.js` — JSDoc on class and every exported method
- [x] Tests

### Rust
- [x] `rust/periph/src/transport/smbus.rs` — `//!` module doc + `///` on every `pub` item
- [x] Tests (Linux)
- [x] Tests (ESP32-S3)

### JVM
- [x] `jvm/periph-transport/src/main/java/it/uhde/periph/transport/SMBusTransport.java` — Javadoc on class and every public method
- [x] Tests (Pi hardware, JBang)

### Go
- [ ] `go/periph/transport/smbus.go` — Go doc comment on the type and every exported method
- [ ] Tests (Linux)
- [ ] Tests (TinyGo / Pico W)
