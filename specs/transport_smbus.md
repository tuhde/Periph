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
