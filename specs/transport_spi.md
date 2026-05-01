# Transport Spec: SPI

**Protocol:** SPI (Serial Peripheral Interface)  
**Reference:** Motorola SPI Block Guide v03.06

## Overview

SPI is a four-wire full-duplex bus (MOSI, MISO, SCK, CS). Each device gets its own CS pin; the transport instance owns one CS pin and represents one device. Used when a chip lists SPI as a supported transport.

## Interface Contract

Identical to the I²C contract — chip drivers use the same three operations regardless of transport. CS is managed internally; callers never touch it.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `write` | `data: bytes` | — | Assert CS, send data, deassert CS |
| `read` | `n: int` | `bytes` | Assert CS, clock out n dummy bytes, capture response, deassert CS |
| `write_read` | `data: bytes, n: int` | `bytes` | Assert CS, send data, clock out n bytes, deassert CS; used for register reads |

## Configuration Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `bus` | platform object | Configured `machine.SPI` (MicroPython) or `SPIClass&` (Arduino) |
| `cs` | pin | CS pin — `machine.Pin` (MicroPython) or pin number `uint8_t` (Arduino) |
| `baudrate` / `clock_hz` | int | Clock frequency in Hz (MicroPython only; Arduino uses `SPISettings`) |

For Arduino, clock speed, bit order, and data mode are passed as an `SPISettings` object at construction time.

CS idles high. Asserted low for the duration of each operation.

## Platform Notes

### MicroPython

Wraps `machine.SPI`. CS is a `machine.Pin` driven manually.

| Contract | MicroPython |
|----------|-------------|
| `write` | `cs(0)` → `spi.write(data)` → `cs(1)` |
| `read` | `cs(0)` → `spi.read(n)` → `cs(1)` |
| `write_read` | `cs(0)` → `spi.write(data)` → `spi.readinto(buf)` → `cs(1)` |

Constructor accepts a `machine.SPI` or `machine.SoftSPI` instance and a `machine.Pin` for CS.

### Arduino

Wraps `SPIClass` (the global `SPI` object or any other `TwoWire` instance for boards with multiple SPI buses). Uses `beginTransaction` / `endTransaction` to support shared-bus operation correctly.

| Contract | Arduino |
|----------|---------|
| `write` | `beginTransaction` → `digitalWrite(cs, LOW)` → `transfer` loop → `digitalWrite(cs, HIGH)` → `endTransaction` |
| `read` | same framing, transfer dummy `0x00` bytes |
| `write_read` | same framing, write phase then read phase |

Constructor accepts a `SPIClass&`, a CS pin number, and an `SPISettings` (clock, bit order, data mode).
