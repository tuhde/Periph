# Feature Design: Connection — Interrupt Delivery and Power Management

**Status:** Draft  
**Branch:** `feature/interrupt-design`  
**Scope:** All languages × all platforms — `Connection` abstraction, interrupt delivery, power management, naming, spec template, AGENTS.md guidance

---

## 1. Problem Statement

Two independent gaps exist across the codebase:

### 1.1 Interrupt fragmentation

Interrupt support exists in several chip drivers (PCF8574, PCF8575, MCP23017) but the
design was shaped entirely by IO expanders. Many other chip categories also have INT
outputs with richer semantics:

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

### 1.2 No unified power management

There is currently no way to enable or disable a chip independently of whether it has a
hardware enable/powerdown pin or a software powerdown register. Applications may want to
gate a chip off temporarily (e.g., to save power, to sequence initialization, or to make
a bus scan safe) using a consistent API regardless of the underlying hardware mechanism.

---

## 2. Design Goals

1. **`Connection` as the unified chip handle** — rename and expand `Transport` into a
   `Connection` object that carries the bus transport, an optional INT pin, an optional
   enable pin, and a software enable gate. Chip drivers accept one `Connection` instead
   of separate transport + pin arguments.
2. **Chip-agnostic interrupt model** — the interrupt API applies equally to gyroscopes,
   RTCs, accelerometers, and IO expanders.
3. **Consistent naming** — one vocabulary adapted to each language's convention.
4. **Separated concerns** — INT-pin delivery (hardware IRQ vs. polling thread vs. epoll)
   is handled by a thin `InputPin` abstraction in the `Connection`; chip drivers never
   contain platform-specific interrupt code.
5. **Transparent power gating** — `Connection.enable()` / `disable()` drives the
   hardware EN pin if wired and gates all bus access; chip drivers require no changes to
   benefit.
6. **Configurable interrupt sources** — chips with multiple selectable interrupt
   conditions expose `enable_interrupt(source)` / `disable_interrupt(source)`; chips
   with a single fixed condition do not.
7. **Rust-safe** — Rust stays callback-free (`no_std`, no heap); `Connection` carries
   only the bus transport + enabled state; drivers expose only `poll_interrupt()`.
8. **Additive** — existing drivers are migrated; no chip loses functionality.
9. **Spec-first** — the spec template gains an `## Interrupt` section; AGENTS.md is
   updated with per-language guidance.

---

## 3. Vocabulary

### 3.1 Interrupt methods

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
callback argument. Usable without any callback — the only interrupt method Rust exposes.

**`enable_interrupt(source)` / `disable_interrupt(source)`** — only present on chips
with selectable interrupt sources (Level 2+). `source` is a chip-specific constant
(an enum or integer). Source-specific parameters (threshold value, duration, etc.) are
configured through chip-specific setter methods on `Full`, not through `enable_interrupt`.

### 3.2 Power management methods

These live on the chip driver and delegate to `Connection`:

| Concept | Unified name (snake_case) | Idiomatic forms |
|---------|--------------------------|-----------------|
| Turn chip on | `enable` | `enable` / `enable` |
| Turn chip off | `disable` | `disable` / `disable` |
| Query state | `is_enabled` | `is_enabled` / `isEnabled` |

**`enable()`** — resumes bus access; drives the hardware EN pin high (or asserts it,
respecting polarity) if one is wired.

**`disable()`** — gates all subsequent bus reads and writes (they silently become
no-ops returning zeros); drives the hardware EN pin low (or de-asserts it) if wired.

**`is_enabled()`** — returns the current software-gate state.

---

## 4. Connection Abstraction

`Connection` replaces `Transport` as the single object passed to every chip constructor.
It bundles three concerns:

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `bus` | platform bus transport | yes | I²C / SPI byte access |
| `int_pin` | `InputPin` | no | edge notifications from chip's INT line |
| `en_pin` | `OutputPin` | no | hardware enable / power control |

It also maintains an internal `enabled` boolean (default `true`). All `read` / `write`
operations on the bus check this flag; when `false` they are silently dropped (reads
return all-zero bytes).

**Chip drivers access the bus exclusively through `connection.read()` / `.write()`** —
they never call into the raw platform transport directly.

### 4.1 Python (`python/periph/transport/connection.py`)

