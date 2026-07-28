# Feature Design: Connection — Interrupt Delivery and Power Management

**Status:** Ready  
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

1. **`Connection` as the unified chip handle** — rename and expand `Transport` (the
   interface) into `Connection`, directly on the existing bus implementations
   (`I2CConnection`, `SPIConnection`, etc. — formerly `I2CTransport`, `SPITransport`).
   There is no separate wrapper object: the bus implementation itself carries an optional
   INT pin, an optional enable pin, and a software enable gate. Chip drivers accept one
   `Connection` instead of separate transport + pin arguments — and that `Connection` is
   the same object that talks to the bus, not a composed handle around it.
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

`Connection` replaces `Transport` — not as a new object wrapping the old one, but as the
renamed and expanded interface/base that every existing bus implementation
(`I2CTransport`, `SPITransport`, `HX711Transport`, etc.) implements directly.
Concretely: `I2CTransport` becomes `I2CConnection`, `SPITransport` becomes
`SPIConnection`, and so on for every protocol × platform pair. The object a chip driver
holds is the same object that talks to the bus — there is no `conn.bus` indirection.

`Connection` bundles three concerns directly on the concrete class:

| Concern | Held via | Purpose |
|---------|----------|---------|
| Bus access | the concrete class's own `read()` / `write()` / `write_read()` | I²C / SPI / etc. byte access, unchanged from today |
| `int_pin` | `InputPin`, optional | edge notifications from chip's INT line |
| `en_pin` | `OutputPin`, optional | hardware enable / power control |

Each concrete `Connection` also maintains an internal `enabled` boolean (default `true`).
`read` / `write` check this flag; when `false` they are silently dropped (reads return
all-zero bytes).

**Chip drivers access the bus exclusively through `connection.read()` / `.write()`**,
exactly as they call `transport.read()` / `.write()` today — nothing changes at the call
site beyond the parameter's name and type.

### 4.1 Package and class rename

This is a rename of every existing bus implementation, not new code living alongside the
old one. Two things move together, in every language:

1. **The package/module** — `transport` → `connection` (for JVM, the module itself is
   renamed; see below).
2. **Every concrete class** — `<Protocol>Transport` → `<Protocol>Connection`, per
   protocol, per platform variant. The abstract interface/base — `Transport` — becomes
   `Connection`.

| Language | Package/module rename | Interface/base rename |
|----------|------------------------|------------------------|
| Python | `python/periph/transport/` → `python/periph/connection/` | `Transport` (in `base.py`) → `Connection` |
| C++ | `cpp/src/transport/` → `cpp/src/connection/` | `Transport.h` → `Connection.h` (`class Transport` → `class Connection`) |
| Node.js | `nodejs/packages/periph/src/transport/` → `nodejs/packages/periph/src/connection/` | no previous base existed — see §4.4 |
| Rust | `rust/periph/src/transport/` → `rust/periph/src/connection/` (`lib.rs`: `pub mod transport;` → `pub mod connection;`) | no shared base — see §4.5 |
| JVM | `jvm/periph-transport/` → `jvm/periph-connection/`; `it.uhde.periph.transport` → `it.uhde.periph.connection` | `Transport.java` → `Connection.java` |
| Go | `go/periph/transport/` (`package transport`) → `go/periph/connection/` (`package connection`) | `type Transport interface` → `type Connection interface` |

Every concrete class follows the same pattern (shown for I²C; identical for SPI, SMBus,
UART, NeoPixel, HX711, DHTxx, and SiPo, and for every platform variant of each):

| Old | New |
|-----|-----|
| `I2CTransport` (Python, C++, Node.js, JVM) | `I2CConnection` |
| `I2CTransportLinux` / `I2CTransportESPIDF` / `I2CTransportPicoSDK` / `I2CTransportZephyr` (C++) | `I2CConnectionLinux` / `I2CConnectionESPIDF` / `I2CConnectionPicoSDK` / `I2CConnectionZephyr` |
| `I2CTransport` / `NewI2CTransport` (Go) | `I2CConnection` / `NewI2CConnection` |
| `HX711Transport`, `NeoPixelTransport`, `DHTxxTransportLinux`, `DHTxxTransportEsp32s3`, `SiPoTransport`, `SmBusTransport` (Rust) | `HX711Connection`, `NeoPixelConnection`, `DHTxxConnectionLinux`, `DHTxxConnectionEsp32s3`, `SiPoConnection`, `SmBusConnection` |

**Blast radius.** Python and Node.js chip drivers duck-type on the `connection`
parameter (call `.read()` / `.write()` without importing a concrete class) — merging
costs nothing beyond the `transport` → `connection` variable rename. C++, Go, and JVM
chip drivers name concrete classes statically, so this also touches every file that
references one: at last count, 26 C++ chip files, 28 Go, 157 JVM, and 5 Rust, plus the
matching share of the 678 example/test files across all languages. This is accepted as
a one-time mechanical sweep, consistent with §13.1's "no published packages yet — rename
without deprecation shims."

**Avoiding duplicated enable/disable/gating logic.** With no wrapper object, the
`enabled` flag, `int_pin` / `en_pin` storage, and the read/write gating must live
somewhere every concrete class can share, or it would be hand-duplicated into every one
of the ~50 protocol × platform implementations. Each language uses its own idiom for
this — shown in §4.2–§4.7:

| Language | Shared-behavior mechanism |
|----------|---------------------------|
| Python | `Connection` base class; concrete `read()` / `write()` renamed to protected `_read()` / `_write()`, called by non-abstract public `read()` / `write()` on the base (template method) |
| C++ | Same template-method split, in the `Connection` base class |
| Node.js | Same — `Connection` base class, single inheritance |
| JVM | `Connection` interface + `AbstractConnection` base class implementing the template method |
| Go | No inheritance — a small unexported `connectionBase` struct is embedded in every concrete struct, providing `Enable()` / `Disable()` / `IsEnabled()` |
| Rust | No inheritance — each of the 4 periph-owned custom-protocol structs (`HX711Connection`, `NeoPixelConnection`, `DHTxxConnection*`, `SiPoConnection`) carries its own `enabled: bool` and repeats the same two-line gate in `read()` / `write()`, exactly as `Connection<BUS>` already did before this decision (see §4.5) — too small (4 structs) to warrant a shared trait |

