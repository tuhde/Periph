# Feature Design: Unified Interrupt Support

**Status:** Draft  
**Branch:** `feature/interrupt-design`  
**Scope:** All languages × all platforms — naming, abstractions, spec template, AGENTS.md guidance

---

## 1. Problem Statement

Interrupt support exists in several chip drivers (PCF8574, PCF8575, MCP23017) but each
language implemented it independently, producing inconsistent names and incompatible
mental models:

| Concern | Python | C++ | Node.js | Rust | JVM |
|---------|--------|-----|---------|------|-----|
| Enable driver-level callback | `configure_interrupt(pin, cb)` | `configure_interrupt(pin, cb)` | — | — | `configureInterrupt(cb)` |
| Disable callback | — | — | — | — | `deconfigureInterrupt()` |
| Read & clear flags | `clear_interrupt()` | `clear_interrupt()` | — | `clear_interrupt()` | `clearInterrupt()` |
| Per-pin subscribe | `pin.irq(cb, trigger)` | `pin.attachInterrupt(cb, mode)` | `pin.watch(cb)` | — | — |
| Per-pin unsubscribe | — | `pin.detachInterrupt()` | `pin.unwatch()` | — | — |

Problems:
- No single concept maps cleanly across all five languages.
- The INT-signal delivery mechanism (hardware IRQ vs. polling thread vs. epoll) is
  embedded inside the driver, making it hard to swap or extend.
- New chip drivers have no guidance on which pattern to follow.
- Spec templates have no interrupt section; implementers invent ad hoc.

---

## 2. Design Goals

1. **Consistent naming** — one vocabulary for interrupts, adapted to each language's
   convention but mapping to the same concept everywhere.
2. **Separated concerns** — INT-pin delivery (hardware IRQ, polling, epoll) is handled
   by a thin `GpioPin` abstraction in the transport layer; chip drivers only call
   `gpio_pin.on_edge(handler)`.
3. **Rust-safe** — Rust stays callback-free (`no_std`, no heap); drivers expose only
   `poll_interrupt()`. Rust users wire the chip to their own interrupt context.
4. **Additive** — existing drivers are migrated; no chip loses functionality.
5. **Spec-first** — the spec template gains an `## Interrupt` section; AGENTS.md is
   updated with per-language guidance.

---

## 3. Vocabulary

| Concept | Unified name (snake_case) | Language-idiomatic form |
|---------|--------------------------|------------------------|
| Enable driver-level change callback | `on_change` | `on_change` / `onChange` |
| Disable driver-level callback | `off_change` | `off_change` / `offChange` |
| Read & clear interrupt flags | `poll_interrupt` | `poll_interrupt` / `pollInterrupt` |
| Per-pin subscribe | `watch` | `watch` (all except C++) |
| Per-pin unsubscribe | `unwatch` | `unwatch` / `detachInterrupt` (C++) |
| Trigger mode (enum/constant) | `CHANGE`, `RISING`, `FALLING` | platform-native constants |

Rationale for `on_change` / `off_change`:
- Mirrors the existing transport-layer naming style (`write`, `read`, `write_read`).
- Short, unambiguous, not overloaded (`configure_interrupt` sounds like a one-time
  register write; `on_change` clearly means "subscribe to changes").
- `poll_interrupt` is kept for the low-level, callback-free path that Rust (and any
  bare-metal C++ without a GPIO pin) always uses.

---

## 4. GpioPin Abstraction

The new `GpioPin` type lives in the **transport** layer (one implementation file per
platform). It wraps a single digital input that can deliver edge notifications.
Chip drivers receive an optional `GpioPin`; if `None`/`nullptr`/`null`, the driver
falls back to polling.

### 4.1 Python (`python/periph/transport/gpio.py`)

