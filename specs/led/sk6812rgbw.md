# Chip Spec: SK6812RGBW

**Manufacturer:** Normand Electronic  
**Datasheet:** `datasheets/led/SK6812RGBW.pdf`  
**Category:** led  
**Transports:** NeoPixel (WS2812B single-wire NZR via SPI bit-encoding)

## Overview

The SK6812RGBW is an addressable RGBW LED with an integrated control IC packaged in a 5050 SMD component. It extends the WS2812B protocol to four channels: red, green, blue, and white. Each pixel contains an RGB LED plus a dedicated white LED element, and the integrated driver latches its own 32-bit GRBW value from the single-wire data stream and forwards the remaining bits to the next pixel in the chain. Pixels cascade indefinitely via DIN → DOUT; the host needs only one GPIO or SPI-MOSI line. Each of the four channels has 256 brightness levels. A reset pulse (≥80 µs low) latches the transmitted data into all pixels simultaneously.

The driver in this library sits on top of the NeoPixel transport (`specs/transport_neopixel.md`), which handles all timing-critical SPI bit-encoding. The chip driver is responsible only for maintaining the pixel buffer, converting RGBW → GRBW order, and calling `transport.write()`.

## Transport Configuration

### NeoPixel

- **Encoding:** SPI bit-encoding at 2.4 MHz (see `specs/transport_neopixel.md`)
- **Bit order:** MSB-first per byte; channel order is GRBW (Green, Red, Blue, White)
- **Frame format:** `n × 4` bytes (32 bits per pixel), followed by ≥80 µs reset (24 SPI zero-bytes encoded by transport)
- **Max pixels at 30 fps:** 768 (limited by data rate; each pixel is 4 bytes instead of 3)

## Pixel Data Format

No register map — the SK6812RGBW has no addressable registers. Data is a continuous bit stream.

### 32-bit pixel word (MSB-first)

| Bits 31–24       | Bits 23–16     | Bits 15–8      | Bits 7–0       |
|------------------|----------------|----------------|----------------|
| Green (G7–G0)    | Red (R7–R0)    | Blue (B7–B0)   | White (W7–W0)  |

The driver accepts RGBW from the user and reorders to GRBW internally before passing to the transport.

## Initialization Sequence

1. Construct the NeoPixel transport for the target platform (see `specs/transport_neopixel.md`).
2. Construct `SK6812RGBWMinimal(transport, n)` where `n` is the number of pixels. The constructor allocates an internal buffer of `n × 4` zero-bytes (all pixels off).
3. No further initialization is required — the strip powers up in an undefined state and can be set at any time.

## Implementation Stages

### Minimal

Goal: light up an entire strip with one RGBW color in three lines — construct, fill, done. No brightness control or per-pixel addressing required.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `transport`, `n: int` | — | Allocates `n × 4` zero-byte buffer; stores transport |
| `fill` | `r: int`, `g: int`, `b: int`, `w: int` | — | Fills every pixel with (r, g, b, w); clamps each to [0, 255]; sends immediately |
| `off` | — | — | Equivalent to `fill(0, 0, 0, 0)`; turns the entire strip off |

**Sensible defaults:** Buffer initialized to all zeros (all pixels off). No brightness scaling. `fill()` always calls the transport write so there is no separate show step. `w` defaults to `0` in `fill()` to allow RGB-only usage.

### Full

Goal: expose per-pixel addressing, explicit frame control, global brightness scaling, and convenience helpers. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `set_pixel` | `index: int`, `r: int`, `g: int`, `b: int`, `w: int` (default 0) | — | Write one pixel into the buffer (0-indexed); clamps to [0, 255]; does **not** send |
| `set_pixels` | `colors: list[tuple[int,int,int,int]]` | — | Write a list of `(r, g, b, w)` tuples into the buffer starting at pixel 0; does not send |
| `show` | — | — | Send the current buffer (with brightness applied) to the strip |
| `brightness` | `value: int` (0–255, get/set) | `int` | Global brightness scalar; default 255 (full); applied at `show()` time: `sent = stored × brightness // 255` |
| `rotate` | `steps: int` (default 1) | — | Shift the pixel buffer left by `steps` positions (in whole pixels); wraps around; does not send |
| `fill_hsv` | `h: float` (0.0–1.0), `s: float` (0.0–1.0), `v: float` (0.0–1.0) | — | Convert HSV to RGB (white=0), fill every pixel, then send immediately |

