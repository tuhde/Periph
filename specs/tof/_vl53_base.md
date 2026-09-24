# Base Spec: ST VL53 FlightSense ranging family

Shared plumbing and a shared public API contract for ST's I²C Time-of-Flight ranging
sensors. The chips in this family have completely different register maps (the VL53L0X uses
8-bit register indices and a private-bank tuning scheme; the VL53L1X uses 16-bit indices and
ST's "ULD" default-configuration block), so the base class holds **no register addresses
and no ranging logic**. It holds everything that is identical across the family:

- big-endian register access with a 1- or 2-byte register index
- the bounded poll-with-timeout helper
- the XSHUT boot wait
- the lock that serializes multi-register sequences against the interrupt poller
- interrupt *delivery* (int_pin edge subscription / Linux polling-thread fallback)
- the volatile I²C re-addressing helper
- shared constants (default address, logical interrupt-source values, timeouts)

Chip specs that reference this document describe only their own registers, sequences and
chip-specific extras.

**Current chips using this base:** VL53L0X (`specs/tof/vl53l0x.md`, refactored onto it with
issue #175), VL53L1X (`specs/tof/vl53l1x.md`).
**Likely future users:** VL53L4CD / VL53L4CX / VL53L3CX (same 16-bit-index ULD scheme as the
VL53L1X, same GPIO1/XSHUT pinout).

---

## Class structure

```
VL53Base                 (internal, protected helpers only — never instantiated directly)
 ├── VL53L0XMinimal  →  VL53L0XFull
 └── VL53L1XMinimal  →  VL53L1XFull
```

The base exposes **no public ranging methods**. Minimal/Full keep their normal
relationship (Full extends Minimal); each chip's Full class exposes the public
interrupt/re-addressing methods as thin wrappers around the protected base helpers, so the
Minimal class never gains Full-only API.

| Language | Base location | Mechanism |
|----------|---------------|-----------|
| Python | `python/periph/chips/tof/_vl53_base.py`, class `_VL53Base` | Minimal classes subclass `_VL53Base`; helpers are `_`-prefixed |
| C++ | `cpp/src/chips/tof/VL53Base.h` / `.cpp`, class `VL53Base` | `class VL53L1XMinimal : public VL53Base`; helpers `protected`; constructor `protected` |
| Node.js | `nodejs/packages/periph/src/chips/tof/_vl53_base.js`, class `VL53Base` (not exported from the package index) | `class VL53L1XMinimal extends VL53Base`; helpers `_`-prefixed |
| Rust | `rust/periph/src/chips/tof/vl53_base.rs`, `pub(crate) struct Vl53Bus<I2C>` | Composition: each chip struct owns a `Vl53Bus` field; `pub(crate)` methods. No interrupt delivery (Rust drivers expose `poll_interrupt` only, per repo design) |
| Go | `go/periph/chips/tof/vl53_base.go`, unexported `vl53Base` struct | Embedded by value in `VL53L0XMinimal` / `VL53L1XMinimal`; all base methods unexported |
| Java | `jvm/periph-java/.../chips/tof/VL53Base.java`, `public abstract class VL53Base` | `protected` constructor and helpers |
| Kotlin | *(none of its own)* | Kotlin `VL53L0XMinimal`/`VL53L1XMinimal` extend the **Java** `VL53Base` — same pattern as the NeoPixel family (`periph-kotlin` already depends on `periph-java`) |
| Groovy | `jvm/periph-groovy/.../chips/tof/VL53Base.groovy` | Own copy — Groovy must **not** depend on `periph-java` (dynamic dispatch collides on same-named classes) |

---

## Constructor (internal)

| Parameter | Type | Description |
|-----------|------|-------------|
| `connection` | I²C `Connection` | Optional `en_pin` drives XSHUT (high = enabled); optional `int_pin` receives GPIO1 |
| `index_bytes` | `int` (1 or 2) | Width of the register index sent before data — VL53L0X `1`, VL53L1X `2` |
| `chip_name` | `str` | Used in error messages (`"VL53L1X timeout waiting for data ready"`) |

Rust additionally takes the `DelayNs` implementation; Go takes nothing extra.

---

## Register access helpers (protected)

All multi-byte values are **big-endian** (MSB at the lowest register index) for every chip in
the family. The register index is sent MSB first when `index_bytes == 2`. Every access is a
single I²C transaction (write) or write-then-read with repeated start (read); the chips
auto-increment the index.

| Helper | Frame on the wire |
|--------|-------------------|
| `wr8(reg, v)` | write `[index…, v]` |
| `wr16(reg, v)` | write `[index…, v>>8, v&0xFF]` |
| `wr32(reg, v)` | write `[index…, v>>24, v>>16, v>>8, v]` (each `& 0xFF`) |
| `wr_block(reg, bytes)` | write `[index…, bytes…]` |
| `rd8(reg)` | write `[index…]`, read 1 |
| `rd16(reg)` | write `[index…]`, read 2 → `(b0<<8) \| b1` |
| `rd32(reg)` | write `[index…]`, read 4 → big-endian |
| `rd_block(reg, n)` | write `[index…]`, read `n` |

where `index…` is `[reg]` for `index_bytes == 1` and `[reg>>8, reg&0xFF]` for `2`.

Chips keep their existing helper names if the language's VL53L0X driver already uses
different ones (e.g. Python `_wr`/`_rd`/`_wr16`) — the refactor moves the implementation, it
does not force a rename across unrelated call sites. New code (VL53L1X) uses the names above
with the language's usual prefix/casing (`_wr8`, `wr8`, `writeReg8` …).

---

## Poll helper (protected)

`wait_until(predicate, what)` — call `predicate()` repeatedly (each call does its own I²C
reads) until it returns true; if more than `TIMEOUT_MS` (500 ms) elapse, fail with the
language's timeout error, message `"<chip_name> timeout waiting for <what>"`. No sleep
between polls is required; Rust polls with a 1 ms `DelayNs` and a bounded count (500).

The VL53L0X's existing `_wait(reg, mask, until_set, what)` becomes a one-line adapter over
`wait_until` (or stays, implemented on top of it).

---

## Boot helper (protected)

`boot_wait()` — if the connection has an `en_pin`, drive it high (XSHUT released); then wait
`BOOT_US` = 1200 µs in every case (tBOOT ≤ 1.2 ms for both chips; the host may have just
powered the module). Chip-specific boot confirmation (e.g. the VL53L1X firmware-status poll)
is done by the chip after this returns.

---

## Lock

The per-instance lock the VL53L0X drivers already use (Python `threading.RLock` on Linux /
no-op on MicroPython/CircuitPython; Java `synchronized`; the existing C++/Node/Go
mechanism) moves into the base unchanged. Chips take it around every multi-register
sequence that must not interleave with the interrupt poller's status read/clear.

---

## Interrupt delivery (protected)

Both chips drive GPIO1 **active-low, open-drain** (each chip's init configures this), so the
edge is always FALLING.

| Helper | Behaviour |
|--------|-----------|
| `subscribe(callback, int_pin=None)` | Store `callback`. Pin = `int_pin` argument, else `connection.int_pin`. With a pin: register a FALLING-edge handler. Without a pin, where the language supports background threads (Python on Linux, Node timers, JVM, Go goroutines, C++ Linux): start a 5 ms polling loop. Otherwise (MicroPython/CircuitPython without a pin): store the callback only — the user calls `poll_interrupt()` |
| `unsubscribe()` | Remove the edge handler or stop the polling loop; clear the callback |
| handler / poll loop body | `status = poll_interrupt_status()`; if non-zero and a callback is set, `callback(status)` |

`poll_interrupt_status()` is an **abstract hook** each chip implements (under the lock):
read whether an interrupt is pending, clear it if so, and return the active logical
`SOURCE_*` value (non-zero) or `0`. Each chip's Full `poll_interrupt()` is a public wrapper
returning the hook's result.

This is exactly the machinery in the current VL53L0X Full drivers; it moves to the base
verbatim with the VL53L0X-specific status read replaced by the hook.

---

## Re-addressing helper (protected)

`set_address_reg(reg, address)` — reject `address` outside `0x08`–`0x77` (invalid-argument
error); `wr8(reg, address & 0x7F)`. The chip answers on the new address immediately; the
driver instance becomes unusable. Both chips' address is volatile (reverts to `0x29` on
power-up or XSHUT low).

---

## Shared constants

Defined once in the base; each chip module/class re-exports them under its existing public
names (e.g. Python `vl53l0x.SOURCE_LEVEL_LOW`, C++ `VL53L0XFull::SOURCE_LEVEL_LOW`, Go
`VL53L0XSourceLevelLow`) so no public API changes.

| Constant | Value | Meaning |
|----------|-------|---------|
| `I2C_ADDRESS` | `0x29` | 7-bit default address of every family member |
| `TIMEOUT_MS` | `500` | Poll timeout |
| `BOOT_US` | `1200` | XSHUT-high → first I²C access |
| `SOURCE_LEVEL_LOW` | `1` | Range < low threshold |
| `SOURCE_LEVEL_HIGH` | `2` | Range > high threshold |
| `SOURCE_OUT_OF_WINDOW` | `3` | Range < low **or** > high threshold |
| `SOURCE_NEW_SAMPLE_READY` | `4` | New measurement available (default) |
| `SOURCE_IN_WINDOW` | `5` | low ≤ range ≤ high — **VL53L1X only**; the VL53L0X rejects it |

The `SOURCE_*` values are *logical* API values. They happen to equal the VL53L0X's
`SYSTEM_INTERRUPT_CONFIG_GPIO` register encoding; other chips translate them to their own
register encoding.

---

## Family API contract

Every chip in the family implements these public methods with **identical names, parameter
units and semantics**, so application code can swap sensors by changing only the
constructor. (No formal interface type is introduced — the contract is enforced by the chip
specs.) Chips add their own extras on top (VL53L0X: VCSEL periods, profiles; VL53L1X:
distance mode, ROI, sigma threshold, calibration helpers).

### Minimal

| Operation | Returns | Semantics |
|-----------|---------|-----------|
| `distance()` | int, mm | One single-shot measurement, blocking; returns the raw range even when invalid |
| `range_valid()` | bool | Whether the most recent measurement's status is the chip's "valid" code. **Portable check** — the numeric `range_status` codes differ per chip |

### Full

| Operation | Parameters | Returns | Semantics |
|-----------|------------|---------|-----------|
| `start_continuous` | `period_ms: int = 0` | — | Start continuous ranging; `0` = as fast as the timing budget allows |
| `stop_continuous` | — | — | Stop continuous ranging, does not wait |
| `read_continuous` | — | int, mm | Block until a fresh result, read, clear |
| `data_ready` | — | bool | Non-blocking |
| `read_measurement` | — | record | Non-blocking read + clear: `{ distance_mm: int, range_status: int, signal_rate_mcps: float, ambient_rate_mcps: float, effective_spad_count: float }` — same field names and units on every chip |
| `range_status` | — | int | Chip-specific code of the most recent measurement |
| `set_timing_budget` / `timing_budget` | `budget_us: int` | — / int µs | Chips define their allowed values |
| `set_signal_rate_limit` / `signal_rate_limit` | `limit_mcps: float` | — / float | 9.7 fixed-point MCPS on both chips |
| `set_offset` / `offset` | `offset_mm: float` | — / float mm | Volatile range offset |
| `set_crosstalk_compensation` | `rate_mcps: float` | — | `0` disables |
| `recalibrate` | — | — | Temperature-drift recalibration; not while ranging |
| `set_address` | `address: int` | — | Via `set_address_reg` |
| `set_interrupt_thresholds` / `interrupt_thresholds` | `low_mm, high_mm` | — / (int, int) | |
| `model_id` / `revision_id` | — | int | Raw ID register values |
| `on_interrupt` / `off_interrupt` / `poll_interrupt` / `enable_interrupt` / `disable_interrupt` | | | Via the base interrupt delivery; `poll_interrupt` returns the logical `SOURCE_*` value that fired or `0` |

Language naming follows the chip specs (snake_case Python/Rust, camelCase C++/JS/JVM, Go
exported PascalCase).

---

## VL53L0X refactor rules

The VL53L0X drivers (all languages) move onto the base with issue #175. The refactor is
**behavior-preserving**:

- No public API change (names, signatures, constants, record types all unchanged).
- Byte-identical I²C traffic — the existing VL53L0X unit tests, Linux/hardware tests,
  conformance checker and sigrok decoder must pass **unmodified**.
- UIFlow 1/2 blocks, Node-RED node and examples are not touched.
- Only the duplicated plumbing listed above moves; all VL53L0X register logic stays in the
  VL53L0X files.
