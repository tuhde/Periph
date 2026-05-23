# Base Spec: NeoPixel RGB (3-channel)

Shared logic for all NeoPixel-protocol chips with three output channels (R, G, B). Chip specs that reference this document need only state their wire channel order and reset timing — all API definitions, implementation notes, and Node-RED conventions live here.

**Current chips using this base:** WS2812B

---

## Constructor Parameters (internal, not user-visible)

Subclass constructors call the base constructor with these fixed values:

| Parameter | Type | Description |
|-----------|------|-------------|
| `transport` | NeoPixel transport | Platform-specific SPI bit-encoder |
| `n` | `int` | Number of pixels |
| `channel_order` | `tuple[int,int,int]` | Wire byte positions for (R, G, B); e.g. `(1, 0, 2)` means wire[0]=G, wire[1]=R, wire[2]=B |
| `reset_bytes` | `int` | Zero-bytes appended after pixel data to produce the required reset pulse |

Subclass constructors are `__init__(self, transport, n)` — they do not expose `channel_order` or `reset_bytes` to the user.

---

## Initialization Sequence

1. Construct the NeoPixel transport for the target platform (see `specs/transport_neopixel.md`).
2. Construct `<Chip>Minimal(transport, n)` where `n` is the number of pixels. The constructor allocates an internal buffer of `n × 3` zero-bytes (all pixels off).
3. No further initialization is required.

---

## Implementation Stages

### Minimal

Goal: light up an entire strip with one color in three lines — construct, fill, done.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `transport`, `n: int` | — | Allocates `n × 3` zero-byte buffer; stores transport |
| `fill` | `r: int`, `g: int`, `b: int` | — | Fills every pixel with (r, g, b); clamps each to [0, 255]; sends immediately |
| `off` | — | — | Equivalent to `fill(0, 0, 0)`; turns the entire strip off |

**Sensible defaults:** Buffer initialized to all zeros. No brightness scaling. `fill()` always calls the transport write — there is no separate show step.

### Full

Goal: expose per-pixel addressing, explicit frame control, global brightness scaling, and convenience helpers. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `set_pixel` | `index: int`, `r: int`, `g: int`, `b: int` | — | Write one pixel into the buffer (0-indexed); clamps to [0, 255]; does **not** send |
| `set_pixels` | `colors: list[tuple[int,int,int]]` | — | Write a list of `(r, g, b)` tuples into the buffer starting at pixel 0; does not send |
| `show` | — | — | Send the current buffer (with brightness applied) to the strip |
| `brightness` | `value: int` (0–255, get/set) | `int` | Global brightness scalar; default 255 (full); applied at `show()` time: `sent = stored × brightness // 255` |
| `rotate` | `steps: int` (default 1) | — | Shift the pixel buffer left by `steps` positions (whole pixels); wraps around; does not send |
| `fill_hsv` | `h: float` (0.0–1.0), `s: float` (0.0–1.0), `v: float` (0.0–1.0) | — | Convert HSV to RGB, fill every pixel, then send immediately |

---

## Data Conversion

```
# User (r, g, b) → wire order (applied internally before transport.write())
# channel_order = (i_r, i_g, i_b) where wire[0]=user[i_r], wire[1]=user[i_g], wire[2]=user[i_b]
# Example for GRB (WS2812B): channel_order = (1, 0, 2)
wire[0] = [r, g, b][channel_order[0]]
wire[1] = [r, g, b][channel_order[1]]
wire[2] = [r, g, b][channel_order[2]]

# Brightness scaling (applied at show() time in Full)
sent = stored_channel_value * brightness // 255

# HSV → RGB (standard algorithm, all inputs 0.0–1.0, outputs 0–255 int)
# Use platform math library or implement inline for embedded targets.
```

---

## Node-RED Conventions

Node name: `periph-<chipname>` (e.g., `periph-ws2812b`)
Package: `node-red-contrib-periph-led`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| `{ r, g, b }` | — | Fill entire strip; no output message |
| `{ color: "#RRGGBB" }` | — | Fill entire strip with hex color |
| `{ pixel: N, r, g, b }` | — | Set a single pixel and show |
| `{ pixels: [[r,g,b], …] }` | — | Set all pixels from array and show |
| `{ command: "off" }` | — | Turn off the entire strip |

Config panel fields: **SPI bus**, **SPI device**, **Pixel count**, **Brightness** (0–255, default 255).

---

## Implementation Notes

- **Channel reorder:** apply `channel_order` mapping before handing bytes to the transport; the transport is order-agnostic.
- **Buffer ownership:** the chip driver owns the internal buffer in user (R, G, B) order. The transport receives the wire-ordered copy. Storing user values allows non-destructive brightness scaling.
- **Brightness scaling at show() time:** avoids accumulated rounding errors when brightness changes frequently.
- **Transport write is blocking and timing-critical:** do not interleave other I/O during `transport.write()`.
- **`fill()` in Minimal auto-shows:** `transport.write()` is called inside `fill()`. Full separates buffer mutation from transmission — `set_pixel()` / `set_pixels()` do not call the transport; `show()` must be called explicitly.
- **`fill()` inherited in Full:** Full inherits Minimal's `fill()` as a convenience. This is intentional — it is the fast path for all-same-color updates.
- **Pixel count is fixed at construction:** no dynamic resize.
- **HSV conversion:** for embedded targets without `math`, implement a fixed-point or lookup-table HSV → RGB.
- **rotate() stride:** rotation operates on whole pixels (3-byte units), not individual bytes.
- **Cascade:** the driver controls all `n` pixels in one strip. Multiple strips require separate driver instances.
- **reset_bytes:** append `reset_bytes` trailing zero-bytes to the pixel data before calling `transport.write()`. Concretely: `transport.write(pixel_bytes + bytes(reset_bytes))`.