**Additional configuration options:**
- Per-pixel addressing via `set_pixel()` and `set_pixels()`
- Explicit frame control: build frames with `set_pixel()` / `set_pixels()`, then push with `show()`
- Global brightness scaling (0–255) applied non-destructively at `show()` time
- Pixel buffer rotation for chase/scroll effects (steps are whole pixels, i.e. 4-byte units)
- HSV fill convenience for hue-sweep animations (white channel remains 0)

## Data Conversion

```
# RGBW → GRBW reorder (done internally before transport.write())
grbw_buffer[i*4 + 0] = g    # green
grbw_buffer[i*4 + 1] = r    # red
grbw_buffer[i*4 + 2] = b    # blue
grbw_buffer[i*4 + 3] = w    # white

# Brightness scaling (applied at show() time in Full)
sent = stored_channel_value * brightness // 255

# HSV → RGB (standard algorithm, all inputs 0.0–1.0, outputs 0–255 int, w=0)
# Use platform math library or implement inline for embedded targets.
```

## Node-RED

Node name: `periph-sk6812rgbw`  
Package: `node-red-contrib-periph-led`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| `{ r, g, b, w }` | — | Fill entire strip with the given RGBW color; `w` defaults to 0; no output message |
| `{ color: "#RRGGBB" }` | — | Fill entire strip with hex color; parses to r/g/b (w=0); no output message |
| `{ pixel: N, r, g, b, w }` | — | Set a single pixel and show |
| `{ pixels: [[r,g,b,w], …] }` | — | Set all pixels from array and show |
| `{ command: "off" }` | — | Turn off the entire strip |

Config panel fields:
- **SPI bus** — bus number (e.g., `0` for `/dev/spidev0.x`)
- **SPI device** — device number (e.g., `0` for `/dev/spidevx.0`)
- **Pixel count** — number of LEDs in the strip
- **Brightness** — 0–255 global brightness (default 255)

### Demo flow

An Inject node fires every 2 seconds cycling through six colors (red, warm white, green, cold white, blue, off). Each payload is `{ r, g, b, w }`. The `periph-sk6812rgbw` node updates the strip. A second Inject node sends `{ command: "off" }` on demand. The flow demonstrates both RGB and white-channel usage on a physical strip.

## Examples

### Demo

Animate a color-wheel sweep with a warm-white flash: each frame shifts the hue offset so the rainbow rotates around the strip using the RGB channels (white=0). After 10 seconds of rainbow rotation, flash all pixels with warm white (r=255, g=200, b=150, w=255) at 5 Hz for 2 seconds to showcase the dedicated white element, then return to the rainbow. Use 30 pixels, update at approximately 30 fps (33 ms sleep), and print the current hue offset and mode once per second. This exercises the white channel, brightness property, per-pixel addressing, and HSV convenience method.

## Timing Constraints

- **Bit period:** TH + TL ≈ 1.25 µs ± 600 ns (at 800 Kbps data rate)
- **T0H:** 0.3 µs ± 150 ns; **T0L:** 0.9 µs ± 150 ns
- **T1H:** 0.6 µs ± 150 ns; **T1L:** 0.6 µs ± 150 ns
- **Reset pulse (RES):** ≥ 80 µs low; the transport must append ≥24 zero-bytes (≈80 µs at 2.4 MHz SPI encoding) after the last pixel
- **Transmission time for n pixels:** n × 32 × 1.25 µs + 80 µs reset ≈ (40n + 80) µs
  - 30 pixels ≈ 1.28 ms; 300 pixels ≈ 12.08 ms; 768 pixels ≈ 30.8 ms (≈32 fps)
- **No per-pixel read-back:** the chip is write-only; the driver must maintain its own buffer

## Reset Pulse Note

The SK6812RGBW requires a ≥80 µs reset pulse, compared to ≥50 µs for the WS2812B. The NeoPixel transport appends 16 zero-bytes by default (≈53 µs). For SK6812RGBW, chip drivers must pass an extended reset to the transport — 24 zero-bytes (≈80 µs). This is handled by passing `reset_bytes=24` to `transport.write()` if the transport supports it, or by appending 24 zero-bytes to the pixel buffer directly before calling `transport.write()`.

