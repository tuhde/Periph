# Transport Spec: I²C

**Protocol:** I²C (Inter-Integrated Circuit)  
**Reference:** NXP I²C-bus specification UM10204

## Overview

I²C is a two-wire serial bus (SDA + SCL) supporting multiple devices on one bus, each addressed by a 7-bit address. Used when a chip lists I²C as a supported transport.

## Interface Contract

All transport implementations must provide these operations. The transport is constructed with a configured, ready-to-use bus object from the platform (MicroPython `machine.I2C`, Arduino `TwoWire`).

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `write` | `addr: int, data: bytes` | — | Write data to device |
| `read` | `addr: int, n: int` | `bytes` | Read n bytes from device |
| `write_read` | `addr: int, data: bytes, n: int` | `bytes` | Write then read without releasing bus (repeated start); used for register reads |

## Configuration Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `bus` | platform object | Configured `machine.I2C` (MicroPython) or `TwoWire&` (Arduino) |
| `addr` | int | 7-bit device address |

Address is set at construction time — a transport instance represents one device on the bus.

## Error Handling

| Condition | Behaviour |
|-----------|-----------|
| No ACK from device | Raise `OSError` / return error code |
| Bus timeout | Raise `OSError` / return error code |

## Platform Notes

### MicroPython

Wraps `machine.I2C`. Method mapping:

| Contract | MicroPython |
|----------|-------------|
| `write` | `i2c.writeto(addr, data)` |
| `read` | `i2c.readfrom(addr, n)` |
| `write_read` | `i2c.writeto(addr, data, False)` + `i2c.readfrom_into(addr, buf)` |

`False` on `writeto` suppresses the STOP condition, so the following `readfrom_into` issues a repeated START. Do not use `writeto_then_readfrom` — that is a CircuitPython (`busio.I2C`) method and does not exist in `machine.I2C`.

Constructor accepts a `machine.I2C` or `machine.SoftI2C` instance.

### CircuitPython

Wraps `busio.I2C`. The bus must be locked before each operation and unlocked after.

| Contract | CircuitPython |
|----------|---------------|
| `write` | `try_lock()` → `writeto(addr, data)` → `unlock()` |
| `read` | `try_lock()` → `readfrom_into(addr, buf)` → `unlock()` |
| `write_read` | `try_lock()` → `writeto_then_readfrom(addr, data, buf)` → `unlock()` |

Constructor accepts a `busio.I2C` instance.

### Linux kernel

Wraps `smbus2`. Constructor accepts either a bus number (int, opens `/dev/i2c-N` itself) or an already-opened `smbus2.SMBus` instance. Call `close()` to release the bus when done.

| Contract | smbus2 |
|----------|--------|
| `write` | `i2c_rdwr(i2c_msg.write(addr, data))` |
| `read` | `i2c_rdwr(i2c_msg.read(addr, n))` |
| `write_read` | `i2c_rdwr(i2c_msg.write(...), i2c_msg.read(...))` — combined transfer, repeated start |

### Arduino

Wraps `Wire` (or any `TwoWire` instance for boards with multiple I²C buses).

| Contract | Arduino Wire |
|----------|-------------|
| `write` | `beginTransmission` → `write` → `endTransmission` |
| `read` | `requestFrom` → `read` loop |
| `write_read` | `endTransmission(false)` (repeated start) → `requestFrom` → `read` loop |