```python
from dataclasses import dataclass, field
from .input_pin import InputPin
from .output_pin import OutputPin

@dataclass
class Connection:
    bus: object          # I2CTransport, SPITransport, etc.
    int_pin: InputPin | None = None
    en_pin: OutputPin | None = None
    _enabled: bool = field(default=True, init=False, repr=False)

    def enable(self):
        self._enabled = True
        if self.en_pin:
            self.en_pin.set(True)

    def disable(self):
        self._enabled = False
        if self.en_pin:
            self.en_pin.set(False)

    def is_enabled(self) -> bool:
        return self._enabled

    def read(self, reg: int, length: int) -> bytes:
        if not self._enabled:
            return bytes(length)
        return self.bus.read(reg, length)

    def write(self, reg: int, data: bytes | int) -> None:
        if not self._enabled:
            return
        self.bus.write(reg, data)
```

Usage:

```python
from periph.transport.i2c_linux import I2CLinux
from periph.transport.input_pin import LinuxSysfsPin
from periph.transport.output_pin import LinuxOutputPin
from periph.transport.connection import Connection
from periph.chips.imu.mpu6050 import Mpu6050Full

bus  = I2CLinux(bus=1, address=0x68)
conn = Connection(bus, int_pin=LinuxSysfsPin(17), en_pin=LinuxOutputPin(18))
imu  = Mpu6050Full(conn)

conn.disable()   # gates all I²C access; drives EN low if wired
conn.enable()    # resumes access; drives EN high if wired
```

Chip constructors:

```python
class Mpu6050Minimal:
    def __init__(self, connection: Connection):
        self._conn = connection

class Mpu6050Full(Mpu6050Minimal):
    def __init__(self, connection: Connection):
        super().__init__(connection)
        self._callback = None
        # int_pin wiring deferred until on_interrupt() is called
```

### 4.2 C++ (`cpp/src/transport/Connection.h`)

```cpp
#pragma once
#include "Transport.h"
#include "InputPin.h"
#include "OutputPin.h"
#include <cstdint>
#include <cstring>

class Connection {
public:
    Connection(Transport& bus,
               InputPin*   intPin = nullptr,
               OutputPin* enPin  = nullptr)
        : _bus(bus), _intPin(intPin), _enPin(enPin), _enabled(true) {}

    void enable() {
        _enabled = true;
        if (_enPin) _enPin->set(true);
    }

    void disable() {
        _enabled = false;
        if (_enPin) _enPin->set(false);
    }

    bool isEnabled() const { return _enabled; }

    InputPin*   intPin() const { return _intPin; }
    OutputPin* enPin()  const { return _enPin;  }

    void read(uint8_t reg, uint8_t* buf, size_t len) {
        if (!_enabled) { memset(buf, 0, len); return; }
        _bus.read(reg, buf, len);
    }

    void write(uint8_t reg, const uint8_t* data, size_t len) {
        if (!_enabled) return;
        _bus.write(reg, data, len);
    }

private:
    Transport& _bus;
    InputPin*   _intPin;
    OutputPin* _enPin;
    bool       _enabled;
};
```

Chip constructors:

```cpp
class Mpu6050Minimal {
public:
    explicit Mpu6050Minimal(Connection& conn) : _conn(conn) {}
protected:
    Connection& _conn;
};

class Mpu6050Full : public Mpu6050Minimal {
public:
    explicit Mpu6050Full(Connection& conn)
        : Mpu6050Minimal(conn), _callback(nullptr) {}
};
```

### 4.3 Node.js (`nodejs/packages/periph/src/transport/connection.js`)

```js
class Connection {
    /**
     * @param {object}    bus    I2CTransport or SPITransport instance
     * @param {InputPin?}  intPin optional interrupt input pin
     * @param {OutputPin?} enPin optional enable/power output pin
     */
    constructor(bus, intPin = null, enPin = null) {
        this._bus     = bus;
        this._intPin  = intPin;
        this._enPin   = enPin;
        this._enabled = true;
    }

    async enable() {
        this._enabled = true;
        if (this._enPin) await this._enPin.set(true);
    }

    async disable() {
        this._enabled = false;
        if (this._enPin) await this._enPin.set(false);
    }

    isEnabled() { return this._enabled; }

    get intPin() { return this._intPin; }

    async read(reg, length) {
        if (!this._enabled) return Buffer.alloc(length);
        return this._bus.read(reg, length);
    }

    async write(reg, data) {
        if (!this._enabled) return;
        return this._bus.write(reg, data);
    }
}

module.exports = { Connection };
```

### 4.4 Rust — simplified Connection

Rust targets `no_std` (ESP32-S3) and `std` (Linux) equally. Bundling `InputPin` and
`OutputPin` as generic type parameters would require 2–3 additional type parameters on
every chip struct, significantly increasing complexity with marginal gain given that Rust
is already callback-free for interrupts.