## Implementation Notes

- **GRBW order:** the datasheet specifies G7…G0, R7…R0, B7…B0, W7…W0. The driver must reorder before handing bytes to the transport; the transport itself is order-agnostic.
- **4 bytes per pixel:** the internal buffer is `n × 4` bytes, not `n × 3`. Buffer indices must use stride 4.
- **White channel default 0:** `fill()` and `set_pixel()` accept `w` as a keyword argument defaulting to 0, allowing RGB-only usage without breaking the interface.
- **Extended reset:** append 24 trailing zero-bytes to the pixel data before calling `transport.write()` to guarantee ≥80 µs reset. Concretely: `transport.write(pixel_bytes + bytes(24))`.
- **Buffer ownership:** the chip driver owns the internal GRBW buffer. The transport receives an encoded copy; the driver's stored buffer stays in RGBW (raw user values) to allow brightness scaling without precision loss.
- **Brightness scaling at show() time:** storing raw user values and scaling just before transmission avoids accumulated rounding errors if brightness changes frequently.
- **HSV conversion:** white channel stays 0 for HSV fills. For embedded targets without `math`, implement a fixed-point or lookup-table HSV → RGB. For host targets (Linux, Node.js), use the standard library.
- **Pixel count is fixed at construction:** the transport buffer size is `n × 4` bytes. No dynamic resize.
- **`fill()` in Minimal auto-shows:** Minimal calls `transport.write()` inside `fill()`. Full separates buffer mutation from transmission — `set_pixel()` / `set_pixels()` do not call the transport; `show()` must be called explicitly.
- **`fill()` inherited in Full:** Full inherits Minimal's `fill()` as a convenience (fill all + immediate show). This is intentional — it is the fast path for all-same-color updates.
- **Cascade note:** the driver controls all `n` pixels in one strip. Multiple strips on separate SPI buses require separate driver instances.
- **rotate() stride:** rotation operates on whole pixels (4-byte units), not individual bytes, so a step of 1 shifts one pixel, not one byte.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/led/sk6812rgbw.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/led/sk6812rgbw/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/led/sk6812rgbw/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/led/sk6812rgbw/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/led/sk6812rgbw_test.py` (MicroPython)
- [ ] Tests `python/tests/led/sk6812rgbw_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/led/sk6812rgbw_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/led/SK6812RGBW.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/led/SK6812RGBW.cpp`
- [ ] Examples `cpp/examples/SK6812RGBW_Minimal/SK6812RGBW_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/SK6812RGBW_Complete/SK6812RGBW_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/SK6812RGBW_Demo/SK6812RGBW_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/SK6812RGBW_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/SK6812RGBW_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/SK6812RGBW_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/led/sk6812rgbw_test/sk6812rgbw_test.ino` (Arduino)
- [ ] Tests `cpp/tests/led/sk6812rgbw_test_linux/sk6812rgbw_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/led/sk6812rgbw_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/led/sk6812rgbw.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/led/sk6812rgbw/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/led/sk6812rgbw/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/led/sk6812rgbw/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/led/sk6812rgbw_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-led/nodes/sk6812rgbw/sk6812rgbw.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-led/nodes/sk6812rgbw/sk6812rgbw.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-led/examples/sk6812rgbw/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/led/sk6812rgbw.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/sk6812rgbw_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/sk6812rgbw_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/sk6812rgbw_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/led/sk6812rgbw_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/led/sk6812rgbw_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/led/SK6812RGBWMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/led/SK6812RGBWFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/led/SK6812RGBWMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/led/SK6812RGBWFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/led/SK6812RGBWMinimal.groovy` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/led/SK6812RGBWFull.groovy` — Javadoc on every class and public method
- [ ] Examples `jvm/examples/java/led/sk6812rgbw/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/led/sk6812rgbw/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/led/sk6812rgbw/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/led/sk6812rgbw/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/led/sk6812rgbw/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/led/sk6812rgbw/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/led/sk6812rgbw/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/led/sk6812rgbw/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/led/sk6812rgbw/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/led/sk6812rgbw/SK6812RGBWTest.java`