### 4.2 Python (`python/periph/connection/base.py`, was `transport/base.py`)

```python
from abc import ABC, abstractmethod
from .input_pin import InputPin
from .output_pin import OutputPin

class Connection(ABC):
    """Base class for all bus connections. One instance represents one device
    on a bus, plus its optional INT pin, EN pin, and software enable gate."""

    def __init__(self, int_pin: InputPin | None = None, en_pin: OutputPin | None = None):
        self.int_pin = int_pin
        self.en_pin = en_pin
        self._enabled = True

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

    def read(self, n: int) -> bytes:
        if not self._enabled:
            return bytes(n)
        return self._read(n)

    def write(self, data: bytes) -> None:
        if not self._enabled:
            return
        self._write(data)

    def write_read(self, data: bytes, n: int) -> bytes:
        if not self._enabled:
            return bytes(n)
        return self._write_read(data, n)

    @abstractmethod
    def _read(self, n: int) -> bytes: ...
    @abstractmethod
    def _write(self, data: bytes) -> None: ...
    @abstractmethod
    def _write_read(self, data: bytes, n: int) -> bytes: ...

    def close(self) -> None:
        """Release any resources held by this connection. No-op by default."""
```

Concrete example (`python/periph/connection/i2c_linux.py`, was `transport/i2c_linux.py`):

```python
from smbus2 import SMBus, i2c_msg
from .base import Connection

class I2CConnection(Connection):     # was: class I2CTransport(Transport)
    def __init__(self, bus, addr, int_pin=None, en_pin=None):
        super().__init__(int_pin, en_pin)
        if isinstance(bus, int):
            self._bus = SMBus(bus)
            self._owns_bus = True
        else:
            self._bus = bus
            self._owns_bus = False
        self._addr = addr

    def _write(self, data):              # was: def write(self, data)
        self._bus.i2c_rdwr(i2c_msg.write(self._addr, list(data)))

    def _read(self, n):                  # was: def read(self, n)
        ...
```

Usage:

```python
from periph.connection.i2c_linux import I2CConnection
from periph.connection.input_pin import LinuxSysfsPin
from periph.connection.output_pin import LinuxOutputPin
from periph.chips.imu.mpu6050 import Mpu6050Full

conn = I2CConnection(bus=1, addr=0x68, int_pin=LinuxSysfsPin(17), en_pin=LinuxOutputPin(18))
imu  = Mpu6050Full(conn)

conn.disable()   # gates all I²C access; drives EN low if wired
conn.enable()    # resumes access; drives EN high if wired
```

Chip constructors are unchanged in shape — only the parameter name/type moves:

```python
class Mpu6050Minimal:
    def __init__(self, connection: Connection):   # was: transport: Transport
        self._conn = connection

class Mpu6050Full(Mpu6050Minimal):
    def __init__(self, connection: Connection):
        super().__init__(connection)
        self._callback = None
        # int_pin wiring deferred until on_interrupt() is called
```

### 4.3 C++ (`cpp/src/connection/Connection.h`, was `transport/Transport.h`)

```cpp
#pragma once
#include "InputPin.h"
#include "OutputPin.h"
#include <cstdint>
#include <cstring>

class Connection {
public:
    virtual ~Connection() = default;

    explicit Connection(InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : _intPin(intPin), _enPin(enPin), _enabled(true) {}

    void enable() {
        _enabled = true;
        if (_enPin) _enPin->set(true);
    }

    void disable() {
        _enabled = false;
        if (_enPin) _enPin->set(false);
    }

    bool isEnabled() const { return _enabled; }
    InputPin*  intPin() const { return _intPin; }
    OutputPin* enPin()  const { return _enPin;  }

    void read(uint8_t* buf, size_t len) {
        if (!_enabled) { memset(buf, 0, len); return; }
        _read(buf, len);
    }

    void write(const uint8_t* data, size_t len) {
        if (!_enabled) return;
        _write(data, len);
    }

    void write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) {
        if (!_enabled) { memset(buf, 0, buf_len); return; }
        _write_read(data, data_len, buf, buf_len);
    }

protected:
    virtual void _read(uint8_t* buf, size_t len) = 0;
    virtual void _write(const uint8_t* data, size_t len) = 0;
    virtual void _write_read(const uint8_t* data, size_t data_len,
                              uint8_t* buf, size_t buf_len) = 0;

private:
    InputPin*  _intPin;
    OutputPin* _enPin;
    bool       _enabled;
};
```

Concrete example (`cpp/src/connection/I2CConnection.h`, was `transport/I2CTransport.h`):

```cpp
#pragma once
#include <Wire.h>
#include "Connection.h"

class I2CConnection : public Connection {     // was: class I2CTransport : public Transport
public:
    I2CConnection(TwoWire& bus, uint8_t addr, InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _bus(bus), _addr(addr) {}

protected:
    void _read(uint8_t* buf, size_t len) override;         // was: public read() override
    void _write(const uint8_t* data, size_t len) override; // was: public write() override
    void _write_read(const uint8_t* data, size_t data_len,
                      uint8_t* buf, size_t buf_len) override;

private:
    TwoWire& _bus;
    uint8_t  _addr;
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

### 4.4 Node.js (`nodejs/packages/periph/src/connection/`)

JavaScript's existing transport classes never had a shared base — each (`I2CTransport`,
`SPITransport`, etc.) stood alone, relying on duck typing. This design adds one:
`connection.js` (new file, no previous equivalent) provides `Connection`, which every
renamed concrete class now extends.

```js
// connection.js — new file
class Connection {
    constructor(intPin = null, enPin = null) {
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

    async read(length) {
        if (!this._enabled) return Buffer.alloc(length);
        return this._read(length);
    }

    async write(data) {
        if (!this._enabled) return;
        return this._write(data);
    }
}

module.exports = { Connection };
```

Concrete example (`i2c.js`, was `I2CTransport` → now `I2CConnection`):

```js
const { Connection } = require('./connection');
const i2c = require('i2c-bus');

class I2CConnection extends Connection {       // was: class I2CTransport
    constructor(busNumber, addr, intPin = null, enPin = null) {
        super(intPin, enPin);
        this._bus  = i2c.openSync(busNumber);
        this._addr = addr;
    }