**In Rust, `Connection` wraps the bus transport + enabled state only.** INT pin and EN
pin are not part of `Connection`; callers manage them directly via `embedded_hal` traits.

```rust
pub struct Connection<BUS> {
    pub(crate) bus: BUS,
    enabled: bool,
}

impl<BUS> Connection<BUS>
where
    BUS: embedded_hal::i2c::I2c,
{
    pub fn new(bus: BUS) -> Self {
        Self { bus, enabled: true }
    }

    pub fn enable(&mut self)  { self.enabled = true;  }
    pub fn disable(&mut self) { self.enabled = false; }
    pub fn is_enabled(&self) -> bool { self.enabled }

    pub(crate) fn read(&mut self, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), BUS::Error> {
        if !self.enabled { buf.fill(0); return Ok(()); }
        self.bus.write_read(addr, &[reg], buf)
    }

    pub(crate) fn write(&mut self, addr: u8, reg: u8, data: &[u8]) -> Result<(), BUS::Error> {
        if !self.enabled { return Ok(()); }
        let mut buf = [0u8; 17];
        buf[0] = reg;
        buf[1..=data.len()].copy_from_slice(data);
        self.bus.write(addr, &buf[..=data.len()])
    }
}
```

For hardware EN pin control in Rust, callers use `embedded_hal::digital::OutputPin`
directly before constructing the chip:

```rust
en_pin.set_high().unwrap();    // power up the chip
let conn = Connection::new(i2c);
let imu  = Mpu6050Minimal::new(conn);
```

### 4.5 JVM (`jvm/periph-transport/…/transport/Connection.java`)

```java
public class Connection implements AutoCloseable {
    private final Transport bus;
    private final InputPin   intPin;   // nullable
    private final OutputPin enPin;    // nullable
    private volatile boolean enabled = true;

    public Connection(Transport bus) {
        this(bus, null, null);
    }

    public Connection(Transport bus, InputPin intPin, OutputPin enPin) {
        this.bus    = bus;
        this.intPin = intPin;
        this.enPin  = enPin;
    }

    public void enable()  { enabled = true;  if (enPin != null) enPin.set(true);  }
    public void disable() { enabled = false; if (enPin != null) enPin.set(false); }
    public boolean isEnabled() { return enabled; }

    public InputPin   intPin() { return intPin; }
    public OutputPin enPin()  { return enPin;  }

    public byte[] read(int reg, int length) {
        if (!enabled) return new byte[length];
        return bus.read(reg, length);
    }

    public void write(int reg, byte[] data) {
        if (!enabled) return;
        bus.write(reg, data);
    }

    @Override public void close() { bus.close(); }
}
```

---

## 5. InputPin — INT Line Delivery

`InputPin` is an input-only abstraction that delivers edge notifications from a chip's
INT line. It is intentionally minimal: it only signals that *an* edge occurred. The
chip driver always calls `poll_interrupt()` to determine the cause.

### 5.1 Python (`python/periph/transport/input_pin.py`)

```python
from abc import ABC, abstractmethod

class InputPin(ABC):
    RISING  = 1
    FALLING = 2
    CHANGE  = 3

    @abstractmethod
    def on_edge(self, handler, trigger=FALLING):
        """Register handler() for edge events. Called from IRQ or polling thread.
        handler takes no arguments; the chip driver calls poll_interrupt()."""

    @abstractmethod
    def off_edge(self):
        """Deregister handler and release resources."""
```

| Class | Platform | Mechanism |
|-------|----------|-----------|
| `MicroPythonPin` | MicroPython | `machine.Pin.irq()` |
| `CircuitPythonPin` | CircuitPython | `countio.Counter` or busy-wait |
| `LinuxPollingPin` | Linux (no GPIO hw) | 5 ms `threading.Thread` loop |
| `LinuxSysfsPin` | Linux (sysfs GPIO) | `select.select()` on `/sys/class/gpio/gpioN/value` |

### 5.2 C++ (`cpp/src/transport/InputPin.h`)

```cpp
class InputPin {
public:
    static constexpr uint8_t FALLING = 0;
    static constexpr uint8_t RISING  = 1;
    static constexpr uint8_t CHANGE  = 2;

    virtual void onEdge(void (*handler)(), uint8_t trigger = FALLING) = 0;
    virtual void offEdge() = 0;
    virtual ~InputPin() = default;
};
```

