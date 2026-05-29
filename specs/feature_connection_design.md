# Feature Design: Unified Interrupt Support

**Status:** Draft  
**Branch:** `feature/interrupt-design`  
**Scope:** All languages × all platforms — naming, abstractions, spec template, AGENTS.md guidance

---

## 1. Problem Statement

Interrupt support exists in several chip drivers (PCF8574, PCF8575, MCP23017) but the
design was shaped entirely by IO expanders, where the only meaningful interrupt condition
is "a GPIO pin changed state." Many other chip categories also have INT outputs with
richer semantics:

| Category | Typical interrupt conditions |
|----------|------------------------------|
| IO expander | Any input pin changed state |
| Accelerometer | Acceleration threshold exceeded, free-fall detected, tap/double-tap |
| Gyroscope | Angular rate threshold exceeded, orientation change |
| IMU | Data-ready, motion, FIFO overflow |
| Environmental | Temperature/humidity/pressure above or below threshold |
| Pressure | Threshold crossed (high/low) |
| Light/UV | Lux threshold, UV index threshold |
| RTC | Alarm time reached, periodic timer |
| ToF / Distance | Distance below proximity threshold |
| RFID | Card entered/left the field |
| ADC/DAC | Conversion complete, comparator threshold |

The existing implementation has two problems beyond IO-expander focus:

1. **Inconsistent naming** — each language invented its own method names:

   | Concern | Python | C++ | Node.js | Rust | JVM |
   |---------|--------|-----|---------|------|-----|
   | Enable callback | `configure_interrupt(pin, cb)` | `configure_interrupt(pin, cb)` | — | — | `configureInterrupt(cb)` |
   | Disable callback | — | — | — | — | `deconfigureInterrupt()` |
   | Read & clear | `clear_interrupt()` | `clear_interrupt()` | — | `clear_interrupt()` | `clearInterrupt()` |
   | Per-pin subscribe | `pin.irq(cb, trigger)` | `pin.attachInterrupt(cb, mode)` | `pin.watch(cb)` | — | — |
   | Per-pin unsubscribe | — | `pin.detachInterrupt()` | `pin.unwatch()` | — | — |

2. **INT-pin delivery embedded in chip drivers** — platform-specific code
   (`machine.Pin.irq`, `poll()` threads, `gpio_add_callback`) lives inside each chip
   driver, duplicated across every chip that has an INT output.

---

## 2. Design Goals

1. **Chip-agnostic** — the interrupt model applies equally to gyroscopes, RTCs,
   accelerometers, and IO expanders. Nothing in the core API assumes that the interrupt
   condition is a GPIO pin change.
2. **Consistent naming** — one vocabulary, adapted to each language's convention but
   mapping to the same concept everywhere.
3. **Separated concerns** — INT-pin delivery (hardware IRQ vs. polling thread vs. epoll)
   is handled by a thin `GpioPin` abstraction in the transport layer; chip drivers never
   contain platform-specific interrupt code.
4. **Configurable sources** — chips with multiple, selectable interrupt conditions expose
   `enable_interrupt(source)` / `disable_interrupt(source)`; chips with a single fixed
   condition do not.
5. **Rust-safe** — Rust stays callback-free (`no_std`, no heap); drivers expose only
   `poll_interrupt()`. Rust users wire the call into their own ISR or polling loop.
6. **Additive** — existing drivers are migrated; no chip loses functionality.
7. **Spec-first** — the spec template gains an `## Interrupt` section; AGENTS.md is
   updated with per-language guidance.

---

## 3. Vocabulary