    async _write(data) { this._bus.i2cWriteSync(this._addr, data.length, data); }  // was: write()
    async _read(n) {                                                              // was: read()
        const buf = Buffer.alloc(n);
        this._bus.i2cReadSync(this._addr, n, buf);
        return buf;
    }
}

module.exports = { I2CConnection };
```

### 4.5 Rust — no shared base (`rust/periph/src/connection/`)

Two different situations, unchanged in kind from the original design — only the
periph-owned custom-protocol structs are renamed:

**I²C / SPI (embedded-hal-covered buses)** — Rust never had a periph-owned `I2CTransport`
struct; chip drivers are generic directly over `embedded_hal::i2c::I2c` /
`embedded_hal::spi::SpiBus`. The small `Connection<BUS>` wrapper this design already adds
around that generic bus (§2 goal 7) is new code, not a rename — nothing here changes:

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

**HX711, NeoPixel, DHTxx, SiPo (periph-owned custom protocols)** — these *do* have
periph-owned structs today (`HX711Transport`, `NeoPixelTransport`, `DHTxxTransportLinux`
/ `DHTxxTransportEsp32s3`, `SiPoTransport`) and get renamed to `*Connection`. Rust has no
implementation inheritance, so each struct carries its own `enabled: bool` and repeats
the gate inline — four structs, not worth a shared trait:

**SMBus (also periph-owned, omitted above by oversight)** — `SmBusTransport` wraps a
generic `I2c` bus and itself implements `I2c`, so chip drivers generic over `I2c` accept
it transparently; it is architecturally a bus-enhancer like `Connection<BUS>`, not a
chip-facing custom-protocol struct like the four above. It is renamed to `SmBusConnection`
for naming consistency with every other language's SMBus wrapper, but does **not** gain
`enabled`/`enable()`/`disable()` — that role doesn't fit its "transparent `I2c` passthrough"
position, and a caller who wants software gating on an SMBus-backed connection can already
get it via `Connection<SmBusConnection<I2C>>`.

```rust
pub struct HX711Connection<DI, CK> {   // was: struct HX711Transport<DI, CK>
    dout: DI,
    pd_sck: CK,
    enabled: bool,
}

impl<DI, CK> HX711Connection<DI, CK>
where
    DI: embedded_hal::digital::InputPin,
    CK: embedded_hal::digital::OutputPin,
{
    pub fn enable(&mut self)  { self.enabled = true; }
    pub fn disable(&mut self) { self.enabled = false; }
    pub fn is_enabled(&self) -> bool { self.enabled }

    pub fn read(&mut self) -> Result<i32, Error> {
        if !self.enabled { return Ok(0); }
        self.read_raw()   // existing bit-bang implementation, unchanged
    }
}
```

### 4.6 JVM (`jvm/periph-connection/…/connection/Connection.java`, was `jvm/periph-transport/…/transport/Transport.java`)

The Maven module itself is renamed — `periph-transport` → `periph-connection` — since
every class it contains becomes a `*Connection`; keeping the old artifact name would be
just as wrong as filing `Connection` inside a package still called `transport`.

```java
package it.uhde.periph.connection;

public interface Connection extends AutoCloseable {
    void enable();
    void disable();
    boolean isEnabled();
    InputPin  intPin();  // nullable
    OutputPin enPin();   // nullable

    byte[] read(int n);
    void   write(byte[] data);
    byte[] writeRead(byte[] data, int n);
}
```

```java
package it.uhde.periph.connection;

public abstract class AbstractConnection implements Connection {
    private final InputPin  intPin;
    private final OutputPin enPin;
    private volatile boolean enabled = true;

    protected AbstractConnection(InputPin intPin, OutputPin enPin) {
        this.intPin = intPin;
        this.enPin  = enPin;
    }

    @Override public void enable()  { enabled = true;  if (enPin != null) enPin.set(true);  }
    @Override public void disable() { enabled = false; if (enPin != null) enPin.set(false); }
    @Override public boolean isEnabled() { return enabled; }
    @Override public InputPin  intPin() { return intPin; }
    @Override public OutputPin enPin()  { return enPin;  }

    @Override public byte[] read(int n) {
        if (!enabled) return new byte[n];
        return _read(n);
    }

    @Override public void write(byte[] data) {
        if (!enabled) return;
        _write(data);
    }

    protected abstract byte[] _read(int n);
    protected abstract void   _write(byte[] data);
}
```

Concrete example (`I2CConnection.java`, was `I2CTransport.java`):

```java
package it.uhde.periph.connection;

public final class I2CConnection extends AbstractConnection {   // was: implements Transport
    public I2CConnection(int bus, int addr, InputPin intPin, OutputPin enPin) {
        super(intPin, enPin);
        // ... open /dev/i2c-<bus> via FFM, as today
    }