| Class | File | Platform | Mechanism |
|-------|------|----------|-----------|
| `ArduinoInputPin` | `InputPinArduino.h` | Arduino | `attachInterrupt(digitalPinToInterrupt(…))` |
| `LinuxInputPin` | `InputPinLinux.h` | Linux GCC | `poll()` thread on sysfs |
| `ZephyrInputPin` | `InputPinZephyr.h` | Zephyr | `gpio_add_callback()` |

### 5.3 Node.js (`nodejs/packages/periph/src/transport/input_pin.js`)

```js
class InputPin {
    async onEdge(callback, trigger = 'falling') { throw new Error('abstract'); }
    async offEdge() { throw new Error('abstract'); }
}
```

| Class | Mechanism |
|-------|-----------|
| `EpollInputPin` | `epoll` on sysfs or `gpiod` |
| `PollingInputPin` | 5 ms `setInterval` fallback |

### 5.4 Rust — no InputPin abstraction

Rust drivers expose only `poll_interrupt()`. The application registers a hardware ISR
via the HAL or RTOS and calls `poll_interrupt()` from within it.

### 5.5 JVM (`jvm/periph-transport/…/transport/InputPin.java`)

```java
@FunctionalInterface
public interface EdgeHandler { void onEdge(); }

public interface InputPin extends AutoCloseable {
    void onEdge(EdgeHandler handler, EdgeTrigger trigger);
    void offEdge();
}

public enum EdgeTrigger { RISING, FALLING, CHANGE }
```

| Class | Mechanism |
|-------|-----------|
| `Pi4JInputPin` | Pi4J `DigitalInput` listener (BCM pin numbering) |
| `PollingInputPin` | 5 ms `ScheduledExecutorService` |

---

## 6. OutputPin — Enable / Power Control

`OutputPin` is an output-only abstraction used by `Connection` to drive a chip's
hardware enable or power pin.

### 6.1 Python (`python/periph/transport/output_pin.py`)

```python
from abc import ABC, abstractmethod

class OutputPin(ABC):
    @abstractmethod
    def set(self, high: bool) -> None:
        """Drive the pin high (True) or low (False)."""
```

| Class | Platform | Mechanism |
|-------|----------|-----------|
| `MicroPythonOutputPin` | MicroPython | `machine.Pin(n, machine.Pin.OUT)` |
| `CircuitPythonOutputPin` | CircuitPython | `digitalio.DigitalInOut` |
| `LinuxOutputPin` | Linux | sysfs `/sys/class/gpio/gpioN/value` |

### 6.2 C++ (`cpp/src/transport/OutputPin.h`)

```cpp
class OutputPin {
public:
    virtual void set(bool high) = 0;
    virtual ~OutputPin() = default;
};
```

| Class | File | Platform | Mechanism |
|-------|------|----------|-----------|
| `ArduinoOutputPin` | `OutputPinArduino.h` | Arduino | `digitalWrite(pin, HIGH/LOW)` |
| `LinuxOutputPin` | `OutputPinLinux.h` | Linux GCC | sysfs GPIO |
| `ZephyrOutputPin` | `OutputPinZephyr.h` | Zephyr | `gpio_pin_set()` |

### 6.3 Node.js (`nodejs/packages/periph/src/transport/output_pin.js`)

```js
class OutputPin {
    async set(high) { throw new Error('abstract'); }
}
```

| Class | Mechanism |
|-------|-----------|
| `SysfsOutputPin` | sysfs GPIO write |
| `GpiodOutputPin` | `libgpiod` via native binding |

### 6.4 Rust

Callers use `embedded_hal::digital::OutputPin` directly (see §4.4). No wrapper needed.

### 6.5 JVM (`jvm/periph-transport/…/transport/OutputPin.java`)

```java
public interface OutputPin extends AutoCloseable {
    void set(boolean high);
}
```

| Class | Mechanism |
|-------|-----------|
| `Pi4JOutputPin` | Pi4J `DigitalOutput` (BCM pin numbering) |
| `SysfsOutputPin` | sysfs GPIO write |

---

## 7. Driver-Level Interrupt API

### 7.1 Core methods (all chips with INT output)

All languages implement the same three-method contract on `Full` drivers. Only
`poll_interrupt` is mandatory in Rust; the other two require a `InputPin` in `Connection`.

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

`on_interrupt` wires `connection.int_pin` to an internal handler that calls
`poll_interrupt()` and dispatches to the user callback. If `connection.int_pin` is
`None` / `nullptr` / `null`, `on_interrupt` starts a fallback polling timer (except
Rust — polling is always caller-managed).

### 7.2 Interrupt source configuration (chips with selectable sources)

