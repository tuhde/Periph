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

### Zephyr RTOS

Wraps the Zephyr I²C subsystem (`zephyr/drivers/i2c.h`). Constructor accepts a `const struct device *` obtained via `DEVICE_DT_GET()` and a 7-bit address.

| Contract | Zephyr |
|----------|--------|
| `write` | `i2c_write(dev, data, len, addr)` |
| `read` | `i2c_read(dev, buf, len, addr)` |
| `write_read` | `i2c_write_read(dev, addr, write_buf, write_len, read_buf, read_len)` |

`prj.conf` must enable `CONFIG_I2C=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`. The I²C device node (`i2c0` by default) must be enabled in the board's devicetree or an overlay.

### Go — Linux

No cgo: uses `golang.org/x/sys/unix` for the raw `ioctl()` call plus hand-built structs mirroring `linux/i2c-dev.h`'s `struct i2c_msg` / `struct i2c_rdwr_ioctl_data` — Go's `unsafe.Pointer` plays the same role the JVM transport's manual FFM struct layout plays; neither needs a native library.

| Contract | Go Linux |
|----------|----------|
| `Write` | one `i2c_msg` (no `I2C_M_RD` flag) via `I2C_RDWR` |
| `Read` | one `i2c_msg` with `I2C_M_RD` set |
| `WriteRead` | two `i2c_msg`s in a single `I2C_RDWR` call — combined transfer, repeated start |

Constructor opens `/dev/i2c-N` (`unix.Open`) and takes the 7-bit address; `Close()` calls `unix.Close`.

File: `go/periph/transport/i2c_linux.go`

### Go — TinyGo

Wraps `machine.I2C0` (or any `machine.I2C` value the caller configured and passed in).

| Contract | TinyGo |
|----------|--------|
| `Write` | `i2c.Tx(addr, data, nil)` |
| `Read` | `i2c.Tx(addr, nil, buf)` |
| `WriteRead` | `i2c.Tx(addr, data, buf)` — `machine.I2C.Tx` is already a combined write-then-read |

Constructor accepts a configured `machine.I2C` value and the 7-bit address; `Close()` is a no-op (`machine.I2C` has no explicit release).

File: `go/periph/transport/i2c_tinygo.go`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] `python/periph/transport/i2c_micropython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/i2c_circuitpython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/i2c_linux.py` — Google-style docstring on class and every public method
- [ ] Tests (MicroPython)
- [ ] Tests (CircuitPython)
- [ ] Tests (Linux)

### C++
- [x] `cpp/src/transport/I2cTransport.h` — Doxygen `/** @brief */` on class and every public method
- [x] `cpp/src/transport/I2cTransport.cpp`
- [x] `cpp/src/transport/I2cTransportLinux.h` — Doxygen
- [x] `cpp/src/transport/I2cTransportLinux.cpp`
- [x] `cpp/src/transport/I2cTransportZephyr.h` — Doxygen (header-only)
- [ ] Tests (Arduino)
- [ ] Tests (Linux GCC)
- [ ] Tests (Zephyr)

### Node.js
- [x] `nodejs/packages/periph/src/transport/i2c.js` — JSDoc on class and every exported method
- [ ] Tests

### Rust
- [x] `rust/periph/src/transport/i2c.rs` — `//!` module doc + `///` on every `pub` item

### Go
- [ ] `go/periph/transport/i2c_linux.go` — Go doc comment on the type and every exported method
- [ ] `go/periph/transport/i2c_tinygo.go` — Go doc comment on the type and every exported method
- [ ] Tests (Linux)
- [ ] Tests (TinyGo / Pico W)
- [ ] Tests (Linux)
- [ ] Tests (ESP32-S3)