    @Override protected byte[] _read(int n) { /* unchanged FFM read */ }
    @Override protected void   _write(byte[] data) { /* unchanged FFM write */ }
}
```

`periph-java`, `periph-kotlin`, and `periph-groovy` update their dependency coordinates
(`periph-transport` → `periph-connection`) and every `import it.uhde.periph.transport.*`
to `import it.uhde.periph.connection.*` — this is the bulk of the 157-file count in §4.1.

### 4.7 Go (`go/periph/connection/connection.go`, was `transport/transport.go`)

Go has no implementation inheritance; a small unexported `connectionBase` struct is
embedded in every concrete connection struct to share the `Enable` / `Disable` /
`IsEnabled` implementation without duplicating it:

```go
package connection

type Connection interface {    // was: type Transport interface
    Write(data []byte) error
    Read(n int) ([]byte, error)
    WriteRead(data []byte, n int) ([]byte, error)
    Close() error
    Enable()
    Disable()
    IsEnabled() bool
}

// connectionBase is embedded by every concrete Connection implementation to
// share enable/disable/pin state without repeating it in each one.
type connectionBase struct {
    IntPin   InputPin  // nil if unused
    EnPin    OutputPin // nil if unused
    disabled bool       // zero value = enabled
}

func (b *connectionBase) Enable() {
    b.disabled = false
    if b.EnPin != nil {
        b.EnPin.Set(true)
    }
}

func (b *connectionBase) Disable() {
    b.disabled = true
    if b.EnPin != nil {
        b.EnPin.Set(false)
    }
}

func (b *connectionBase) IsEnabled() bool { return !b.disabled }
```

Concrete example (`i2c_linux.go`, was `I2CTransport` / `NewI2CTransport`):

```go
type I2CConnection struct {    // was: type I2CTransport struct
    connectionBase
    fd   int
    addr uint8
}

func NewI2CConnection(bus int, addr uint8, intPin InputPin, enPin OutputPin) (*I2CConnection, error) {
    // was: func NewI2CTransport(bus int, addr uint8) (*I2CTransport, error)
    c := &I2CConnection{connectionBase: connectionBase{IntPin: intPin, EnPin: enPin}, addr: addr}
    // ... open /dev/i2c-<bus>, as today
    return c, nil
}

func (c *I2CConnection) Read(n int) ([]byte, error) {
    if !c.IsEnabled() {
        return make([]byte, n), nil
    }
    return c.readRaw(n)   // existing ioctl implementation, unchanged
}
```

Usage:

```go
conn, err := connection.NewI2CConnection(1, 0x68, intPin, enPin)
chip, err := mpu6050.NewMpu6050Full(conn)
```

Chip constructors: `New<Chip>Minimal(conn connection.Connection, ...) (*<Chip>Minimal, error)`,
same as today except `conn connection.Connection` replaces `t transport.Transport`.

### 4.8 Shared InputPin (wired-AND INT lines)

When multiple chips share one physical INT GPIO (open-drain outputs wired together),
pass the same `InputPin` instance to each chip's `Connection`. Each chip driver calls
`on_interrupt()` independently and registers its own internal handler; all handlers are
called on every edge, and each reads its own chip's interrupt-status register.

```python
int_pin  = LinuxSysfsPin(17)                                    # one physical GPIO
imu_conn = I2CConnection(bus=1, addr=0x68, int_pin=int_pin)
rtc_conn = I2CConnection(bus=1, addr=0x51, int_pin=int_pin)

imu.on_interrupt(lambda s: handle_imu(s))
rtc.on_interrupt(lambda s: handle_rtc(s))
# Both handlers are now registered on int_pin; firing it polls both chips.
```

---

## 5. InputPin — INT Line Delivery

`InputPin` is an input-only abstraction that delivers edge notifications from a chip's
INT line. It is intentionally minimal: it only signals that *an* edge occurred. The
chip driver always calls `poll_interrupt()` to determine the cause.

**Multiple handlers are supported.** `on_edge` appends to an ordered list; `off_edge`
removes one specific handler by identity. This enables the common hardware pattern of
multiple chips with open-drain INT outputs wired to a single GPIO: each chip's
`Connection` holds the same `InputPin` instance and registers its own internal handler.
When the edge fires, all handlers are called in registration order; each then calls
`poll_interrupt()` on its own chip to identify the source.

### 5.1 Python (`python/periph/connection/input_pin.py`)

```python
from abc import ABC, abstractmethod

class InputPin(ABC):
    RISING  = 1
    FALLING = 2
    CHANGE  = 3

    @abstractmethod
    def on_edge(self, handler, trigger=FALLING):
        """Append handler() to the edge-notification list for the given trigger.
        handler takes no arguments; the chip driver calls poll_interrupt().
        Multiple handlers may be registered; all are called in registration order."""

    @abstractmethod
    def off_edge(self, handler):
        """Remove a specific handler from the list. No-op if not registered."""
```

| Class | Platform | Mechanism |
|-------|----------|-----------|
| `MicroPythonPin` | MicroPython | `machine.Pin.irq()` |
| `CircuitPythonPin` | CircuitPython | `countio.Counter` or busy-wait |
| `LinuxPollingPin` | Linux (no GPIO hw) | 5 ms `threading.Thread` loop |
| `LinuxSysfsPin` | Linux (sysfs GPIO) | `select.select()` on `/sys/class/gpio/gpioN/value` |

### 5.2 C++ (`cpp/src/connection/InputPin.h`)

```cpp
class InputPin {
public:
    // Named kFalling/kRising/kChange, not FALLING/RISING/CHANGE: those bare
    // names are #defined as macros by <Arduino.h> (and other vendor GPIO
    // headers), which would corrupt every qualified InputPin::FALLING
    // reference via raw token substitution before the compiler ever sees
    // the "::". kFalling/kRising/kChange do not collide.
    static constexpr uint8_t kFalling = 0;
    static constexpr uint8_t kRising  = 1;
    static constexpr uint8_t kChange  = 2;