| Concept | Unified name (snake_case) | Idiomatic forms |
|---------|--------------------------|-----------------|
| Subscribe to any INT assertion | `on_interrupt` | `on_interrupt` / `onInterrupt` |
| Unsubscribe | `off_interrupt` | `off_interrupt` / `offInterrupt` |
| Read & clear interrupt status | `poll_interrupt` | `poll_interrupt` / `pollInterrupt` |
| Enable one interrupt source | `enable_interrupt` | `enable_interrupt` / `enableInterrupt` |
| Disable one interrupt source | `disable_interrupt` | `disable_interrupt` / `disableInterrupt` |
| Per-pin subscribe *(IO expanders only)* | `watch` | `watch` |
| Per-pin unsubscribe *(IO expanders only)* | `unwatch` | `unwatch` |

**`on_interrupt(callback)`** — the callback fires whenever the chip asserts its INT
line. The callback receives an integer whose bits encode which interrupt source(s)
fired; the spec documents the bit layout. This integer is the raw value of the chip's
interrupt-status register (or a bitmask of changed pins for IO expanders).

**`poll_interrupt()`** — reads the interrupt-status register and clears it (or performs
whatever chip-specific clear sequence is needed). Returns the same integer as the
callback argument. Usable without any callback — the only method Rust exposes.

**`enable_interrupt(source)` / `disable_interrupt(source)`** — only present on chips
with selectable interrupt sources (Level 2+). `source` is a chip-specific constant
(an enum or integer). Enabling a source causes the chip to assert INT when that
condition is met; disabling it suppresses the assertion. Source-specific parameters
(threshold value, duration, etc.) are configured through chip-specific setter methods
on `Full`, not through `enable_interrupt`.

**Rationale for `on_interrupt` / `off_interrupt` over `on_change` / `off_change`:**
`on_change` implies state-change events (appropriate for IO expanders only).
`on_interrupt` correctly names the signal being delivered — the chip asserted its
hardware INT line — regardless of the underlying reason.

---

## 4. GpioPin Abstraction

The new `GpioPin` type lives in the **transport** layer (one implementation file per
platform). It wraps a single digital input that can deliver edge notifications.
Chip drivers receive an optional `GpioPin`; if `None`/`nullptr`/`null`, the driver
falls back to polling `poll_interrupt()` on a timer.