Chips with selectable interrupt conditions expose two additional methods on `Full`:

| Method | Description |
|--------|-------------|
| `enable_interrupt(source)` | Allow *source* to assert INT |
| `disable_interrupt(source)` | Prevent *source* from asserting INT |

`source` is a chip-specific constant defined in a companion `<Chip>Source` class/enum:

```python
class Mpu6050Source:
    DATA_READY    = 0x01
    MOTION        = 0x40
    FIFO_OVERFLOW = 0x10
```

Source-specific parameters (thresholds, durations) are set via separate `Full` setter
methods, not through `enable_interrupt`.

### 7.3 Callback payload

The callback always receives a single integer:

| Chip type | Payload meaning |
|-----------|----------------|
| IO expander | Bitmask of input pins that changed (bit N = pin N) |
| Accelerometer / Gyroscope | Interrupt-status register bitmask |
| RTC | Alarm / timer flags |
| ADC | Conversion-complete or comparator flags |
| RFID | Event type (card detected, card removed, …) |

```python
def handler(status):
    if status & Mpu6050Source.DATA_READY:
        reading = imu.read()
    if status & Mpu6050Source.MOTION:
        alert("motion detected")

imu.on_interrupt(handler)
```

### 7.4 Delivery mechanism per platform

| Platform | Delivery | Notes |
|----------|----------|-------|
| MicroPython | Hardware IRQ via `InputPin.on_edge` | Handler runs in IRQ context — keep it short |
| CircuitPython | Same | |
| Python Linux (no GPIO) | 5 ms polling thread | Default when `int_pin=None` |
| Python Linux (sysfs) | `select()` on sysfs fd | Lower latency, opt-in |
| Arduino | Hardware IRQ via `ArduinoInputPin` | Handler runs in ISR — keep it short |
| Linux GCC | `poll()` thread | |
| Zephyr | `gpio_add_callback()` | |
| Node.js (epoll) | `EpollInputPin` | Requires native `epoll` dependency |
| Node.js (polling) | `PollingInputPin` | Fallback |
| Rust | None (user-managed) | Call `poll_interrupt()` from own ISR or polling loop |
| JVM (Pi4J) | `Pi4JInputPin` | BCM pin numbering |
| JVM (polling) | `PollingInputPin` via `ScheduledExecutorService` | Default |

---

## 8. Per-Pin API — IO Expanders Only

IO expander chips expose a virtual GPIO pin object (`Pin` / `IOExpanderPin`) per
physical pin. This section is specific to the `io_expander` category.

The per-pin API is a thin filter layer on top of `on_interrupt`. When the driver fires
the raw changed-pin bitmask, each pin checks its own bit and applies trigger-direction
filtering before dispatching to its registered handler.

### 8.1 Unified pin API

| Language | Subscribe | Unsubscribe | Trigger argument |
|----------|-----------|-------------|-----------------|
| Python | `pin.watch(handler, trigger=CHANGE)` | `pin.unwatch()` | `InputPin.RISING`, `.FALLING`, `.CHANGE` |
| C++ | `pin.watch(handler, mode)` | `pin.unwatch()` | `InputPin::RISING`, `::FALLING`, `::CHANGE` |
| Node.js | `pin.watch(callback, trigger='change')` | `pin.unwatch()` | `'rising'`, `'falling'`, `'change'` |
| JVM | `pin.watch(handler, EdgeTrigger.CHANGE)` | `pin.unwatch()` | `EdgeTrigger` enum |

Rust has no pin-level subscribe.

### 8.2 Trigger filtering

Each pin object maintains a previous-read shadow to detect direction. A `watch(handler,
FALLING)` call means the handler fires only when this pin transitions high → low.

### 8.3 Multiple handlers per pin

At most one handler per pin at a time. A second `watch()` call replaces the first
(log a debug-level warning).

---

## 9. Interrupt Capability Levels

| Level | Description | Extra methods |
|-------|-------------|---------------|
| **0** | No INT output | none |
| **1** | Single INT line; one fixed condition | `on_interrupt`, `off_interrupt`, `poll_interrupt` |
| **2** | Single INT line; multiple selectable conditions | adds `enable_interrupt(source)`, `disable_interrupt(source)` |
| **3** | Multiple independent INT lines | all Level-2 methods, indexed by line |

IO-expander per-pin `watch` / `unwatch` is an additional layer above Level 1 or 3.

### 9.1 Chips currently implemented