    virtual void onEdge(void (*handler)(), uint8_t trigger = kFalling) = 0;
    virtual void offEdge(void (*handler)()) = 0;
    virtual ~InputPin() = default;
};
```

| Class | File | Platform | Mechanism |
|-------|------|----------|-----------|
| `InputPinArduino` | `InputPinArduino.h` | Arduino | `attachInterrupt(digitalPinToInterrupt(…))` |
| `InputPinLinux` | `InputPinLinux.h` | Linux GCC | `poll()` thread on sysfs |
| `InputPinZephyr` | `InputPinZephyr.h` | Zephyr | `gpio_add_callback()` |
| `InputPinESPIDF` | `InputPinESPIDF.h` | ESP-IDF | `gpio_install_isr_service()` + `gpio_isr_handler_add()` |
| `InputPinPicoSDK` | `InputPinPicoSDK.h` | Raspberry Pi Pico SDK | `gpio_set_irq_enabled_with_callback()` |

Class names follow the same `<Base>ESPIDF` / `<Base>PicoSDK` suffix convention already used by
every C++ transport (`I2CTransportESPIDF`, `I2CTransportPicoSDK`, …).

### 5.3 Node.js (`nodejs/packages/periph/src/connection/input_pin.js`)

```js
class InputPin {
    async onEdge(callback, trigger = 'falling') { throw new Error('abstract'); }
    async offEdge(callback) { throw new Error('abstract'); }
}
```

| Class | Mechanism |
|-------|-----------|
| `EpollInputPin` | `epoll` on sysfs or `gpiod` |
| `PollingInputPin` | 5 ms `setInterval` fallback |

### 5.4 Rust — no InputPin abstraction

Rust drivers expose only `poll_interrupt()`. The application registers a hardware ISR
via the HAL or RTOS and calls `poll_interrupt()` from within it.

### 5.5 JVM (`jvm/periph-connection/…/connection/InputPin.java`)

```java
@FunctionalInterface
public interface EdgeHandler { void onEdge(); }

public interface InputPin extends AutoCloseable {
    void onEdge(EdgeHandler handler, EdgeTrigger trigger);
    void offEdge(EdgeHandler handler);
}

public enum EdgeTrigger { RISING, FALLING, CHANGE }
```

| Class | Mechanism |
|-------|-----------|
| `SysfsInputPin` | sysfs GPIO input (`/sys/class/gpio/gpioN/value`) |
| `PollingInputPin` | 5 ms `ScheduledExecutorService` (default) |

### 5.6 Go (`go/periph/connection/input_pin.go`)

Go function values are not comparable, so there is no way to implement `off_edge(handler)`
by matching a stored handler against the one passed in — the pattern every other language
uses. `OnEdge` instead returns an unsubscribe closure directly, which is the idiomatic Go
equivalent (the same shape as `context.CancelFunc`):

```go
package connection

type Trigger int

const (
    Rising Trigger = iota
    Falling
    Change
)

// InputPin delivers edge notifications from a chip's INT line.
type InputPin interface {
    // OnEdge registers handler to run on each edge matching trigger and
    // returns a function that removes it. Multiple handlers may be
    // registered concurrently — e.g. several chips sharing one INT line.
    OnEdge(trigger Trigger, handler func()) (unsubscribe func())
}
```

| Type | File | Build tag | Platform | Mechanism |
|------|------|-----------|----------|-----------|
| `GpioInputPin` | `input_pin_linux.go` | `linux && !tinygo` | Go Linux | `/dev/gpiochip*` edge-event ioctl |
| `GpioInputPin` | `input_pin_tinygo.go` | `tinygo` | Go TinyGo | `machine.Pin.SetInterrupt` |
| `PollingInputPin` | `input_pin_polling.go` | none | Go Linux (no GPIO) | ticker-driven goroutine, 5 ms default |

`GpioInputPin` exports the same type name on both platforms, gated by build tag — the same
pattern every Go connection implementation already uses (see AGENTS.md § Connection
interface). Only an example's `main()` ever names the concrete type; chip drivers see
`connection.InputPin` only.

---

## 6. OutputPin — Enable / Power Control

`OutputPin` is an output-only abstraction used by `Connection` to drive a chip's
hardware enable or power pin.

### 6.1 Python (`python/periph/connection/output_pin.py`)

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

### 6.2 C++ (`cpp/src/connection/OutputPin.h`)

```cpp
class OutputPin {
public:
    virtual void set(bool high) = 0;
    virtual ~OutputPin() = default;
};
```

| Class | File | Platform | Mechanism |
|-------|------|----------|-----------|
| `OutputPinArduino` | `OutputPinArduino.h` | Arduino | `digitalWrite(pin, HIGH/LOW)` |
| `OutputPinLinux` | `OutputPinLinux.h` | Linux GCC | sysfs GPIO |
| `OutputPinZephyr` | `OutputPinZephyr.h` | Zephyr | `gpio_pin_set()` |
| `OutputPinESPIDF` | `OutputPinESPIDF.h` | ESP-IDF | `gpio_set_level()` |
| `OutputPinPicoSDK` | `OutputPinPicoSDK.h` | Raspberry Pi Pico SDK | `gpio_put()` |

### 6.3 Node.js (`nodejs/packages/periph/src/connection/output_pin.js`)

```js
class OutputPin {
    async set(high) { throw new Error('abstract'); }
}
```

| Class | Mechanism |
|-------|-----------|
| `GpioOutputPin` | wraps an `opengpio` Output (`libgpiod` character device) |

### 6.4 Rust

Callers use `embedded_hal::digital::OutputPin` directly (see §4.5). No wrapper needed.

### 6.5 JVM (`jvm/periph-connection/…/connection/OutputPin.java`)

```java
public interface OutputPin extends AutoCloseable {
    void set(boolean high);
}
```

| Class | Mechanism |
|-------|-----------|
| `SysfsOutputPin` | sysfs GPIO output (`/sys/class/gpio/gpioN/value`) |

### 6.6 Go (`go/periph/connection/output_pin.go`)

```go
package connection

// OutputPin drives a chip's hardware enable or power pin.
type OutputPin interface {
    Set(high bool) error
}
```

| Type | File | Build tag | Platform | Mechanism |
|------|------|-----------|----------|-----------|
| `GpioOutputPin` | `output_pin_linux.go` | `linux && !tinygo` | Go Linux | `/dev/gpiochip*` `SetValue` |
| `GpioOutputPin` | `output_pin_tinygo.go` | `tinygo` | Go TinyGo | `machine.Pin.Set` (configured `machine.PinOutput`) |

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
| Go | `OnInterrupt(cb) error` | `OffInterrupt() error` | `PollInterrupt() (uint8, error)` | `func(uint8)` |

`on_interrupt` wires `connection.int_pin` to an internal handler that calls
`poll_interrupt()` and dispatches to the user callback. If `connection.int_pin` is
`None` / `nullptr` / `null` / `nil`, `on_interrupt` starts a fallback polling timer
(except Rust — polling is always caller-managed). In Go, `OnInterrupt` calls
`IntPin.OnEdge` internally and stores the returned unsubscribe closure so `OffInterrupt`
has something to call.

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
| Arduino | Hardware IRQ via `InputPinArduino` | Handler runs in ISR — keep it short |
| Linux GCC | `poll()` thread | |
| Zephyr | `gpio_add_callback()` | |
| ESP-IDF | Hardware IRQ via `InputPinESPIDF` | `gpio_install_isr_service()` + `gpio_isr_handler_add()` |
| Pico SDK | Hardware IRQ via `InputPinPicoSDK` | `gpio_set_irq_enabled_with_callback()` |
| Node.js (epoll) | `EpollInputPin` | Requires native `epoll` dependency |
| Node.js (polling) | `PollingInputPin` | Fallback |
| Rust | None (user-managed) | Call `poll_interrupt()` from own ISR or polling loop |
| JVM (sysfs) | `SysfsInputPin` | sysfs GPIO, requires prior `gpio export N` |
| JVM (polling) | `PollingInputPin` via `ScheduledExecutorService` | Default |
| Go Linux (gpiochip) | `GpioInputPin` | `/dev/gpiochip*` edge-event ioctl |
| Go Linux (polling) | `PollingInputPin` | Fallback when no GPIO chip line is wired |
| Go TinyGo | Hardware IRQ via `GpioInputPin` | `machine.Pin.SetInterrupt()` |

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
| C++ | `pin.watch(handler, mode)` | `pin.unwatch()` | `InputPin::kRising`, `::kFalling`, `::kChange` |
| Node.js | `pin.watch(callback, trigger='change')` | `pin.unwatch()` | `'rising'`, `'falling'`, `'change'` |
| JVM | `pin.watch(handler, EdgeTrigger.CHANGE)` | `pin.unwatch()` | `EdgeTrigger` enum |
| Go | `pin.Watch(trigger, handler) error` | `pin.Unwatch() error` | `connection.Rising`, `.Falling`, `.Change` |

Rust has no pin-level subscribe.

Go's existing IO-expander guidance (AGENTS.md § IO Expander drivers → Go) names this method
`WatchInterrupt`; rename it to `Watch` to match the unified vocabulary above.

### 8.2 Trigger filtering

Each pin object maintains a previous-read shadow to detect direction. A `watch(handler,
FALLING)` call means the handler fires only when this pin transitions high → low.

### 8.3 Multiple handlers per pin

At most one handler per pin at a time. A second `watch()` call replaces the first
(log a debug-level warning).

### 8.4 OutputPin composability

An output-configured IO expander pin satisfies the `OutputPin` interface (§6) and can be
passed directly as `en_pin` to another chip's `Connection`. This enables cascaded power
control where one IO expander output gates an entire sensor's bus access.

```python
# Python example — IO expander pin as en_pin for a downstream chip
expander = PCF8574Minimal(I2CConnection(bus=1, addr=0x20))
en = expander.pin(0)
en.init(Pin.OUT)                     # configure as output
sensor_conn = I2CConnection(bus=1, addr=0x68, en_pin=en)
sensor_conn.enable()                 # drives IO expander pin 0 high
```

| Language | Mechanism |
|----------|-----------|
| Python | `_Pin` has `set(high: bool)` → `on()` / `off()`; duck-types as `OutputPin` on all targets |
| C++ | `IOExpanderPin` inherits from `OutputPin`; implements `set(bool high)` → `high()` / `low()` |
| Node.js | `_Pin.write(value)` is async (Connection is async everywhere), so it cannot duck-type `OutputPin.set()` directly; call `pin.asGpio()` for a synchronous `{value, direction, stop()}` facade and wrap that in a `GpioOutputPin` |
| JVM | `Pin` implements `OutputPin`; `set(boolean high)` → delegates to write |
| Rust | `ExPin<Output>` implements `embedded_hal::digital::OutputPin` — already compatible; no extra work |
| Go | `Pin.Set(high bool) error` already matches `OutputPin`'s method set structurally — already compatible; no extra work |

The IO expander must be initialised and its `Connection` enabled before the downstream
chip's `Connection.enable()` is called, since the EN pin write goes over the expander's
bus.

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

| Capability | Py MicroPy | Py CP | Py Linux | C++ Arduino | C++ Linux | C++ Zephyr | C++ ESP-IDF | C++ Pico SDK | Node.js | Rust | JVM | Go Linux | Go TinyGo |
|-----------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `Connection` object | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓* | ✓ | ✓ | ✓ |
| `enable` / `disable` (software gate) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `en_pin` (hardware) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗† | ✓ | ✓ | ✓ |
| `on_interrupt` / `off_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ |
| `poll_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `enable_interrupt` / `disable_interrupt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Hardware-edge delivery | ✓ | ✓ | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| Polling-thread delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ |
| `epoll` / sysfs / gpiochip delivery | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ | ✗ |
| `pin.watch` / `unwatch` *(IO expanders)* | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ |

✓ = supported after this feature, ✗ = not supported (by design)  
\* Rust `Connection` carries bus + enabled state only; no pin fields.  
† Rust callers drive the EN pin directly via `embedded_hal::digital::OutputPin`.

### 10.2 Why Rust Connection is bus-only

Bundling `int_pin` and `en_pin` as generic type parameters would add 2–3 type
parameters to every chip struct. `embedded_hal` traits are not object-safe, so
boxing requires `std` (unavailable on ESP32-S3). The existing Rust pattern already
omits callbacks for interrupts; keeping EN pin management caller-side is consistent
with that approach.

### 10.3 Why JVM defaults to polling for InputPin

Sysfs GPIO access requires the pin to be exported first (`gpio export N` or equivalent).
`PollingInputPin` (5 ms `ScheduledExecutorService`) works out of the box on any
Raspberry Pi without any prior GPIO setup; `SysfsInputPin` is opt-in for lower latency
when the INT line is wired and exported.

### 10.4 Delivery latency summary

| Delivery | Typical latency | Jitter |
|----------|----------------|--------|
| Hardware IRQ (MicroPython, Arduino, Zephyr, ESP-IDF, Pico SDK, Go TinyGo) | < 10 µs | very low |
| epoll / sysfs / gpiochip (Linux GCC, Node.js, Go Linux) | < 1 ms | low |
| Polling thread 5 ms (Python Linux, JVM, Node.js fallback, Go Linux fallback) | 0–5 ms | ±5 ms |

### 10.5 Why Go's InputPin has no separate `OffEdge`

Go function values are not comparable (`==` on two `func` values is a compile error unless
one side is `nil`), so `off_edge(handler)` cannot be implemented by matching a stored
handler against the one passed back in — the approach every other language uses. `OnEdge`
returns an unsubscribe closure instead (`func() (unsubscribe func())`), which captures the
handler's identity by closing over it rather than comparing it. This is the same shape as
`context.CancelFunc` and is idiomatic Go; `Connection`-level `OffInterrupt()` simply calls
the closure it got back from `OnEdge` when `OnInterrupt` was called.

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

All chip constructors accept a single `Connection` object. `Connection` is not a
wrapper — it's the renamed, expanded bus implementation itself (`I2CTransport` is now
`I2CConnection`, etc.), constructed directly with its optional INT pin (`InputPin`) and
EN pin (`OutputPin`). See `specs/feature_connection_design.md` for the full design.