```python
from abc import ABC, abstractmethod

class GpioPin(ABC):
    """Opaque edge-notification source for a chip's INT line."""

    RISING  = 1
    FALLING = 2
    CHANGE  = 3

    @abstractmethod
    def on_edge(self, handler, trigger=FALLING):
        """Register *handler()* for edge events matching *trigger*.
        Called from the MicroPython IRQ context or a polling thread."""

    @abstractmethod
    def off_edge(self):
        """Deregister handler and release resources."""
```

Concrete implementations (same file, platform-gated):

| Class | Platform | Mechanism |
|-------|----------|-----------|
| `MicroPythonPin` | MicroPython | `machine.Pin.irq()` |
| `CircuitPythonPin` | CircuitPython | `countio.Counter` or busy-wait |
| `LinuxPollingPin` | Linux (no GPIO hw) | 5 ms `threading.Thread` loop |
| `LinuxSysfsPin` | Linux (sysfs GPIO) | `select.select()` on `/sys/class/gpio/gpioN/value` |

Usage in a Full driver constructor:

```python
class Pcf8574Full(Pcf8574Minimal):
    def __init__(self, transport, int_pin: GpioPin | None = None):
        super().__init__(transport)
        self._int_pin = int_pin
        self._callback = None
```

### 4.2 C++ (`cpp/src/transport/GpioPin.h`)

```cpp
class GpioPin {
public:
    static constexpr uint8_t FALLING = 0;
    static constexpr uint8_t RISING  = 1;
    static constexpr uint8_t CHANGE  = 2;

    virtual void onEdge(void (*handler)(), uint8_t trigger = FALLING) = 0;
    virtual void offEdge() = 0;
    virtual ~GpioPin() = default;
};
```

Concrete implementations (one header each):

| Class | File | Platform | Mechanism |
|-------|------|----------|-----------|
| `ArduinoGpioPin` | `GpioPinArduino.h` | Arduino | `attachInterrupt(digitalPinToInterrupt(pin), …)` |
| `LinuxGpioPin` | `GpioPinLinux.h` | Linux GCC | `poll()` thread on `/sys/class/gpio/gpioN/value` |
| `ZephyrGpioPin` | `GpioPinZephyr.h` | Zephyr | `gpio_add_callback()` |

Usage:

```cpp
class PCF8574Full : public PCF8574Minimal {
public:
    explicit PCF8574Full(Transport& transport, GpioPin* int_pin = nullptr);
};
```

The `#ifdef __linux__` / `#ifdef CONFIG_GPIO` guards move from chip files into
`GpioPinLinux.h` and `GpioPinZephyr.h`. Chip drivers become platform-agnostic.

### 4.3 Node.js (`nodejs/packages/periph/src/transport/gpio.js`)

```js
class GpioPin {
    /**
     * Register *callback* for edge events.
     * @param {function} callback  Called with no arguments on each edge.
     * @param {string}   trigger   'rising' | 'falling' | 'change'
     * @returns {Promise<void>}
     */
    async onEdge(callback, trigger = 'falling') { throw new Error('abstract'); }

    /** Deregister and release resources. @returns {Promise<void>} */
    async offEdge() { throw new Error('abstract'); }
}
```

| Class | Mechanism |
|-------|-----------|
| `EpollGpioPin` | `epoll` on sysfs or `gpiod` |
| `PollingGpioPin` | 5 ms `setInterval` polling |

### 4.4 Rust — no GpioPin abstraction

Rust does not use callbacks (no_std, no heap allocation for closures in the general
case). Instead, chip Full drivers expose only `poll_interrupt()`. The application wires
this call into its own interrupt context using whatever HAL is available:

```rust
// Application code on Linux:
let mut chip = Pcf8574Full::new(i2c);
// In an interrupt handler or polling loop:
let changed = chip.poll_interrupt()?;
```

If a future Rust target requires hardware IRQ delivery, that is handled by the
application layer, not the driver.

### 4.5 JVM (`jvm/periph-transport/src/main/java/it/uhde/periph/transport/GpioPin.java`)

```java
@FunctionalInterface
public interface EdgeHandler { void onEdge(); }

public interface GpioPin extends AutoCloseable {
    void onEdge(EdgeHandler handler, EdgeTrigger trigger);
    void offEdge();
}

public enum EdgeTrigger { RISING, FALLING, CHANGE }
```

