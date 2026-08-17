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
`SMBusConnection(bus, addr, pec=False)`

### Arduino

Wraps `TwoWire`. Constructor signature:
`SMBusConnection(TwoWire& bus, uint8_t addr, bool pec = false)`

PEC errors set an internal error flag readable via `bool valid()` after each operation.

### ESP-IDF

Wraps `I2CConnectionESPIDF` and adds the same 7-bit address validation and software PEC as `SMBusConnectionZephyr`, swapping in the driver-ng `i2c_master_transmit`/`i2c_master_receive`/`i2c_master_transmit_receive` calls the wrapped connection already makes. Constructor signature: `SMBusConnectionESPIDF(i2c_master_dev_handle_t dev, uint8_t addr, bool pec = false)`.

PEC errors are reported the same way as `SMBusConnection` (Arduino): an internal error flag readable via `bool valid()` after each operation — ESP-IDF C++ code in this repo does not rely on exceptions, consistent with every other embedded platform's SMBus notes.

File: `cpp/src/connection/SMBusConnectionESPIDF.h` (header-only)

### Raspberry Pi Pico SDK

Wraps `I2CConnectionPicoSDK` and adds the same 7-bit address validation and software PEC as `SMBusConnectionZephyr`, swapping in the `hardware_i2c` calls the wrapped connection already makes. Constructor signature: `SMBusConnectionPicoSDK(i2c_inst_t* i2c, uint8_t addr, bool pec = false)`.

PEC errors are reported the same way as `SMBusConnection` (Arduino): an internal error flag readable via `bool valid()` after each operation — pico-sdk has no exceptions.

File: `cpp/src/connection/SMBusConnectionPicoSDK.h` (header-only)

### JVM (Linux)

Wraps `I2CConnection` (FFM-based, same approach as the Linux I²C connection) and adds address validation plus software PEC. Constructor signature: `SMBusConnection(int bus, int address, boolean pec)`.

`I2CConnection.writeRead` performs a stop-then-start rather than a true repeated start, and `SMBusConnection` inherits that limitation.

On a PEC mismatch, `read` and `writeRead` throw `IOException("SMBus PEC error")`.

### Go

Wraps any `Connection` (typically an `I2CConnection`, whichever of `i2c_linux.go`/`i2c_tinygo.go` the build selected) and adds 7-bit address validation plus software PEC — same approach as the JVM connection. Because it only depends on the `Connection` interface rather than a concrete I²C type, this is the one connection in the Go implementation that needs **no build tag and no separate Linux/TinyGo file** — `SMBusConnection` itself is platform-agnostic.

Constructor: `NewSMBusConnection(c Connection, addr uint8, pec bool) (*SMBusConnection, error)` — returns a non-nil error immediately if `addr` falls in the reserved 0x00–0x07 / 0x78–0x7F range.

On a PEC mismatch, `Read` and `WriteRead` return an error wrapping `"smbus: PEC error"`.

File: `go/periph/connection/smbus.go`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] `python/periph/connection/smbus_micropython.py` — Google-style docstring on class and every public method  (was `smbus.py`; renamed)
- [x] `python/periph/connection/smbus_circuitpython.py` — Google-style docstring on class and every public method
- [x] `python/periph/connection/smbus_linux.py` — Google-style docstring on class and every public method
- [x] Tests (MicroPython)
- [x] Tests (CircuitPython)
- [x] Tests (Linux)

### C++
- [x] `cpp/src/connection/SMBusConnection.h` — Doxygen `/** @brief */` on class and every public method
- [x] `cpp/src/connection/SMBusConnection.cpp`
- [x] `cpp/src/connection/SMBusConnectionLinux.h` — Doxygen
- [x] `cpp/src/connection/SMBusConnectionLinux.cpp`
- [x] `cpp/src/connection/SMBusConnectionZephyr.h` — Doxygen (header-only)
- [x] `cpp/src/connection/SMBusConnectionESPIDF.h` — Doxygen (header-only)
- [x] Tests (Arduino)
- [x] Tests (Linux GCC)
- [x] Tests (Zephyr)
- [x] Tests (ESP-IDF)

- [x] `cpp/src/connection/SMBusConnectionPicoSDK.h` — Doxygen (header-only)
- [x] Tests (Arduino)
- [x] Tests (Linux GCC)
- [x] Tests (Zephyr)
- [x] Tests (Pico SDK)

### Node.js
- [x] `nodejs/packages/periph/src/connection/smbus.js` — JSDoc on class and every exported method
- [x] Tests

### Rust
- [x] `rust/periph/src/connection/smbus.rs` — `//!` module doc + `///` on every `pub` item
- [x] Tests (Linux)
- [x] Tests (ESP32-S3)

### JVM
- [x] `jvm/periph-connection/src/main/java/it/uhde/periph/connection/SMBusConnection.java` — Javadoc on class and every public method
- [x] Tests (Pi hardware, JBang)

### Go
- [x] `go/periph/connection/smbus.go` — Go doc comment on the type and every exported method
- [x] Tests (Linux)
- [x] Tests (TinyGo / Pico W)