**Python:**
```python
conn = I2CConnection(bus=1, addr=0x68, int_pin=LinuxSysfsPin(17), en_pin=LinuxOutputPin(18))
```

**C++** (identical on Arduino, Linux GCC, Zephyr, ESP-IDF, and Pico SDK):
```cpp
I2CConnection conn(bus, addr, &gpioPin, &enPin);
```

**Node.js:**
```js
const conn = new I2CConnection(busNumber, addr, intPin, enPin);
```

**JVM:**
```java
Connection conn = new I2CConnection(bus, addr, gpioPin, enPin);
```

**Rust** (I²C/SPI — the one case that still wraps a generic bus, since Rust never had a
periph-owned `I2CTransport` to rename; see §4.5):
```rust
let conn = Connection::new(i2c);  // bus only; manage pins directly
```

**Go** (identical on Linux and TinyGo):
```go
conn, err := connection.NewI2CConnection(busNum, addr, intPin, enPin)
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
exclusively in `InputPinLinux.h` / `InputPinArduino.h` / `InputPinZephyr.h` /
`InputPinESPIDF.h` / `InputPinPicoSDK.h` — never in the chip driver.

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

**Go**
`OnInterrupt(cb)` calls `conn.IntPin.OnEdge(connection.Falling, handler)` and stores the
returned unsubscribe closure; `OffInterrupt()` calls it. If `conn.IntPin` is `nil`, start
a ticker-driven polling goroutine instead. Every fallible method returns `error`, per Go
convention elsewhere in this codebase.

### Interrupt sources (Level 2/3 chips)

Define a companion `<Chip>Source` constants class/object/enum in the same file as the
driver. One constant per condition, values matching the chip's interrupt-status register
bit layout. Threshold values and other parameters are set via separate `Full` setter
methods.
````

---

## 13. Migration Plan for Existing Chip Drivers

### 13.1 PCF8574

| Change | Python | C++ | Node.js | Rust | JVM | Go |
|--------|--------|-----|---------|------|-----|-----|
| Constructor | `Connection` replaces `(transport, int_pin=None)` | `Connection&` replaces `(Transport&, InputPin*)` | `Connection` replaces `(transport, intPin)` | `Connection<I2C>` replaces `I2C` | `Connection` replaces `(Transport, InputPin)` | `connection.Connection` replaces `transport.Transport` |
| `configure_interrupt` → | `on_interrupt(cb)` | `onInterrupt(cb)` | `onInterrupt(cb)` | — | `onInterrupt(IntConsumer)` | add `OnInterrupt(cb) error` (no prior equivalent) |
| `clear_interrupt` → | `poll_interrupt()` | `pollInterrupt()` | `pollInterrupt()` | `poll_interrupt()` | `pollInterrupt()` | `ClearInterrupt()` → `PollInterrupt()` |
| `pin.irq` → | `pin.watch()` | `pin.watch()` | already `watch` | — | add `pin.watch()` | `WatchInterrupt()` → `Watch()`, add `Unwatch()` |
| INT-pin delivery | Move to `Connection` / `InputPin` impl | Same | Same | N/A | Same | Same |
| Platform guards | Remove from chip driver | Same | Same | N/A | Same | Same (build-tag files instead of `#ifdef`) |

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