| Class | Mechanism |
|-------|-----------|
| `Pi4JGpioPin` | Pi4J `DigitalInput` listener |
| `PollingGpioPin` | 5 ms `ScheduledExecutorService` |

---

## 5. Driver-Level API

### 5.1 Method signatures

All languages follow the same three-method contract on `Full` drivers:

| Method | Returns | Description |
|--------|---------|-------------|
| `on_change(callback)` | `void` | Subscribe to any input change; fires callback with pin bitmask |
| `off_change()` | `void` | Unsubscribe and stop delivery |
| `poll_interrupt()` | `int`/`u8`/`Result<u8,E>` | Read & clear interrupt flags; returns changed-pin bitmask |

For multi-port chips (e.g. MCP23017 with PORTA / PORTB):

| Method | Returns | Description |
|--------|---------|-------------|
| `on_change(port, callback)` | `void` | Subscribe to port A (0) or B (1) |
| `off_change(port)` | `void` | Unsubscribe from port |
| `poll_interrupt(port)` | `int`/`Result<u8,E>` | Read & clear INTA or INTB flags |

### 5.2 Callback signature

All platforms pass a single integer — a bitmask of the pins that changed — to the
callback. Bit 0 = pin 0, bit 7 = pin 7.

```
callback(changed: int)          # Python, Node.js, JVM (IntConsumer)
void (*callback)(uint8_t)       # C++
```

### 5.3 Delivery mechanism per platform

| Platform | Delivery | Notes |
|----------|----------|-------|
| MicroPython | Hardware IRQ via `GpioPin.on_edge` → `poll_interrupt` in handler | Handler runs in IRQ context — keep short |
| CircuitPython | Same as MicroPython | |
| Python Linux (no hw GPIO) | 5 ms polling thread | `LinuxPollingPin` used automatically when `int_pin=None` |
| Python Linux (sysfs GPIO) | `select()` on sysfs fd | Pass `LinuxSysfsPin(gpio_num)` |
| Arduino | Hardware IRQ via `ArduinoGpioPin` | Handler runs in ISR — keep short |
| Linux GCC | `poll()` thread via `LinuxGpioPin` | |
| Zephyr | `gpio_add_callback()` via `ZephyrGpioPin` | |
| Node.js (epoll) | `EpollGpioPin` | Requires `epoll` npm package |
| Node.js (polling) | `PollingGpioPin` | Fallback when no GPIO available |
| Rust | None (user-managed) | Call `poll_interrupt()` from own ISR or loop |
| JVM (Pi4J GPIO) | `Pi4JGpioPin` listener | |
| JVM (polling) | `PollingGpioPin` via `ScheduledExecutorService` | Default on Pi |

---

## 6. Per-Pin API (IO Expanders Only)

IO expander drivers expose a `Pin` / `IOExpanderPin` class per physical pin.
Full drivers add `watch` / `unwatch` on each pin.

### 6.1 Unified pin API

| Language | Subscribe | Unsubscribe | Trigger argument |
|----------|-----------|-------------|-----------------|
| Python | `pin.watch(handler, trigger=GpioPin.CHANGE)` | `pin.unwatch()` | `GpioPin.RISING`, `.FALLING`, `.CHANGE` |
| C++ | `pin.watch(handler, mode)` | `pin.detachInterrupt()` | `GpioPin::RISING`, `::FALLING`, `::CHANGE` |
| Node.js | `pin.watch(callback, trigger='change')` | `pin.unwatch()` | `'rising'`, `'falling'`, `'change'` |
| JVM | `pin.watch(handler, EdgeTrigger.CHANGE)` | `pin.unwatch()` | `EdgeTrigger` enum |

Rust has no pin-level subscribe (same reason as driver level).

### 6.2 Trigger filtering

Each pin stores its registered trigger. When the driver's `on_change` callback fires
with a bitmask, the pin checks its bit and its trigger:

