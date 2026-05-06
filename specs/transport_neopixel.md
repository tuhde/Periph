# Transport Spec: NeoPixel

**Protocol:** WS2812B single-wire NZR (Non-Return-to-Zero) at 800 Kbps  
**Reference:** Worldsemi WS2812B datasheet (see issue #22)

## Overview

The NeoPixel transport drives cascaded WS2812B-compatible addressable LEDs over a single data line using a timing-encoded NZR protocol. It is **write-only**: pixels accept data but never respond. Each `write()` transmits a raw byte buffer then holds the line low for ≥50 µs (the reset/latch pulse).

Compatible chips: WS2811, WS2812, WS2812B, WS2812S, SK6812, and most "NeoPixel"-branded variants. The byte payload length is variable — 3 bytes per pixel for RGB/GRB variants, 4 bytes per pixel for RGBW/GRBW variants. The transport sends whatever bytes it receives; color ordering and bytes-per-pixel are the caller's responsibility.

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

Data is transmitted MSB-first within each byte. The reset pulse is always appended by `write()` after the last byte — callers never send it explicitly.

### Pixel Format (from datasheet)

24-bit (RGB variant): `G7 G6 G5 G4 G3 G2 G1 G0 | R7 R6 R5 R4 R3 R2 R1 R0 | B7 B6 B5 B4 B3 B2 B1 B0`

32-bit (RGBW variant, e.g. SK6812): same GRB block followed by `W7..W0`.

The transport treats the buffer as opaque bytes; chip drivers or callers handle the ordering.

## Interface Contract

NeoPixel is write-only. No `read` or `write_read` operation exists.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `write` | `data: bytes` | — | Transmit all bytes, then hold line low ≥50 µs |

`len(data)` must be a multiple of `bytes_per_pixel` for the strip to update correctly, but the transport does not validate this.

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|-------------|
| `pin` | MicroPython | `machine.Pin` | Data output pin (configured as output) |
| `pin` | CircuitPython | `digitalio.DigitalInOut` | Data output pin |
| `bus_num`, `device_num` | Linux Python / Node.js | `int` | SPI bus/device for bit-encoding (`/dev/spidevB.D`) |
| `pin` | Arduino | `uint8_t` | Data output pin number |
| `bpp` | Arduino | `uint8_t` | Bytes per pixel: 3 (RGB/GRB) or 4 (RGBW/GRBW); default 3 |
| `dev` | Zephyr | `const struct device *` | LED strip device from `DEVICE_DT_GET` |
| `bpp` | Zephyr | `uint8_t` | Bytes per pixel: 3 or 4 |
| `spi` | Rust (embedded-hal) | `impl SpiBus` | SPI bus for `ws2812-spi` encoding |

## SPI Bit-Encoding (Linux, Node.js, Rust Linux)

Platforms without direct bit-bang capability encode each NeoPixel bit as 3 SPI bits at **2.4 MHz** (one SPI bit = 416.7 ns):

| NeoPixel bit | SPI bit pattern | Effective timing |
|---|---|---|
| 0 | `100` | ~417 ns high, ~833 ns low (≈ T0H/T0L) |
| 1 | `110` | ~833 ns high, ~417 ns low (≈ T1H/T1L) |

Each NeoPixel byte (8 bits) → 24 SPI bits → 3 SPI bytes. Each NeoPixel bit is encoded MSB-first, packing the 3-bit codes MSB-first into the SPI bytes.

For a buffer of `n` NeoPixel bytes: output `3n` encoded SPI bytes, then **16 zero bytes** (≈ 53 µs at 2.4 MHz) for the reset pulse.

Encoding one NeoPixel byte `b` into 3 SPI bytes (Python reference):
```python
def encode_byte(b):
    out = 0
    for i in range(7, -1, -1):
        out = (out << 3) | (0b110 if (b >> i) & 1 else 0b100)
    return out.to_bytes(3, 'big')
```

## Platform Notes

### MicroPython

Use `machine.bitstream()` — the MicroPython built-in for NZR-style timing. After the call returns the line idles low; Python overhead is typically sufficient for the ≥50 µs reset, but add `time.sleep_us(50)` in `write()` to be safe.

```python
import machine, time

timing = (400, 850, 800, 450)  # (T0H_ns, T0L_ns, T1H_ns, T1L_ns)
machine.bitstream(self._pin, 0, timing, data)
time.sleep_us(50)
```

File: `python/periph/transport/neopixel_micropython.py`

### CircuitPython

Use the built-in `neopixel_write` module. It handles all timing internally; the line idles low after the last bit. No explicit reset sleep is needed if successive `write()` calls are naturally spaced >50 µs apart (they almost always are in Python).

```python
import neopixel_write
neopixel_write.neopixel_write(self._pin, bytearray(data))
```

File: `python/periph/transport/neopixel_circuitpython.py`

### Linux

Use `spidev` with SPI bit-encoding (see [SPI Bit-Encoding](#spi-bit-encoding-linux-nodejs-rust-linux)). Open the SPI device at 2.4 MHz, mode 0. Build the encoded buffer and send it in one `xfer2()` call (which includes the 16 trailing zero bytes).

```python
import spidev

spi = spidev.SpiDev()
spi.open(bus_num, device_num)
spi.mode = 0
spi.max_speed_hz = 2_400_000

# write(data):
encoded = encode(data)  # 3*len(data) + 16 zero bytes
spi.xfer2(list(encoded))
```

File: `python/periph/transport/neopixel_linux.py`

### Arduino

Use the `Adafruit_NeoPixel` library. The transport owns an internal `Adafruit_NeoPixel` instance. `write(data, len)` updates the strip length if it has changed (via `updateLength()`), copies `data` directly into the library's pixel buffer (`getPixels()`), then calls `show()`.

Constructor parameters: `pin` (Arduino pin number), `bpp` (3 for `NEO_GRB + NEO_KHZ800`, 4 for `NEO_GRBW + NEO_KHZ800`). Strip LED type is derived from `bpp`.

```cpp
// NeoPixelTransport(uint8_t pin, uint8_t bpp = 3)
// write(const uint8_t* data, size_t len):
//   _strip.updateLength(len / _bpp);
//   memcpy(_strip.getPixels(), data, len);
//   _strip.show();
```

Files: `cpp/src/transport/NeoPixelTransport.h`, `cpp/src/transport/NeoPixelTransport.cpp`

### Zephyr RTOS

Use Zephyr's native WS2812 LED strip driver. Enable via `CONFIG_LED_STRIP=y` and either `CONFIG_WS2812_STRIP_SPI=y` or `CONFIG_WS2812_STRIP_GPIO=y` in `prj.conf`. The devicetree node must declare `chain-length` and `color-mapping`.

`write(data, len)` converts the raw byte buffer into a `struct led_rgb[]` array (or `uint8_t[][bpp]` for RGBW) and calls:

```c
#include <zephyr/drivers/led_strip.h>

// 3-byte (RGB) variant:
led_strip_update_rgb(dev, pixels, n_pixels);

// 4-byte (RGBW) variant — if driver supports channels:
led_strip_update_channels(dev, channels, n_pixels * 4);
```

For `bpp == 3`: populate `struct led_rgb` with the raw R, G, B bytes from `data` (no reordering — the chip driver sends bytes already in the strip's native order).  
For `bpp == 4`: call `led_strip_update_channels()` treating each group of 4 bytes as one pixel's channels.

`prj.conf` minimum:
```
CONFIG_LED_STRIP=y
CONFIG_WS2812_STRIP=y
CONFIG_WS2812_STRIP_SPI=y   # or CONFIG_WS2812_STRIP_GPIO=y
```

File: `cpp/src/transport/NeoPixelTransportZephyr.h`

### Node.js

Use SPI bit-encoding via the existing `spi-device` package. Open the SPI device at 2.4 MHz. Encode the pixel buffer in JavaScript, then send via `transferSync`.

```js
// encode(data) → Buffer of 3*len(data) + 16 zero bytes
const spi = require('spi-device');
const device = spi.openSync(busNumber, deviceNumber, { maxSpeedHz: 2_400_000 });

// write(data):
const sendBuffer = encode(Buffer.from(data));
device.transferSync([{ sendBuffer, byteLength: sendBuffer.length }]);
```

File: `nodejs/packages/periph/src/transport/neopixel.js`

### Rust (embedded-hal, bare-metal / ESP32-S3)

Use the `ws2812-spi` crate, which encodes NeoPixel bits over any `embedded_hal::spi::SpiBus` at the correct rate. Chip drivers pass an iterator of `smart_leds::RGB8` (or `RGBA8` for 32-bit) values.

```rust
use ws2812_spi::Ws2812;
use smart_leds::SmartLedsWrite;

let mut ws = Ws2812::new(spi_bus);
ws.write(pixel_iter)?;
```

Configure the SPI bus at **3.2 MHz** (the rate `ws2812-spi` uses for its internal encoding — do not change this).

`Cargo.toml`:
```toml
ws2812-spi = "0.4"
smart-leds = "0.4"
embedded-hal = "1"
```

File: `rust/periph/src/transport/neopixel.rs`

### Rust Linux

Same as the embedded-hal variant, but back the SPI bus with `linux-embedded-hal`:

```rust
use linux_embedded_hal::SpidevBus;
use ws2812_spi::Ws2812;
use smart_leds::SmartLedsWrite;

let spi = SpidevBus::open("/dev/spidev0.0")?;
// configure speed to 3.2 MHz via SpidevOptions before wrapping
let mut ws = Ws2812::new(spi);
ws.write(pixel_iter)?;
```

`Cargo.toml`:
```toml
ws2812-spi = "0.4"
smart-leds = "0.4"
linux-embedded-hal = "0.4"
embedded-hal = "1"
```