| Chip | Category | Level | Condition(s) |
|------|----------|-------|-------------|
| PCF8574 | io_expander | 1 | Any input pin changes |
| PCF8575 | io_expander | 1 | Any input pin changes |
| MCP23017 | io_expander | 3 | Any input pin changes per port (INTA / INTB) |

### 9.2 Expected future chips by level

| Level | Examples |
|-------|---------|
| 1 | Simple data-ready sensors (pressure, temperature, light, ToF) |
| 2 | IMUs, accelerometers, gyroscopes, RTCs |
| 3 | Chips with separate INT lines per function |

---

## 10. Comparison Across Languages and Platforms

### 10.1 Feature parity matrix

| Capability | Py MicroPy | Py CP | Py Linux | C++ Arduino | C++ Linux | C++ Zephyr | Node.js | Rust | JVM |
|-----------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `Connection` object | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓* | ✓ |
| `enable` / `disable` (software gate) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `en_pin` (hardware) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗† | ✓ |
| `on_interrupt` / `off_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |
| `poll_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `enable_interrupt` / `disable_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Hardware-edge delivery | ✓ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Polling-thread delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ |
| `epoll` / sysfs delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ |
| `pin.watch` / `unwatch` *(IO expanders)* | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |

✓ = supported after this feature, ✗ = not supported (by design)  
\* Rust `Connection` carries bus + enabled state only; no pin fields.  
† Rust callers drive the EN pin directly via `embedded_hal::digital::OutputPin`.

### 10.2 Why Rust Connection is bus-only

Bundling `int_pin` and `en_pin` as generic type parameters would add 2–3 type
parameters to every chip struct. `embedded_hal` traits are not object-safe, so
boxing requires `std` (unavailable on ESP32-S3). The existing Rust pattern already
omits callbacks for interrupts; keeping EN pin management caller-side is consistent
with that approach.

### 10.3 Why JVM defaults to polling for both InputPin and OutputPin

Pi4J requires explicit GPIO setup per host environment. The polling and sysfs defaults
work out of the box on any Raspberry Pi; Pi4J implementations are opt-in for
latency-sensitive use cases.

### 10.4 Delivery latency summary

| Delivery | Typical latency | Jitter |
|----------|----------------|--------|
| Hardware IRQ (MicroPython, Arduino, Zephyr) | < 10 µs | very low |
| epoll / sysfs (Linux GCC, Node.js) | < 1 ms | low |
| Polling thread 5 ms (Python Linux, JVM, Node.js fallback) | 0–5 ms | ±5 ms |

---

## 11. Spec Template Changes

### 11.1 Base template (`specs/_template_chip.md`)

Add an `## Interrupt` section after `## Pin Configuration`. Remove it for Level-0 chips.

```markdown
## Interrupt

| Property | Value |
|----------|-------|
| INT pin | active-low, open-drain — requires external pull-up |
| Level | 1 / 2 / 3 (see `specs/feature_connection_design.md`) |
| Condition(s) | e.g. data-ready; threshold exceeded; alarm |
| Clear mechanism | read status register / write clear bit |

### Interrupt sources
<!-- Only for Level 2/3. Delete for Level 1. -->

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

The `## Pin Configuration` section should also document the EN pin if the chip has one:

```markdown
| EN | active-high enable; float or drive high to power chip |
```

### 11.2 IO Expander template (`specs/_template_chip_io_expander.md`)

Replace the existing ad-hoc interrupt row with the full block from §11.1, plus:

```markdown
### Pin interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `watch` | `watch(handler, trigger=CHANGE)` | Subscribe to this pin's edge events |
| `unwatch` | `unwatch()` | Unsubscribe |
```

---

## 12. AGENTS.md Changes

Replace the scattered interrupt paragraphs with a unified block and add a `Connection`
construction section.

### 12.1 Connection construction

````markdown
## Connection (replaces Transport)

All chip constructors accept a single `Connection` object, which bundles the bus
transport, an optional INT pin (`InputPin`), and an optional EN pin (`OutputPin`).
See `specs/feature_connection_design.md` for the full design.

Construct the bus transport as before (unchanged), then wrap it:

**Python:**
```python
conn = Connection(bus, int_pin=LinuxSysfsPin(17), en_pin=LinuxOutputPin(18))
```

**C++:**
```cpp
Connection conn(bus, &gpioPin, &enPin);
```

**Node.js:**
```js
const conn = new Connection(bus, intPin, enPin);
```

**JVM:**
```java
Connection conn = new Connection(bus, gpioPin, enPin);
```

**Rust:**
```rust
let conn = Connection::new(i2c);  // bus only; manage pins directly
```

### Enable / disable