```
pin.watch(handler, FALLING)
  → handler is called only when this pin transitions high→low
```

This filtering happens inside the pin object; the chip driver always passes the raw
bitmask to `on_change`. The pin class implements the edge-direction check by comparing
the current read to its cached previous state.

### 6.3 Multiple handlers per pin

A pin may have at most one `watch` handler at a time. A second `watch()` call replaces
the first (no error; log a debug warning).

---

## 7. Interrupt Capability Levels

Chips vary in how much interrupt hardware they expose. Three levels:

| Level | Description | Driver methods | Pin methods |
|-------|-------------|----------------|-------------|
| **0** | No INT output | none | none |
| **1** | Single INT line, any-change only | `on_change`, `off_change`, `poll_interrupt` | `watch`, `unwatch` |
| **2** | Per-port INT lines, configurable mode (change vs. default-compare) | `on_change(port, …)`, `off_change(port)`, `poll_interrupt(port)` | `watch`, `unwatch` with trigger filtering |

Chips currently implemented:

| Chip | Level | Ports | Notes |
|------|-------|-------|-------|
| PCF8574 | 1 | 1 | Active-low open-drain INT; any-change only |
| PCF8575 | 1 | 1 | Same as PCF8574, 16-bit |
| MCP23017 | 2 | 2 (A, B) | INTA / INTB; interrupt-on-change + default-compare |

---

## 8. Comparison Across Languages and Platforms

### 8.1 Feature parity matrix

| Capability | Python MicroPy | Python CP | Python Linux | C++ Arduino | C++ Linux | C++ Zephyr | Node.js | Rust | JVM |
|-----------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `on_change` / `off_change` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `poll_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Hardware-edge delivery | ✓ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Polling-thread delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ |
| `epoll`/sysfs delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ |
| `pin.watch` / `pin.unwatch` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |
| Trigger filtering (RISING/FALLING) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |
| GpioPin abstraction | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |

✓ = supported after this feature, ✗ = not supported (by design)

### 8.2 Why Rust is polling-only

Rust targets `no_std` (ESP32-S3) and Linux host equally. A callback-based API would
require either:
- `Box<dyn Fn()>` — heap allocation, not available on bare-metal without an allocator
- Static function pointers — breaks the generic `<I2C: embedded_hal::i2c::I2c>` API

The idiomatic Rust approach for embedded interrupts is: the application registers a
hardware ISR via the HAL or RTOS, and that ISR calls the driver's `poll_interrupt()`.
This design does not change.

On Linux host, a future `Pcf8574FullLinux` wrapper (outside the `no_std` crate) could
add a polling thread, but that is out of scope for this feature.

### 8.3 Why JVM always polls

Pi4J does expose `DigitalInput` listeners backed by pigpio, but:
- The GPIO line must be wired and exported at OS level.
- Most Raspberry Pi use cases connect INT to an unused GPIO, which varies per setup.
- The `PollingGpioPin` default (5 ms) is sufficient for typical sensor applications.
- `Pi4JGpioPin` is provided as an opt-in for latency-sensitive applications.

### 8.4 Delivery latency summary

| Delivery | Typical latency | Jitter |
|----------|----------------|--------|
| Hardware IRQ (MicroPython, Arduino, Zephyr) | < 10 µs | very low |
| epoll / sysfs (Linux, Node.js) | < 1 ms | low |
| Polling thread 5 ms (Linux, JVM, Node.js fallback) | 0–5 ms | ±5 ms |

---

## 9. Spec Template Changes

### 9.1 Base template (`specs/_template_chip.md`)

Add a new top-level section after `## Pin Configuration`:

