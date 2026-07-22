# Transport Spec: NeoPixel

**Protocol:** WS2812B single-wire NZR (Non-Return-to-Zero) at 800 Kbps  
**Reference:** Worldsemi WS2812B datasheet (see issue #22)

## Overview

The NeoPixel transport drives cascaded WS2812B-compatible addressable LEDs over a single data line using a timing-encoded NZR protocol. It is **write-only**: pixels accept data but never respond.

All platforms use the same **SPI bit-encoding** approach: each NeoPixel bit is encoded as 3 SPI bits at 2.4 MHz and shifted out on the MOSI line. No platform-specific timing libraries are used. The encoding algorithm, configuration parameters, and reset handling are identical across all nine platforms.

**Hardware constraint:** the NeoPixel DIN pin must be connected to the SPI MOSI pin. SCK, MISO, and CS are unused by the strip.

Compatible chips: WS2811, WS2812, WS2812B, WS2812S, SK6812, and most "NeoPixel"-branded variants. Payload length is variable — 3 bytes per pixel for RGB/GRB variants, 4 bytes per pixel for RGBW/GRBW variants. The transport sends whatever bytes it receives; color ordering and bytes-per-pixel are the caller's responsibility.

## Protocol

### Bit Timing

Each NeoPixel bit is a fixed-period pulse (TH + TL = 1.25 µs ± 600 ns, transmitted at 800 Kbps):

| Symbol | Description | Duration | Tolerance |
|--------|-------------|----------|-----------|
| T0H | Bit-0 high time | 0.4 µs | ±150 ns |
| T0L | Bit-0 low time | 0.85 µs | ±150 ns |
| T1H | Bit-1 high time | 0.8 µs | ±150 ns |
| T1L | Bit-1 low time | 0.45 µs | ±150 ns |
| RES | Reset / latch (line low) | ≥50 µs | — |

Data is transmitted MSB-first within each byte. The reset pulse is always appended by `write()` after the last byte.

### Pixel Format (from datasheet)

24-bit (RGB variant): `G7 G6 G5 G4 G3 G2 G1 G0 | R7 R6 R5 R4 R3 R2 R1 R0 | B7 B6 B5 B4 B3 B2 B1 B0`

32-bit (RGBW variant, e.g. SK6812): same GRB block followed by `W7..W0`.

The transport treats the buffer as opaque bytes; chip drivers or callers handle the ordering.

## Interface Contract

NeoPixel is write-only. No `read` or `write_read` operation exists.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `write` | `data: bytes` | — | Encode and transmit all bytes, then hold MOSI low ≥50 µs |

`len(data)` must be a multiple of `bytes_per_pixel` for the strip to update correctly, but the transport does not validate this.

## SPI Bit-Encoding

All platforms use this encoding. Each NeoPixel bit maps to 3 SPI bits at **2.4 MHz** (one SPI bit = 416.7 ns):

| NeoPixel bit | SPI bits | Effective timing |
|---|---|---|
| 0 | `100` | 417 ns high, 833 ns low (T0H ✓, T0L ✓) |
| 1 | `110` | 833 ns high, 417 ns low (T1H ✓, T1L ✓) |

Each NeoPixel byte (8 bits) → 24 SPI bits → 3 SPI bytes, packed MSB-first.  
For a buffer of `n` NeoPixel bytes: output `3n` encoded SPI bytes, then **16 zero bytes** (≈53 µs) for the reset.

SPI configuration: mode 0 (CPOL=0, CPHA=0), MSB-first, 2.4 MHz.

**Classic AVR Arduino note (16 MHz CPU):** the SPI clock divider only produces 2 MHz or 4 MHz. Use **2 MHz** (`SPI_CLOCK_DIV8`). At 2 MHz, T1H = 1000 ns vs the datasheet maximum of 950 ns — technically out of spec, but accepted by real WS2812B hardware in practice. All modern Arduino boards (Zero, Due, MKR, Nano 33, Nano Every) achieve 2.4 MHz without issue.

### Encoding reference (Python)

```python
def encode(data: bytes) -> bytes:
    out = bytearray(len(data) * 3 + 16)  # 16 trailing zeros = reset
    for i, byte in enumerate(data):
        bits = 0
        for bit in range(7, -1, -1):
            bits = (bits << 3) | (0b110 if (byte >> bit) & 1 else 0b100)
        out[i*3:(i+1)*3] = bits.to_bytes(3, 'big')
    return bytes(out)
```

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|-------------|
| `spi` | MicroPython | `machine.SPI` or `machine.SoftSPI` | SPI instance configured at 2.4 MHz, mode 0 |
| `spi` | CircuitPython | `busio.SPI` | SPI instance; `configure()` called in `write()` |
| `bus_num`, `device_num` | Linux Python | `int` | Opens `/dev/spidevB.D` at 2.4 MHz, mode 0 |
| `spi` | Arduino | `SPIClass&` | SPI bus (`SPI` or any `SPIClass`); speed set via `SPISettings` |
| `dev`, `config` | Zephyr | `const struct device *`, `struct spi_config` | SPI controller and config at 2.4 MHz |
| `bus_num`, `device_num` | Node.js | `int` | Opens `/dev/spidevB.D` at 2.4 MHz, mode 0 |
| `spi` | Rust (embedded-hal) | `impl SpiBus` | Any `embedded_hal::spi::SpiBus` at 2.4 MHz |
| `spi` | Rust Linux | `impl SpiBus` | `linux-embedded-hal` SPI bus at 2.4 MHz |
| `busNum`, `deviceNum` | Go Linux | `int` | Opens `/dev/spidevB.D` at 2.4 MHz, mode 0, via the Go Linux SPI transport's raw-ioctl path |
| `spi` | Go TinyGo | `machine.SPI` | SPI peripheral configured at 2.4 MHz, mode 0 |

## Platform Notes

All implementations follow the same structure:
1. Encode the input buffer with the 3-bit SPI encoding (see above)
2. Write the encoded buffer (including 16 trailing zero bytes) to the SPI bus in a single transfer
3. No CS toggling is needed; CS can be a dummy pin or left disconnected

### MicroPython

Constructor accepts a `machine.SPI` or `machine.SoftSPI` instance. The SPI instance must be pre-configured at 2.4 MHz, mode 0, MSB-first before being passed to the transport. Use `machine.SoftSPI` to drive any GPIO pin as MOSI.

```python
spi = machine.SoftSPI(baudrate=2_400_000, polarity=0, phase=0,
                      sck=machine.Pin(18), mosi=machine.Pin(19), miso=machine.Pin(20))
transport = NeoPixelTransport(spi)
```

File: `python/periph/transport/neopixel_micropython.py`

### CircuitPython

Constructor accepts a `busio.SPI` instance. Call `spi.try_lock()` / `spi.configure(baudrate=2_400_000, polarity=0, phase=0)` / `spi.unlock()` inside `write()` around the transfer. No CS pin is needed.

File: `python/periph/transport/neopixel_circuitpython.py`

### Linux

Wraps `spidev.SpiDev`, opened at 2.4 MHz, mode 0. `write()` encodes the buffer and calls `spi.xfer2(list(encoded))`. Provide `close()` to release the device.

File: `python/periph/transport/neopixel_linux.py`

### Arduino

Constructor accepts a `SPIClass&`. `write(data, len)` calls `SPI.beginTransaction(SPISettings(2400000, MSBFIRST, SPI_MODE0))`, transfers the encoded buffer byte-by-byte or with `transfer(buf, len)`, then calls `SPI.endTransaction()`. No CS pin is used.

For classic 16 MHz AVR boards, pass `SPISettings(2000000, MSBFIRST, SPI_MODE0)` instead (see timing note above).

Files: `cpp/src/transport/NeoPixelTransport.h`, `cpp/src/transport/NeoPixelTransport.cpp`

### Zephyr RTOS

Constructor accepts `const struct device *` and `struct spi_config`. Set `config.frequency = 2400000`, `config.operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER`. `write()` encodes the buffer and calls `spi_write()` with a single `spi_buf_set`.

`prj.conf`: `CONFIG_SPI=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`.

File: `cpp/src/transport/NeoPixelTransportZephyr.h`

### Node.js

Constructor accepts `busNumber` and `deviceNumber`. Opens the `spi-device` at 2.4 MHz, mode 0. `write()` encodes the buffer in JavaScript and sends it via `transferSync`.

File: `nodejs/packages/periph/src/transport/neopixel.js`

### Rust (embedded-hal, bare-metal / ESP32-S3)

Constructor wraps any `embedded_hal::spi::SpiBus` configured at 2.4 MHz. `write()` encodes the pixel buffer into a `Vec<u8>` (or a fixed-size stack buffer if `alloc` is unavailable) and calls `spi.write(&encoded)`.

```rust
impl<SPI: SpiBus> NeoPixelTransport<SPI> {
    pub fn write(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        let encoded = encode(data); // returns heapless::Vec or Vec
        self.spi.write(&encoded)
    }
}
```

File: `rust/periph/src/transport/neopixel.rs`

### Rust Linux

Same as the embedded-hal variant. Use `linux-embedded-hal`'s `SpidevBus` configured at 2.4 MHz as the `SpiBus` implementation:

```rust
use linux_embedded_hal::SpidevBus;

let spi = SpidevBus::open("/dev/spidev0.0")?;
// configure 2.4 MHz, mode 0 via SpidevOptions before passing to transport
let transport = NeoPixelTransport::new(spi);
```

`Cargo.toml`:
```toml
linux-embedded-hal = "0.4"
embedded-hal = "1"
```

### Go — Linux

Wraps the Go Linux SPI transport (`spi_linux.go`) opened at 2.4 MHz, mode 0. `Write` encodes the buffer with the same 3-bits-per-bit scheme as every other implementation, appends the 16 zero reset bytes, and sends it in one `SPI_IOC_MESSAGE` transfer.

File: `go/periph/transport/neopixel_linux.go`

### Go — TinyGo

Uses the `tinygo-org/drivers/ws2812` package rather than hand-rolling the SPI encoding — it already implements WS2812 timing per TinyGo-supported board (typically cycle-counted bit-banged GPIO, not necessarily the SPI trick), so behavior tracks whatever TinyGo's own maintained driver does. Document this divergence in the type's doc comment: TinyGo NeoPixel timing is board-native, not the SPI bit-encoding used by every other platform in this repo.

File: `go/periph/transport/neopixel_tinygo.go`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] `python/periph/transport/neopixel_micropython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/neopixel_circuitpython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/neopixel_linux.py` — Google-style docstring on class and every public method
- [ ] Tests (MicroPython)
- [ ] Tests (CircuitPython)
- [ ] Tests (Linux)

### C++
- [ ] `cpp/src/transport/NeopixelTransport.h` — Doxygen `/** @brief */` on class and every public method
- [ ] `cpp/src/transport/NeopixelTransport.cpp`
- [ ] `cpp/src/transport/NeopixelTransportLinux.h` — Doxygen
- [ ] `cpp/src/transport/NeopixelTransportLinux.cpp`
- [ ] `cpp/src/transport/NeopixelTransportZephyr.h` — Doxygen (header-only)
- [ ] Tests (Arduino)
- [ ] Tests (Linux GCC)
- [ ] Tests (Zephyr)

### Node.js
- [ ] `nodejs/packages/periph/src/transport/neopixel.js` — JSDoc on class and every exported method
- [ ] Tests

### Rust
- [ ] `rust/periph/src/transport/neopixel.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Tests (Linux)
- [ ] Tests (ESP32-S3)

### Go
- [ ] `go/periph/transport/neopixel_linux.go` — Go doc comment on the type and every exported method
- [ ] `go/periph/transport/neopixel_tinygo.go` — Go doc comment on the type and every exported method
- [ ] Tests (Linux)
- [ ] Tests (TinyGo / Pico W)