Call `conn.enable()` / `conn.disable()` to gate the chip.
When disabled, all reads return zeros and writes are silently dropped.
````

### 12.2 Interrupt support

````markdown
## Interrupt support

Interrupts are implemented in the `Full` driver class for all chips with an INT output.
See `specs/feature_connection_design.md` for design rationale and platform matrix.

### Vocabulary

| Concept | Method |
|---------|--------|
| Subscribe to INT assertions | `on_interrupt(callback)` |
| Unsubscribe | `off_interrupt()` |
| Read & clear status | `poll_interrupt() -> int` |
| Enable one interrupt source | `enable_interrupt(source)` — Level 2/3 only |
| Disable one interrupt source | `disable_interrupt(source)` — Level 2/3 only |
| Per-pin subscribe | `watch(handler, trigger)` — IO expanders only |
| Per-pin unsubscribe | `unwatch()` — IO expanders only |

Adapt capitalisation: snake_case for Python/Rust, camelCase for C++/JS/JVM.

### Per-language implementation rules

**Python (MicroPython / CircuitPython)**
`on_interrupt` calls `self._conn.int_pin.on_edge(self._int_handler, InputPin.FALLING)`.
`_int_handler` calls `poll_interrupt()` and dispatches to the stored callback.
If `self._conn.int_pin is None`, start a 5 ms polling `Thread` instead.
Keep the handler short — no I/O beyond the register read.

**Python (Linux)**
Expose `LinuxSysfsPin(gpio_num)` as opt-in for lower latency; default to
`LinuxPollingPin` (5 ms thread) when no `int_pin` is provided.

**C++**
Use `conn.intPin()` to access the `InputPin*`. Platform `#ifdef` guards belong
exclusively in `InputPinLinux.h` / `InputPinArduino.h` / `InputPinZephyr.h`.

**Node.js**
`onInterrupt` calls `this._conn.intPin.onEdge(…)`. `pollInterrupt` is `async`.

**Rust**
Full drivers expose only `poll_interrupt() -> Result<u8, E>`.
Document in the driver docstring: caller is responsible for wiring this into an ISR
or polling loop.

**JVM**
`onInterrupt(IntConsumer)` is the driver-level API.
Default `int_pin` to `new PollingInputPin(5)` when no `InputPin` is provided in the
`Connection`.

### Interrupt sources (Level 2/3 chips)

Define a companion `<Chip>Source` constants class/object/enum in the same file as the
driver. One constant per condition, values matching the chip's interrupt-status register
bit layout. Threshold values and other parameters are set via separate `Full` setter
methods.
````

---

## 13. Migration Plan for Existing Chip Drivers

### 13.1 PCF8574

| Change | Python | C++ | Node.js | Rust | JVM |
|--------|--------|-----|---------|------|-----|
| Constructor | `Connection` replaces `(transport, int_pin=None)` | `Connection&` replaces `(Transport&, InputPin*)` | `Connection` replaces `(transport, intPin)` | `Connection<I2C>` replaces `I2C` | `Connection` replaces `(Transport, InputPin)` |
| `configure_interrupt` → | `on_interrupt(cb)` | `onInterrupt(cb)` | `onInterrupt(cb)` | — | `onInterrupt(IntConsumer)` |
| `clear_interrupt` → | `poll_interrupt()` | `pollInterrupt()` | `pollInterrupt()` | `poll_interrupt()` | `pollInterrupt()` |
| `pin.irq` → | `pin.watch()` | `pin.watch()` | already `watch` | — | add `pin.watch()` |
| INT-pin delivery | Move to `Connection` / `InputPin` impl | Same | Same | N/A | Same |
| Platform guards | Remove from chip driver | Same | Same | N/A | Same |

No `enable_interrupt` / `disable_interrupt` — PCF8574 is Level 1.

Backward compatibility: no published packages yet; rename without deprecation shims.

### 13.2 PCF8575

Same changes as PCF8574.

### 13.3 MCP23017

Level 3 (two independent INT lines). Additional changes:

- `on_interrupt(callback)` — subscribes to both ports; callback receives `(port: int, status: int)`
- `on_interrupt(port, callback)` — single-port subscription
- `off_interrupt()` / `off_interrupt(port)` — symmetric
- `poll_interrupt(port)` — reads INTFA (port=0) or INTFB (port=1)

`enable_interrupt` / `disable_interrupt` not needed — interrupt-on-change applies to
entire ports, not selectable event types.

Pin-level `watch` / `unwatch` is unchanged in concept; INTA/INTB routing is internal.