```markdown
## Interrupt

<!-- Remove this section entirely if the chip has no INT output. -->

| Property | Value |
|----------|-------|
| INT pin | active-low, open-drain (add pull-up) |
| Trigger condition | any input change |
| Clear mechanism | read port register |
| Level | 1 — single INT line |

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_change` | `on_change(callback)` | Subscribe; callback(changed_mask: int) |
| `off_change` | `off_change()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Read & clear; returns changed-pin bitmask |

### Pin interrupt API (IO expanders only)

| Method | Signature | Description |
|--------|-----------|-------------|
| `watch` | `watch(handler, trigger=CHANGE)` | Subscribe to this pin's edges |
| `unwatch` | `unwatch()` | Unsubscribe |
```

### 9.2 IO Expander template (`specs/_template_chip_io_expander.md`)

Replace the existing ad-hoc "Interrupt output" row in Pin Capabilities with the full
block above, and add the `Level` row (1 or 2).

---

## 10. AGENTS.md Changes

The following guidance replaces the current scattered interrupt paragraphs (lines 244,
283, 310, 334 in the current AGENTS.md).

### Unified interrupt guidance block (insert once, near transport section)

```markdown
## Interrupt support

### Vocabulary

Use these method names for interrupt support in Full drivers. Adapt capitalization to
the language convention (snake_case for Python/Rust, camelCase for JS/JVM, C++ either).

| Concept | Method name |
|---------|-------------|
| Enable driver-level callback | `on_change(callback)` |
| Disable driver-level callback | `off_change()` |
| Read & clear interrupt flags | `poll_interrupt() -> int` |
| Per-pin subscribe | `watch(handler, trigger)` |
| Per-pin unsubscribe | `unwatch()` |

### GpioPin abstraction

Chip drivers must not contain platform-specific interrupt delivery code.
Pass a `GpioPin` (from the transport layer) to the Full constructor.
If `int_pin` is `None`/`nullptr`/`null`, the driver delivers via polling.

- Python: `GpioPin` ABC in `python/periph/transport/gpio.py`
- C++: `GpioPin` base in `cpp/src/transport/GpioPin.h`
- Node.js: `GpioPin` class in `nodejs/packages/periph/src/transport/gpio.js`
- JVM: `GpioPin` interface in `jvm/periph-transport/…/transport/GpioPin.java`

### Per-language rules

**Python (MicroPython / CircuitPython)**
Pass the hardware `machine.Pin` wrapped in `MicroPythonPin` to `<Chip>Full.__init__`.
`on_change` calls `int_pin.on_edge(self._irq_handler, GpioPin.FALLING)`.
`_irq_handler` calls `poll_interrupt()` and dispatches to the stored callback.
Keep the IRQ handler short (no I/O other than the mandatory register read).

**Python (Linux)**
Default to `LinuxPollingPin` (5 ms thread) when `int_pin=None`.
Expose `LinuxSysfsPin(gpio_num)` as opt-in for lower latency.

**C++**
`configure_interrupt` is replaced by `GpioPin*` in the constructor.
Platform ifdefs (`#ifdef __linux__`, `#ifdef CONFIG_GPIO`) belong in `GpioPinLinux.h`
and `GpioPinZephyr.h`, NOT in chip driver files.
`pin.watch(handler, mode)` / `pin.detachInterrupt()` for per-pin subscription.

**Node.js**
Pass `EpollGpioPin` or `PollingGpioPin` to the Full constructor.
`on_change(callback)` calls `int_pin.onEdge(…)`.
`poll_interrupt()` is `async`; `on_change` wraps it in an async callback.

**Rust**
No callback API. Full drivers expose only `poll_interrupt() -> Result<u8, E>`.
Document in the driver docstring that the caller is responsible for wiring this
into a hardware ISR or polling loop.