Go mirrors this with `OnInterrupt(cb func(port int, status uint8)) error`,
`OnInterruptPort(port int, cb func(uint8)) error`, `OffInterrupt() error` /
`OffInterruptPort(port int) error`, and `PollInterrupt(port int) (uint8, error)`.

---

## 14. New Files Summary

Genuinely new files — `InputPin` / `OutputPin` and their platform implementations, plus
the few files listed in §4.1 that had no prior equivalent (Node.js's `connection.js`,
JVM's `AbstractConnection.java`). The renamed low-level bus files (`I2CTransport` →
`I2CConnection` and the ~50 other protocol × platform pairs) are **not** re-listed here —
see §4.1 for the rename pattern and blast radius.

| File | Language | Purpose |
|------|----------|---------|
| `python/periph/connection/base.py` | Python | `Connection` ABC (renamed/expanded from `Transport`, was `transport/base.py`) |
| `python/periph/connection/input_pin.py` | Python | `InputPin` ABC + `MicroPythonPin`, `CircuitPythonPin`, `LinuxPollingPin`, `LinuxSysfsPin` |
| `python/periph/connection/output_pin.py` | Python | `OutputPin` ABC + `MicroPythonOutputPin`, `CircuitPythonOutputPin`, `LinuxOutputPin` |
| `cpp/src/connection/Connection.h` | C++ | `Connection` base class (renamed/expanded from `Transport`, was `transport/Transport.h`) |
| `cpp/src/connection/InputPin.h` | C++ | `InputPin` base class |
| `cpp/src/connection/InputPinArduino.h` | C++ | `attachInterrupt` implementation |
| `cpp/src/connection/InputPinLinux.h` | C++ | `poll()` thread implementation |
| `cpp/src/connection/InputPinZephyr.h` | C++ | `gpio_add_callback` implementation |
| `cpp/src/connection/InputPinESPIDF.h` | C++ | `gpio_install_isr_service` / `gpio_isr_handler_add` implementation |
| `cpp/src/connection/InputPinPicoSDK.h` | C++ | `gpio_set_irq_enabled_with_callback` implementation |
| `cpp/src/connection/OutputPin.h` | C++ | `OutputPin` base class |
| `cpp/src/connection/OutputPinArduino.h` | C++ | `digitalWrite` implementation |
| `cpp/src/connection/OutputPinLinux.h` | C++ | sysfs GPIO implementation |
| `cpp/src/connection/OutputPinZephyr.h` | C++ | `gpio_pin_set` implementation |
| `cpp/src/connection/OutputPinESPIDF.h` | C++ | `gpio_set_level` implementation |
| `cpp/src/connection/OutputPinPicoSDK.h` | C++ | `gpio_put` implementation |
| `nodejs/packages/periph/src/connection/connection.js` | Node.js | `Connection` base class (new — JS had no prior shared `Transport` base) |
| `nodejs/packages/periph/src/connection/input_pin.js` | Node.js | `InputPin`, `EpollInputPin`, `PollingInputPin` |
| `nodejs/packages/periph/src/connection/output_pin.js` | Node.js | `OutputPin`, `GpioOutputPin` |
| `rust/periph/src/connection/connection.rs` | Rust | `Connection<BUS>` struct (bus + enabled state) — new code, not a rename; see §4.5 |
| `jvm/periph-connection/…/connection/Connection.java` | Java | `Connection` interface (renamed/expanded from `Transport`, was `periph-transport/…/transport/Transport.java`) |
| `jvm/periph-connection/…/connection/AbstractConnection.java` | Java | Shared `enabled`/pin state + read/write gating (new — template-method base every concrete `*Connection` extends) |
| `jvm/periph-connection/…/connection/InputPin.java` | Java | `InputPin` interface + `EdgeTrigger` enum |
| `jvm/periph-connection/…/connection/PollingInputPin.java` | Java | 5 ms polling `InputPin` (default) |
| `jvm/periph-connection/…/connection/SysfsInputPin.java` | Java | sysfs GPIO input |
| `jvm/periph-connection/…/connection/OutputPin.java` | Java | `OutputPin` interface |
| `jvm/periph-connection/…/connection/SysfsOutputPin.java` | Java | sysfs GPIO output |
| `go/periph/connection/connection.go` | Go | `Connection` interface + `connectionBase` embeddable struct (renamed/expanded from `Transport`, was `transport/transport.go`) |
| `go/periph/connection/input_pin.go` | Go | `InputPin` interface + `Trigger` type, no build tag |
| `go/periph/connection/input_pin_linux.go` | Go | `GpioInputPin` (`linux && !tinygo`) — `/dev/gpiochip*` edge-event ioctl |
| `go/periph/connection/input_pin_tinygo.go` | Go | `GpioInputPin` (`tinygo`) — `machine.Pin.SetInterrupt` |
| `go/periph/connection/input_pin_polling.go` | Go | `PollingInputPin`, no build tag |
| `go/periph/connection/output_pin.go` | Go | `OutputPin` interface, no build tag |
| `go/periph/connection/output_pin_linux.go` | Go | `GpioOutputPin` (`linux && !tinygo`) |
| `go/periph/connection/output_pin_tinygo.go` | Go | `GpioOutputPin` (`tinygo`) |