---

## 14. New Files Summary

| File | Language | Purpose |
|------|----------|---------|
| `python/periph/transport/connection.py` | Python | `Connection` dataclass |
| `python/periph/transport/input_pin.py` | Python | `InputPin` ABC + `MicroPythonPin`, `CircuitPythonPin`, `LinuxPollingPin`, `LinuxSysfsPin` |
| `python/periph/transport/output_pin.py` | Python | `OutputPin` ABC + `MicroPythonOutputPin`, `CircuitPythonOutputPin`, `LinuxOutputPin` |
| `cpp/src/transport/Connection.h` | C++ | `Connection` class |
| `cpp/src/transport/InputPin.h` | C++ | `InputPin` base class |
| `cpp/src/transport/InputPinArduino.h` | C++ | `attachInterrupt` implementation |
| `cpp/src/transport/InputPinLinux.h` | C++ | `poll()` thread implementation |
| `cpp/src/transport/InputPinZephyr.h` | C++ | `gpio_add_callback` implementation |
| `cpp/src/transport/OutputPin.h` | C++ | `OutputPin` base class |
| `cpp/src/transport/OutputPinArduino.h` | C++ | `digitalWrite` implementation |
| `cpp/src/transport/OutputPinLinux.h` | C++ | sysfs GPIO implementation |
| `cpp/src/transport/OutputPinZephyr.h` | C++ | `gpio_pin_set` implementation |
| `nodejs/packages/periph/src/transport/connection.js` | Node.js | `Connection` class |
| `nodejs/packages/periph/src/transport/input_pin.js` | Node.js | `InputPin`, `EpollInputPin`, `PollingInputPin` |
| `nodejs/packages/periph/src/transport/output_pin.js` | Node.js | `OutputPin`, `SysfsOutputPin`, `GpiodOutputPin` |
| `rust/periph/src/transport/connection.rs` | Rust | `Connection<BUS>` struct (bus + enabled state) |
| `jvm/periph-transport/…/transport/Connection.java` | Java | `Connection` class |
| `jvm/periph-transport/…/transport/InputPin.java` | Java | `InputPin` interface + `EdgeTrigger` enum |
| `jvm/periph-transport/…/transport/PollingInputPin.java` | Java | 5 ms polling `InputPin` |
| `jvm/periph-transport/…/transport/Pi4JInputPin.java` | Java | Pi4J `DigitalInput` listener |
| `jvm/periph-transport/…/transport/OutputPin.java` | Java | `OutputPin` interface |
| `jvm/periph-transport/…/transport/SysfsOutputPin.java` | Java | sysfs GPIO write |
| `jvm/periph-transport/…/transport/Pi4JOutputPin.java` | Java | Pi4J `DigitalOutput` |

---

## 15. Open Questions

1. **C++ `pin.watch` vs. `pin.detachInterrupt`** — `detachInterrupt` is the Arduino
   convention. Current proposal uses `unwatch` to match Python/JS. Decision needed
   before implementation.

2. **Node.js `epoll` dependency** — `epoll` is Linux-only and requires native
   compilation. Optional peer dependency, or always bundled?

3. **Rust Linux host — polling thread** — Should a `std`-gated wrapper add polling-
   thread delivery for `poll_interrupt`? Out of scope for v1; revisit if requested.

4. **JVM pin numbering** — Pi4J uses BCM pin numbers. Document explicitly so users
   don't pass physical pin numbers.

5. **MCP23017 INTCAP register** — `INTCAP` latches pin state at interrupt time. Should
   `poll_interrupt` return both capture and flag register, or flags only? Proposal:
   return flags only; expose `read_capture(port)` separately.

6. **`enable_interrupt` arity** — single source per call vs. accepting a list. Proposal:
   single source for v1, revisit if performance matters.

7. **`Connection.disable()` behaviour** — currently silent (reads return zeros, writes
   dropped). An alternative is to raise a `ConnectionDisabledError`. Silent is safer
   for embedded loops; an exception aids debugging in host applications. Could be a
   constructor-time flag.

8. **Rust EN pin as `WithEnPin` wrapper** — a `WithEnPin<BUS, EN>` newtype around
   `Connection` would allow consistent `enable()` / `disable()` semantics in Rust
   without bloating every chip's type signature. Out of scope for v1.

9. **`Transport` rename sweep** — the rename from `Transport` to `Connection` touches
   every chip driver and every example. A mechanical find-and-replace is sufficient
   but must be applied consistently: `transport` (variable) → `connection`,
   `Transport` (type) → `Connection`.