**JVM**
Pass `Pi4JGpioPin` or `PollingGpioPin` to the Full constructor.
`onChange(IntConsumer)` is the driver-level API.
`pin.watch(handler, EdgeTrigger)` / `pin.unwatch()` for per-pin subscription.
```

---

## 11. Migration Plan for Existing Chip Drivers

### 11.1 PCF8574

| Change | Python | C++ | Node.js | Rust | JVM |
|--------|--------|-----|---------|------|-----|
| Constructor | Add `int_pin: GpioPin \| None = None` param | Add `GpioPin* int_pin = nullptr` param | Add `intPin = null` param | No change | Add `GpioPin intPin` param |
| `configure_interrupt` → | `on_change(cb)` + internal `int_pin.on_edge(…)` | `onChange(cb)` | `onChange(cb)` | — | `onChange(IntConsumer)` |
| `clear_interrupt` → | rename to `poll_interrupt` | rename to `pollInterrupt` | rename to `pollInterrupt` | rename to `poll_interrupt` | rename to `pollInterrupt` |
| `pin.irq` → | rename to `pin.watch` | rename to `pin.watch` + keep `detachInterrupt` alias | already `watch` | — | add `pin.watch` |
| Platform guards | Move to `GpioPin` impls | Move to `GpioPinLinux.h`, `GpioPinZephyr.h` | Move to `gpio.js` | N/A | Move to `PollingGpioPin` |

Backward-compatibility note: `configure_interrupt` and `clear_interrupt` are private
API (no version has been published on PyPI / npm / Maven). Rename without deprecation
shims.

### 11.2 PCF8575

Same changes as PCF8574 (same architecture, 16-bit port).

### 11.3 MCP23017

Additional changes for Level 2 (two ports):
- `on_change(callback)` — subscribe to both ports simultaneously (fires with `(port, changed_mask)`)  
- `on_change(port, callback)` — subscribe to single port
- `off_change()` / `off_change(port)` — symmetric
- `poll_interrupt(port)` — reads INTFA (port=0) or INTFB (port=1)

Pin-level: `pin.watch` / `pin.unwatch` unchanged in concept; the driver routes through
the correct port's INTA/INTB line.

---

## 12. New Files Summary

| File | Language | Purpose |
|------|----------|---------|
| `python/periph/transport/gpio.py` | Python | `GpioPin` ABC + `MicroPythonPin`, `LinuxPollingPin`, `LinuxSysfsPin`, `CircuitPythonPin` |
| `cpp/src/transport/GpioPin.h` | C++ | `GpioPin` base class |
| `cpp/src/transport/GpioPinArduino.h` | C++ | Arduino `attachInterrupt` implementation |
| `cpp/src/transport/GpioPinLinux.h` | C++ | Linux `poll()` thread implementation |
| `cpp/src/transport/GpioPinZephyr.h` | C++ | Zephyr `gpio_add_callback` implementation |
| `nodejs/packages/periph/src/transport/gpio.js` | Node.js | `GpioPin`, `EpollGpioPin`, `PollingGpioPin` |
| `jvm/periph-transport/…/transport/GpioPin.java` | Java | `GpioPin` interface + `EdgeTrigger` enum |
| `jvm/periph-transport/…/transport/PollingGpioPin.java` | Java | 5 ms polling implementation |
| `jvm/periph-transport/…/transport/Pi4JGpioPin.java` | Java | Pi4J GPIO listener implementation |

---

## 13. Open Questions

1. **C++ `pin.watch` vs. `pin.attachInterrupt`** — `attachInterrupt` is the Arduino
   naming convention and may be less surprising to Arduino developers. Current proposal
   uses `watch` to match Python/JS. Decision needed before implementation.

2. **Node.js `epoll` dependency** — `epoll` is Linux-only and requires native compilation.
   Should `EpollGpioPin` be an optional peer dependency, or always bundled?

3. **Rust Linux host** — Should a `Pcf8574FullLinux` type (in a `std`-gated module) be
   added with a polling thread? Out of scope for v1; revisit if requested.

4. **JVM `Pi4JGpioPin` GPIO number** — Pi4J uses BCM pin numbering. Document this
   clearly so users don't pass physical pin numbers.

5. **Interrupt capture register** — MCP23017 has `INTCAP` registers that latch the pin
   state at the moment of interrupt. Should `poll_interrupt` return both the capture
   value and the flag register, or just the flag? Current proposal: return flags only,
   expose `read_capture(port)` separately.