`GpioPin` is intentionally minimal: it only signals that *an* edge occurred. It carries
no information about what caused the interrupt; that is always determined by calling
`poll_interrupt()` on the chip driver.

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
        Called from the MicroPython IRQ context or a polling thread.
        handler takes no arguments; the chip driver calls poll_interrupt()."""

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
class Mpu6050Full(Mpu6050Minimal):
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

    /** Register *handler* for INT-line edges. handler takes no arguments;
     *  the chip driver calls pollInterrupt() to determine the cause. */
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

`#ifdef __linux__` / `#ifdef CONFIG_GPIO` guards live exclusively in these files.
Chip drivers never contain platform-specific code.

### 4.3 Node.js (`nodejs/packages/periph/src/transport/gpio.js`)

```js
class GpioPin {
    /**
     * Register *callback* for INT-line edges.
     * callback takes no arguments; the chip driver calls pollInterrupt().
     * @param {function} callback
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
| `PollingGpioPin` | 5 ms `setInterval` fallback |

### 4.4 Rust — no GpioPin abstraction

Rust targets `no_std` (ESP32-S3) and Linux host equally. A callback-based API would
require heap allocation (`Box<dyn Fn()>`) unavailable on bare-metal, or static function
pointers incompatible with the generic `<I2C: embedded_hal::i2c::I2c>` API.

The idiomatic pattern: the application registers a hardware ISR via the HAL or RTOS
and calls `poll_interrupt()` from within it. No `GpioPin` type is needed.

### 4.5 JVM (`jvm/periph-transport/…/transport/GpioPin.java`)

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
| `Pi4JGpioPin` | Pi4J `DigitalInput` listener (BCM pin numbering) |
| `PollingGpioPin` | 5 ms `ScheduledExecutorService` (default) |

---

## 5. Driver-Level Interrupt API

### 5.1 Core methods (all chips with INT output)

All languages implement the same three-method contract on `Full` drivers. Only
`poll_interrupt` is mandatory in Rust; the other two require a `GpioPin`.

| Method | Returns | Description |
|--------|---------|-------------|
| `on_interrupt(callback)` | void | Subscribe; callback(status: int) called on each INT assertion |
| `off_interrupt()` | void | Unsubscribe and stop delivery |
| `poll_interrupt()` | int / Result\<int,E\> | Read & clear interrupt-status register; returns raw status |

Language-idiomatic forms:

| Language | `on_interrupt` | `off_interrupt` | `poll_interrupt` | Callback type |
|----------|---------------|----------------|-----------------|---------------|
| Python | `on_interrupt(cb)` | `off_interrupt()` | `poll_interrupt() -> int` | `Callable[[int], None]` |
| C++ | `onInterrupt(cb)` | `offInterrupt()` | `uint8_t pollInterrupt()` | `void (*)(uint8_t)` |
| Node.js | `onInterrupt(cb)` | `offInterrupt()` | `async pollInterrupt() -> int` | `function(int)` |
| Rust | — | — | `poll_interrupt() -> Result<u8, E>` | — |
| JVM | `onInterrupt(IntConsumer)` | `offInterrupt()` | `int pollInterrupt()` | `IntConsumer` |

### 5.2 Interrupt source configuration (chips with selectable sources)

Chips that let the user choose which conditions assert INT expose two additional methods
on `Full`. These are always called *before* `on_interrupt`.

| Method | Description |
|--------|-------------|
| `enable_interrupt(source)` | Allow *source* to assert INT |
| `disable_interrupt(source)` | Prevent *source* from asserting INT |

`source` is a chip-specific constant. Each chip spec defines an `InterruptSource`
enum (or equivalent) with one member per condition. Example for a hypothetical IMU:

```python
class Mpu6050Source:
    DATA_READY    = 0x01   # new sample available
    MOTION        = 0x40   # acceleration threshold exceeded
    FIFO_OVERFLOW = 0x10   # FIFO buffer full
```

Source-specific parameters (thresholds, durations, axes) are configured through
separate setter methods on `Full` (e.g., `set_motion_threshold(g)`), not through
`enable_interrupt` itself.

A chip with no selectable sources (Level 1, see §7) does not expose
`enable_interrupt` / `disable_interrupt`.

### 5.3 Callback payload

The callback always receives a single integer. Its meaning is chip-specific:

| Chip type | Payload meaning |
|-----------|----------------|
| IO expander | Bitmask of input pins that changed (bit N = pin N) |
| Accelerometer / Gyroscope | Interrupt-status register bitmask (each bit = one condition) |
| RTC | Alarm / timer flags (chip-specific bitmask) |
| ADC | Conversion-complete or comparator flags |
| RFID | Event type (card detected, card removed, …) |

The spec for each chip documents the bit layout. Application code compares the
received integer against the chip-specific source constants:

```python
def handler(status):
    if status & Mpu6050Source.DATA_READY:
        reading = imu.read()
    if status & Mpu6050Source.MOTION:
        alert("motion detected")

imu.on_interrupt(handler)
```

### 5.4 Delivery mechanism per platform

| Platform | Delivery | Notes |
|----------|----------|-------|
| MicroPython | Hardware IRQ via `GpioPin.on_edge` → `poll_interrupt` in handler | Handler runs in IRQ context — keep it short |
| CircuitPython | Same as MicroPython | |
| Python Linux (no hw GPIO) | 5 ms polling thread via `LinuxPollingPin` | Default when `int_pin=None` |
| Python Linux (sysfs GPIO) | `select()` on sysfs fd via `LinuxSysfsPin` | Lower latency opt-in |
| Arduino | Hardware IRQ via `ArduinoGpioPin` | Handler runs in ISR — keep it short |
| Linux GCC | `poll()` thread via `LinuxGpioPin` | |
| Zephyr | `gpio_add_callback()` via `ZephyrGpioPin` | |
| Node.js (epoll) | `EpollGpioPin` | Requires `epoll` npm package |
| Node.js (polling) | `PollingGpioPin` | Fallback when no GPIO is available |
| Rust | None (user-managed) | Call `poll_interrupt()` from own ISR or polling loop |
| JVM (Pi4J GPIO) | `Pi4JGpioPin` listener | BCM pin numbering |
| JVM (polling) | `PollingGpioPin` via `ScheduledExecutorService` | Default |

---

## 6. Per-Pin API — IO Expanders Only

IO expander chips expose a virtual GPIO pin object (`Pin` / `IOExpanderPin`) per
physical pin. This section is specific to the `io_expander` category; all other chip
categories use only the driver-level API from §5.

The per-pin API is a thin filter layer on top of `on_interrupt`. When the driver
fires the raw changed-pin bitmask, each pin checks its own bit and, if set, applies
trigger-direction filtering before dispatching to its registered handler.

### 6.1 Unified pin API

| Language | Subscribe | Unsubscribe | Trigger argument |
|----------|-----------|-------------|-----------------|
| Python | `pin.watch(handler, trigger=CHANGE)` | `pin.unwatch()` | `GpioPin.RISING`, `.FALLING`, `.CHANGE` |
| C++ | `pin.watch(handler, mode)` | `pin.detachInterrupt()` | `GpioPin::RISING`, `::FALLING`, `::CHANGE` |
| Node.js | `pin.watch(callback, trigger='change')` | `pin.unwatch()` | `'rising'`, `'falling'`, `'change'` |
| JVM | `pin.watch(handler, EdgeTrigger.CHANGE)` | `pin.unwatch()` | `EdgeTrigger` enum |

Rust has no pin-level subscribe (same reason as driver level — no heap callbacks).

### 6.2 Trigger filtering

The chip driver always calls `on_interrupt` with the raw bitmask. Each pin object
maintains a previous-read shadow to detect direction:

```
pin.watch(handler, FALLING)
  → handler is called only when this pin transitions high → low
```

### 6.3 Multiple handlers per pin

At most one handler per pin at a time. A second `watch()` call replaces the first
silently (log a debug-level warning).

---

## 7. Interrupt Capability Levels

| Level | Description | Extra methods |
|-------|-------------|---------------|
| **0** | No INT output | none |
| **1** | Single INT line; one fixed condition (e.g., data-ready, any-pin-change) | `on_interrupt`, `off_interrupt`, `poll_interrupt` |
| **2** | Single INT line; multiple selectable conditions | adds `enable_interrupt(source)`, `disable_interrupt(source)` |
| **3** | Multiple independent INT lines (one per port or function) | all Level-2 methods, indexed by line (e.g., `poll_interrupt(port)`) |

IO-expander per-pin `watch` / `unwatch` is an additional layer above Level 1 or 3,
specific to the `io_expander` category.

### 7.1 Chips currently implemented

| Chip | Category | Level | Condition(s) | Notes |
|------|----------|-------|-------------|-------|
| PCF8574 | io_expander | 1 | Any input pin changes | Active-low open-drain INT |
| PCF8575 | io_expander | 1 | Any input pin changes | Same as PCF8574, 16-bit |
| MCP23017 | io_expander | 3 | Any input pin changes per port | INTA / INTB; interrupt-on-change + default-compare |

### 7.2 Expected future chips by level

| Level | Examples |
|-------|---------|
| 1 | Simple sensors with a data-ready pin (pressure, temperature, light, ToF) |
| 2 | IMUs, accelerometers, gyroscopes (data-ready + motion + FIFO overflow + …) |
| 2 | RTCs (alarm 1, alarm 2, periodic timer — each individually enabled) |
| 3 | Chips with separate INT lines per function (e.g., DRDY + alert on separate pins) |

---

## 8. Comparison Across Languages and Platforms

### 8.1 Feature parity matrix

| Capability | Python MicroPy | Python CP | Python Linux | C++ Arduino | C++ Linux | C++ Zephyr | Node.js | Rust | JVM |
|-----------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `on_interrupt` / `off_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `poll_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `enable_interrupt` / `disable_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Hardware-edge delivery | ✓ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Polling-thread delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ |
| `epoll` / sysfs delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ |
| `pin.watch` / `pin.unwatch` *(IO expanders)* | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |
| GpioPin abstraction | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |

✓ = supported after this feature, ✗ = not supported (by design)

`enable_interrupt` / `disable_interrupt` are register writes on the chip — all
platforms including Rust support them; they do not require a GpioPin.

### 8.2 Why Rust is polling-only for callbacks

Rust targets `no_std` (ESP32-S3) and Linux host equally. A callback-based API requires
either `Box<dyn Fn()>` (heap, unavailable without an allocator) or static function
pointers (incompatible with the generic `<I2C: embedded_hal::i2c::I2c>` API).

The idiomatic embedded Rust approach: the application registers a hardware ISR and
calls `poll_interrupt()` from within it. If a future Rust target needs polling-thread
delivery, a `std`-gated wrapper crate can add it without changing the driver crate.

### 8.3 Why JVM defaults to polling

Pi4J exposes `DigitalInput` listeners backed by pigpio, but the GPIO line must be
wired and exported per-setup. `PollingGpioPin` (5 ms) works out of the box on any
Raspberry Pi and is sufficient for typical sensor applications. `Pi4JGpioPin` is an
opt-in for latency-sensitive use cases.

### 8.4 Delivery latency summary

| Delivery | Typical latency | Jitter |
|----------|----------------|--------|
| Hardware IRQ (MicroPython, Arduino, Zephyr) | < 10 µs | very low |
| epoll / sysfs (Linux GCC, Node.js) | < 1 ms | low |
| Polling thread 5 ms (Python Linux, JVM, Node.js fallback) | 0–5 ms | ±5 ms |

---

## 9. Spec Template Changes

### 9.1 Base template (`specs/_template_chip.md`)

Add an `## Interrupt` section after `## Pin Configuration`. Remove it entirely for
chips with no INT output (Level 0).

```markdown
## Interrupt

| Property | Value |
|----------|-------|
| INT pin | active-low, open-drain — requires external pull-up |
| Level | 1 / 2 / 3 (see interrupt design doc) |
| Condition(s) | e.g. data-ready; threshold exceeded; alarm |
| Clear mechanism | read status register / write clear bit |

### Interrupt sources
<!-- Only for Level 2/3 chips. Delete for Level 1. -->

| Constant | Value | Condition |
|----------|-------|-----------|
| `SOURCE_DATA_READY` | `0x01` | New measurement available |
| `SOURCE_THRESHOLD`  | `0x02` | Configured threshold crossed |

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback)` | Subscribe; callback(status: int) |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Read & clear status register |
| `enable_interrupt` | `enable_interrupt(source)` | Enable one interrupt source *(Level 2/3 only)* |
| `disable_interrupt` | `disable_interrupt(source)` | Disable one interrupt source *(Level 2/3 only)* |

### Status register bit layout

| Bit | Constant | Meaning |
|-----|----------|---------|
| 0 | `SOURCE_DATA_READY` | New sample ready |
| 1 | `SOURCE_THRESHOLD` | Threshold crossed |
```

### 9.2 IO Expander template (`specs/_template_chip_io_expander.md`)

The existing ad-hoc "Interrupt output" row in Pin Capabilities is replaced by the
full block from §9.1, with the IO-expander-specific pin API appended:

```markdown
### Pin interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `watch` | `watch(handler, trigger=CHANGE)` | Subscribe to this pin's edge events |
| `unwatch` | `unwatch()` | Unsubscribe |
```

---

## 10. AGENTS.md Changes

The current scattered interrupt paragraphs (lines 244, 283, 310, 334) are replaced by
a single unified block.

### Proposed replacement block

```markdown
## Interrupt support

Interrupts are implemented in the `Full` driver class whenever a chip has an INT output.
See `specs/feature_interrupt_design.md` for the full design rationale.

### Vocabulary

Adapt capitalization to the language convention (snake_case Python/Rust, camelCase JS/JVM/C++).

| Concept | Method |
|---------|--------|
| Subscribe to INT assertions | `on_interrupt(callback)` |
| Unsubscribe | `off_interrupt()` |
| Read & clear status | `poll_interrupt() -> int` |
| Enable one interrupt source | `enable_interrupt(source)` — Level 2/3 chips only |
| Disable one interrupt source | `disable_interrupt(source)` — Level 2/3 chips only |
| Per-pin subscribe | `watch(handler, trigger)` — IO expanders only |
| Per-pin unsubscribe | `unwatch()` — IO expanders only |

### GpioPin abstraction

Chip drivers must not contain platform-specific interrupt delivery code.
Accept a `GpioPin` (from the transport layer) in the `Full` constructor (default: `None`/`nullptr`/`null`).
When `int_pin` is absent the driver falls back to polling `poll_interrupt()` on a timer.

- Python: `GpioPin` ABC — `python/periph/transport/gpio.py`
- C++: `GpioPin` base — `cpp/src/transport/GpioPin.h`
- Node.js: `GpioPin` class — `nodejs/packages/periph/src/transport/gpio.js`
- JVM: `GpioPin` interface — `jvm/periph-transport/…/transport/GpioPin.java`

### Interrupt sources

Chips with selectable sources (Level 2/3) define a companion `<Chip>Source` constants
class/object/enum in the same file as the driver. One constant per condition, values
matching the chip's interrupt-status register bit layout. Threshold values and other
condition-specific parameters are set via separate `Full` setter methods, not via
`enable_interrupt`.

### Per-language rules

**Python (MicroPython / CircuitPython)**
Accept `int_pin: GpioPin | None = None` in `__init__`.
`on_interrupt` calls `int_pin.on_edge(self._int_handler, GpioPin.FALLING)`.
`_int_handler` calls `poll_interrupt()` and dispatches to the stored callback.
Keep the handler short — no I/O beyond the register read.

**Python (Linux)**
Default to `LinuxPollingPin` (5 ms thread) when `int_pin=None`.
Expose `LinuxSysfsPin(gpio_num)` as opt-in for lower latency.

**C++**
Accept `GpioPin* int_pin = nullptr` in the constructor.
Platform ifdefs belong exclusively in `GpioPinLinux.h` / `GpioPinZephyr.h`.

**Node.js**
Accept `intPin = null` in the constructor.
`onInterrupt` calls `intPin.onEdge(…)`.
`pollInterrupt` is `async`; wrap it appropriately in the edge callback.

**Rust**
Full drivers expose only `poll_interrupt() -> Result<u8, E>`.
Document in the driver docstring: caller is responsible for wiring this into an ISR
or polling loop.

**JVM**
Accept `GpioPin intPin` (default: `new PollingGpioPin(5)`) in the constructor.
`onInterrupt(IntConsumer)` is the driver-level API.
```

---

## 11. Migration Plan for Existing Chip Drivers

### 11.1 PCF8574

| Change | Python | C++ | Node.js | Rust | JVM |
|--------|--------|-----|---------|------|-----|
| Constructor | Add `int_pin: GpioPin \| None = None` | Add `GpioPin* int_pin = nullptr` | Add `intPin = null` | No change | Add `GpioPin intPin` param |
| `configure_interrupt` → | `on_interrupt(cb)` | `onInterrupt(cb)` | `onInterrupt(cb)` | — | `onInterrupt(IntConsumer)` |
| `clear_interrupt` → | `poll_interrupt()` | `pollInterrupt()` | `pollInterrupt()` | `poll_interrupt()` | `pollInterrupt()` |
| `pin.irq` → | `pin.watch()` | `pin.watch()` | already `watch` | — | add `pin.watch()` |
| Platform guards | Move to `GpioPin` impls | Move to `GpioPinLinux.h`, `GpioPinZephyr.h` | Move to `gpio.js` | N/A | Move to `PollingGpioPin` |

No `enable_interrupt` / `disable_interrupt` — PCF8574 is Level 1 (all-or-nothing).

Backward compatibility: no published packages yet; rename without deprecation shims.

### 11.2 PCF8575

Same changes as PCF8574.

### 11.3 MCP23017

Level 3 (two independent INT lines). Additional changes:
- `on_interrupt(callback)` — subscribes to both ports; callback receives `(port: int, status: int)`
- `on_interrupt(port, callback)` — single-port subscription
- `off_interrupt()` / `off_interrupt(port)` — symmetric
- `poll_interrupt(port)` — reads INTFA (port=0) or INTFB (port=1)

`enable_interrupt` / `disable_interrupt` not needed — MCP23017's interrupt-on-change
applies to entire ports, not selectable event types.

Pin-level `watch` / `unwatch` unchanged in concept; routing through INTA/INTB is
internal to the driver.

---

## 12. New Files Summary

| File | Language | Purpose |
|------|----------|---------|
| `python/periph/transport/gpio.py` | Python | `GpioPin` ABC + `MicroPythonPin`, `CircuitPythonPin`, `LinuxPollingPin`, `LinuxSysfsPin` |
| `cpp/src/transport/GpioPin.h` | C++ | `GpioPin` base class |
| `cpp/src/transport/GpioPinArduino.h` | C++ | `attachInterrupt` implementation |
| `cpp/src/transport/GpioPinLinux.h` | C++ | `poll()` thread implementation |
| `cpp/src/transport/GpioPinZephyr.h` | C++ | `gpio_add_callback` implementation |
| `nodejs/packages/periph/src/transport/gpio.js` | Node.js | `GpioPin`, `EpollGpioPin`, `PollingGpioPin` |
| `jvm/periph-transport/…/transport/GpioPin.java` | Java | `GpioPin` interface + `EdgeTrigger` enum |
| `jvm/periph-transport/…/transport/PollingGpioPin.java` | Java | 5 ms polling implementation |
| `jvm/periph-transport/…/transport/Pi4JGpioPin.java` | Java | Pi4J GPIO listener |

---

## 13. Open Questions

1. **C++ `pin.watch` vs. `pin.attachInterrupt`** — `attachInterrupt` is the Arduino
   naming convention and may be less surprising there. Current proposal uses `watch` to
   match Python/JS. Decision needed before implementation.

2. **Node.js `epoll` dependency** — `epoll` is Linux-only and requires native
   compilation. Optional peer dependency, or always bundled?

3. **Rust Linux host** — Should a `std`-gated wrapper crate add polling-thread delivery
   for `poll_interrupt`? Out of scope for v1; revisit if requested.

4. **JVM `Pi4JGpioPin` pin numbering** — Pi4J uses BCM pin numbers. Document
   explicitly so users don't pass physical pin numbers.

5. **MCP23017 capture register** — `INTCAP` latches pin state at interrupt time.
   Should `poll_interrupt` return both capture and flag register, or flags only?
   Current proposal: return flags only; expose `read_capture(port)` separately.

6. **Multi-source `enable_interrupt` signature** — For chips where multiple sources
   can be enabled at once (e.g., accelerometer), should `enable_interrupt` accept a
   single source or a list? Single source is simpler; a list avoids multiple I²C
   round-trips when enabling several at startup. Proposal: single source for now,
   revisit if performance is a concern.

7. **Callback arity for Level 3 chips** — MCP23017 uses `(port, status)`, but most
   chips will use `(status,)`. Should the callback always receive `(status,)` and
   expose two separate `on_interrupt` calls for multi-port chips, or is `(port,
   status)` acceptable for Level 3? Current proposal: `(port, status)` for Level 3,
   documented clearly in the spec.