Build/module metadata that also needs updating (not source files):

| File | Language | Change |
|------|----------|--------|
| `jvm/periph-connection/pom.xml` (was `jvm/periph-transport/pom.xml`) | Java | `artifactId` `periph-transport` → `periph-connection` |
| `jvm/periph-connection/src/main/java/module-info.java` | Java | `module it.uhde.periph.transport { exports it.uhde.periph.transport; }` → `module it.uhde.periph.connection { exports it.uhde.periph.connection; }` |
| `jvm/pom.xml` (parent) | Java | `<module>periph-transport</module>` → `<module>periph-connection</module>` |
| `jvm/periph-java/pom.xml`, `jvm/periph-kotlin/pom.xml`, `jvm/periph-groovy/pom.xml` | Java/Kotlin/Groovy | dependency `periph-transport` → `periph-connection`; `module-info.java`: `requires it.uhde.periph.transport;` → `requires it.uhde.periph.connection;` |
| `rust/periph/src/lib.rs` | Rust | `pub mod transport;` → `pub mod connection;` |

---

## 15. Design Decisions

Previously tracked as open questions; all resolved before implementation.

1. **C++ per-pin API: `watch` / `unwatch`**, not `attachInterrupt` / `detachInterrupt`.
   Matches the unified cross-language vocabulary already used by Python, JS, JVM, and Go
   (§8.1). Arduino's own `attachInterrupt` / `detachInterrupt` still gets used one layer
   down, inside `InputPinArduino.h`, to wire the actual hardware ISR — that's a different
   layer (hardware ISR registration) and is unaffected by this decision.

2. **Node.js `epoll` is an optional peer dependency**, not bundled. `EpollInputPin` is
   used only if `require('epoll')` succeeds; `PollingInputPin` (5 ms `setInterval`) is
   the automatic fallback otherwise. No consumer is forced into native compilation
   (node-gyp, a C toolchain) just to use a chip driver that happens to support
   interrupts.

3. **Rust Linux host — no polling-thread wrapper for `poll_interrupt`.** Out of scope
   for v1; revisit if requested.

4. **MCP23017 `INTCAP` register — `poll_interrupt` returns flags only.** `read_capture(port)`
   is a separate method for the latched pin-state register.

5. **`enable_interrupt` arity — single source per call**, not a list. Revisit if a
   real workload needs batched enable/disable of multiple sources at once.

6. **`Connection.disable()` is silent-only** — reads return zeros, writes are dropped,
   with no configurable throwing/strict mode. One behavior to document and test per
   language, and the safer default for embedded control loops where an exception could
   crash the loop; adding a `ConnectionDisabledError` mode later would be additive, not
   a breaking change, if a host-application use case ever needs it.

7. **No Rust `WithEnPin` wrapper.** Out of scope for v1; callers drive the EN pin
   directly via `embedded_hal::digital::OutputPin` (§4.5).

8. **Go's `InputPin.OnEdge` returns an unsubscribe closure instead of a paired
   `OffEdge`** (§5.6, §10.5). Accepted as a documented, one-off structural exception —
   Go function values aren't comparable, so there's no way to match a stored handler
   against one passed to a hypothetical `OffEdge(handler)`, the pattern every other
   language uses. The closure-return shape is idiomatic Go (the same shape as
   `context.CancelFunc`).
