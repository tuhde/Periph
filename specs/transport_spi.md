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

SPI is full-duplex: every byte sent has a simultaneous byte received. For `write_read`, the implementation must send `len(data) + n` bytes total and discard the first `len(data)` received bytes (the chip's response during the command phase).

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|-------------|
| `bus` | MicroPython | `machine.SPI` | Configured SPI instance |
| `cs` | MicroPython | `machine.Pin` | CS pin, driven manually |
| `baudrate` | MicroPython | `int` | Clock frequency in Hz |
| `bus` | CircuitPython | `busio.SPI` | Configured SPI instance |
| `cs` | CircuitPython | `digitalio.DigitalInOut` | CS pin, driven manually |
| `baudrate` | CircuitPython | `int` | Clock frequency in Hz; passed to `spi.configure()` |
| `polarity` | CircuitPython | `int` | CPOL (0 or 1); passed to `spi.configure()` |
| `phase` | CircuitPython | `int` | CPHA (0 or 1); passed to `spi.configure()` |
| `bus_num` | Linux | `int` | SPI bus number (opens `/dev/spidevBUS.DEVICE`) |
| `device_num` | Linux | `int` | Chip-select line on that bus |
| `mode` | Linux | `int` | SPI mode 0–3 (CPOL/CPHA); default 0 |
| `max_speed_hz` | Linux | `int` | Clock frequency in Hz; default 1 000 000 |
| `bus` | Arduino | `SPIClass&` | SPI bus object (`SPI` or other `SPIClass`) |
| `cs_pin` | Arduino | `uint8_t` | CS pin number |
| `settings` | Arduino | `SPISettings` | Clock, bit order, and data mode bundled together |
| `dev` | Zephyr | `const struct device *` | SPI controller from devicetree (`DEVICE_DT_GET`) |
| `config` | Zephyr | `struct spi_config` | Clock frequency, SPI operation flags, CS GPIO spec |
| `I` (generic) | Rust | `impl SpiDevice` | Any type implementing `embedded_hal::spi::SpiDevice` |
| `spi` | Pico SDK | `spi_inst_t*` | SPI controller (`spi0` or `spi1`), already configured via `spi_init()`/`spi_set_format()` |
| `cs` | Pico SDK | `uint` (GPIO pin number) | CS pin, driven manually — pico-sdk has no automatic CS the way Zephyr's devicetree `cs-gpios` does |

CS idles high. Asserted low for the duration of each operation.

## Platform Notes

### MicroPython

Wraps `machine.SPI`. CS is a `machine.Pin` driven manually. Constructor accepts a `machine.SPI` or `machine.SoftSPI` instance and a `machine.Pin` for CS.

| Contract | MicroPython |
|----------|-------------|
| `write` | `cs(0)` → `spi.write(data)` → `cs(1)` |
| `read` | `cs(0)` → `spi.read(n)` → `cs(1)` |
| `write_read` | `cs(0)` → `spi.write(data)` → `spi.readinto(buf)` → `cs(1)` |

File: `python/periph/transport/spi_micropython.py`

### CircuitPython

Wraps `busio.SPI`. The bus must be locked before each operation and unlocked after. CS is a `digitalio.DigitalInOut` driven manually. Call `spi.configure()` during lock to set clock and mode.

| Contract | CircuitPython |
|----------|---------------|
| `write` | `try_lock()` → `configure(...)` → `cs(False)` → `spi.write(data)` → `cs(True)` → `unlock()` |
| `read` | `try_lock()` → `configure(...)` → `cs(False)` → `spi.readinto(buf)` → `cs(True)` → `unlock()` |
| `write_read` | `try_lock()` → `configure(...)` → `cs(False)` → `spi.write_readinto(out, in)` → `cs(True)` → `unlock()` |

`write_readinto` requires both buffers to be the same length. For `write_read(data, n)`:
- Build `out_buf = bytes(data) + bytes(n)` (total length = `len(data) + n`)
- Build `in_buf = bytearray(len(data) + n)`
- Call `write_readinto(out_buf, in_buf)`
- Return `in_buf[len(data):]`

CS is active low: set `cs.value = False` to assert, `True` to deassert.

File: `python/periph/transport/spi_circuitpython.py`

### Linux kernel

Wraps the `spidev` Python package, which uses `/dev/spidevBUS.DEVICE`. Constructor accepts bus and device numbers plus optional mode and clock speed. Call `close()` to release the device when done.

| Contract | spidev |
|----------|--------|
| `write` | `spi.writebytes(list(data))` |
| `read` | `bytes(spi.readbytes(n))` |
| `write_read` | `bytes(spi.xfer2(list(data) + [0]*n))[len(data):]` |

`xfer2` is a simultaneous full-duplex transfer: it sends `len(data) + n` bytes and returns `len(data) + n` bytes. Discard the first `len(data)` bytes (chip response during the command phase).

`spi.mode` is an integer 0–3 (bits: CPOL in bit 1, CPHA in bit 0). `spi.max_speed_hz` sets the clock.

File: `python/periph/transport/spi_linux.py`

### Arduino

Wraps `SPIClass` (the global `SPI` object or any other `SPIClass` instance for boards with multiple SPI buses). Uses `beginTransaction` / `endTransaction` to support shared-bus operation correctly.

| Contract | Arduino |
|----------|---------|
| `write` | `beginTransaction` → `digitalWrite(cs, LOW)` → `transfer` loop → `digitalWrite(cs, HIGH)` → `endTransaction` |
| `read` | same framing; transfer dummy `0x00` bytes |
| `write_read` | same framing; write phase then read phase, all within one transaction |

Constructor accepts a `SPIClass&`, a CS pin number, and an `SPISettings` (clock, bit order, data mode).

Files: `cpp/src/transport/SPITransport.h`, `cpp/src/transport/SPITransport.cpp`

### Zephyr RTOS

Wraps the Zephyr SPI subsystem (`zephyr/drivers/spi.h`). Constructor accepts a `const struct device *` obtained via `DEVICE_DT_GET()` and a `struct spi_config` that specifies clock frequency, operation flags, and the CS GPIO spec.

Zephyr SPI is inherently full-duplex: all transfers use `spi_transceive`, which takes a TX buffer set and an RX buffer set. For `write_read`, build two-segment buffer sets so the command bytes and dummy bytes are transmitted in one CS assertion:

```c
// write_read(data, data_len, buf, buf_len):
uint8_t dummy_tx[buf_len] = {0};
struct spi_buf tx_bufs[2] = {
    { .buf = (void*)data, .len = data_len },
    { .buf = dummy_tx,    .len = buf_len  }
};
struct spi_buf rx_bufs[2] = {
    { .buf = NULL, .len = data_len },   // discard response during command phase
    { .buf = buf,  .len = buf_len  }
};
struct spi_buf_set tx_set = { .buffers = tx_bufs, .count = 2 };
struct spi_buf_set rx_set = { .buffers = rx_bufs, .count = 2 };
spi_transceive(dev, &config, &tx_set, &rx_set);
```

Setting `rx_bufs[i].buf = NULL` tells Zephyr to discard those received bytes.

Typical `spi_config`:
```c
struct spi_config cfg = {
    .frequency = 1000000,
    .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
    .slave     = 0,
    .cs        = { .gpio = GPIO_DT_SPEC_GET(node, cs_gpios), .delay = 0 },
};
```

`prj.conf` must enable `CONFIG_SPI=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`. The SPI device node and its `cs-gpios` property must be present in the board's devicetree or an overlay.

File: `cpp/src/transport/SPITransportZephyr.h`

### Raspberry Pi Pico SDK

Wraps `hardware_spi` (bare-metal `pico-sdk`, no Arduino core, no RTOS). Constructor accepts an `spi_inst_t*` (`spi0` or `spi1`) already configured via `spi_init()`/`spi_set_format()`, plus a GPIO pin number for CS. pico-sdk has no automatic CS the way Zephyr's devicetree `cs-gpios` does, so CS is a plain GPIO the transport drives itself — the same convention `SPITransport` (Arduino) already uses.

| Contract | pico-sdk |
|----------|----------|
| `write` | `gpio_put(cs, 0)` → `spi_write_blocking(spi, data, len)` → `gpio_put(cs, 1)` |
| `read` | `gpio_put(cs, 0)` → `spi_read_blocking(spi, 0x00, buf, n)` → `gpio_put(cs, 1)` |
| `write_read` | `gpio_put(cs, 0)` → `spi_write_blocking(spi, data, len)` → `spi_read_blocking(spi, 0x00, buf, n)` → `gpio_put(cs, 1)` |

`spi_read_blocking`'s second argument is the byte repeatedly clocked out on MOSI while reading — `0x00`, the same dummy-TX-byte convention `SPITransport` and `SPITransportZephyr` already use.

File: `cpp/src/transport/SPITransportPicoSDK.h` (header-only)

### Rust

#### Embedded (embedded-hal `SpiDevice`)

Chip drivers are generic over `embedded_hal::spi::SpiDevice`. The `SpiDevice` trait includes CS management, so callers do not handle CS at all — they pass a fully configured device object (e.g., from esp-hal's `spi.device()` or `embedded-hal-bus::SpiDevice`).

| Contract | embedded-hal |
|----------|--------------|
| `write` | `device.write(data)?` |
| `read` | `device.read(&mut buf)?` |
| `write_read` | `device.transaction(&mut [Operation::Write(data), Operation::Read(&mut buf)])?` |

`transaction` keeps CS asserted across all operations in the slice. This is the correct primitive for multi-phase register reads without releasing CS between the address write and the data read.

Declare the generic bound as:
```rust
use embedded_hal::spi::SpiDevice;

pub struct MyChipMinimal<SPI> {
    spi: SPI,
}

impl<SPI: SpiDevice> MyChipMinimal<SPI> { ... }
```

#### Linux (spidev crate)

For Linux host targets, use the `spidev` crate. It implements `embedded_hal::spi::SpiBus` (not `SpiDevice` — CS is manual or kernel-managed via `/dev/spidevB.D`). Wrap it with `embedded-hal-bus`'s `ExclusiveDevice` to get a `SpiDevice` implementation compatible with chip drivers:

```rust
use spidev::{Spidev, SpidevOptions, SpiModeFlags};
use embedded_hal_bus::spi::ExclusiveDevice;

let mut spi = Spidev::open("/dev/spidev0.0")?;
spi.configure(&SpidevOptions::new()
    .max_speed_hz(1_000_000)
    .mode(SpiModeFlags::SPI_MODE_0)
    .build())?;
let device = ExclusiveDevice::new_no_delay(spi, cs_pin)?;
// `device` now implements SpiDevice — pass it directly to chip driver constructors
```

`Cargo.toml` dependencies:
```toml
spidev = "0.6"
embedded-hal = "1"
embedded-hal-bus = "0.2"
```

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] `python/periph/transport/spi_micropython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/spi_circuitpython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/spi_linux.py` — Google-style docstring on class and every public method
- [x] Tests (MicroPython)
- [x] Tests (CircuitPython)
- [x] Tests (Linux)

### C++
- [x] `cpp/src/transport/SPITransport.h` — Doxygen `/** @brief */` on class and every public method
- [x] `cpp/src/transport/SPITransport.cpp`
- [x] `cpp/src/transport/SPITransportLinux.h` — Doxygen
- [x] `cpp/src/transport/SPITransportLinux.cpp`
- [x] `cpp/src/transport/SPITransportZephyr.h` — Doxygen (header-only)
- [ ] `cpp/src/transport/SPITransportPicoSDK.h` — Doxygen (header-only)
- [x] Tests (Arduino)
- [x] Tests (Linux GCC)
- [x] Tests (Zephyr)
- [ ] Tests (Pico SDK)

### Node.js
- [x] `nodejs/packages/periph/src/transport/spi.js` — JSDoc on class and every exported method
- [x] Tests

### Rust
- [x] `rust/periph/src/transport/spi.rs` — `//!` module doc + `///` on every `pub` item
- [x] Tests (Linux)
- [x] Tests (ESP32-S3)
