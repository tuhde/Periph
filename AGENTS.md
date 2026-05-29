# AGENTS.md

This file provides guidance to OpenCode when implementing chip and transport drivers in this repository.

Read [CLAUDE.md](CLAUDE.md) first for repo layout, category structure, and the Minimal/Full implementation pattern. Read [TESTING.md](TESTING.md) for the per-platform test runners and test-file templates. This file only covers what is specific to writing code.

## Role

OpenCode implements. It does not modify specs, datasheets, or CLAUDE.md.

OpenCode may extend an existing transport implementation by adding methods if a chip driver requires a capability not yet present. Any addition must be documented with the same inline format as the rest of the file (docstring / Doxygen / JSDoc / `///`) and must be consistent with the transport's interface contract in `specs/transport_<name>.md`.

The spec in `specs/<category>/<chip>.md` is the single source of truth. Implement exactly what it defines — no more, no less.

**Documentation is part of implementation.** A platform is not done until its driver, tests, examples, and inline documentation are all complete. Submitting code without documentation is the same as submitting incomplete code.

**The implementation checklist is mandatory.** Every chip and transport spec contains an `## Implementation Checklist` section. This is not optional reading — it is the definition of done. Open it before writing a single line of code and tick each box as the item is committed. The PR may not be opened until every box is ticked.

- Chip checklist: `specs/<category>/<chip>.md` → `## Implementation Checklist`
- Transport checklist: `specs/transport_<name>.md` → `## Implementation Checklist`

## Finding the work

When given an issue number, find the "Ready for implementation" comment on that issue. It contains:

- **Spec** — path to the spec file (e.g. `specs/power/ina226.md`)
- **Branch** — the base feature branch (e.g. `feature/ina226`)
- **Stages** — which stages (Minimal, Full) are requested

Only pick up issues labelled `needs-implementation`. An issue labelled `needs-spec` is still waiting on Claude Code; do not start coding against it. An issue labelled `in-progress` is already being worked on.

## Branch naming

Do **not** commit directly to the feature branch. Create your own implementation branch from it:

```
impl/<chip>/OC-<model>
```

- `<chip>` — lowercase chip or transport name (e.g. `ina226`, `neopixel`)
- `<model>` — the LLM you are running on, lowercase, hyphens for spaces (e.g. `gpt-4o`, `claude-sonnet-4-5`, `gemini-2-5-pro`)

Example: `impl/ina226/OC-gpt-4o`

Check out the base feature branch, create your implementation branch from it, and open a PR targeting the feature branch when done.

### Reimplementation by a different model

If the issue has already been implemented by another model, it may be reopened and its labels reset to `needs-implementation`. In that case, treat it as a fresh implementation: create a new `impl/<chip>/OC-<model>` branch from `feature/<chip>` using your own model name. Do not modify or build on the existing `impl/` branch from the previous model — leave it untouched.

## Issue label workflow

OpenCode is responsible for keeping the labels on its issue in sync with reality. Use `gh issue edit <num> --add-label X --remove-label Y`.

| State | Labels | When |
|-------|--------|------|
| Ready to start | `needs-implementation` | Set by Claude Code when the spec lands |
| Picked up | `in-progress` (remove `needs-implementation`) | First thing you do after creating your implementation branch |
| Minimal stage merged | `in-progress`, `stage:minimal` | After committing the Minimal stage on your implementation branch |
| Full stage merged | `in-progress`, `stage:minimal`, `stage:full` | After committing the Full stage |
| Finished | `done` (remove `in-progress`) | After all platforms across all stages are committed and the PR is open |

The `chip` / `transport` label stays on the issue throughout — those describe what the issue *is*, not its state.

## Where things go

Every chip is implemented across all five languages and every supported platform within each language. Replace `<chip>` with the lowercase chip name (e.g. `ina226`) and `<Chip>` with the title-case chip name (e.g. `INA226`).

| Artifact | Path |
|----------|------|
| Python driver (all 3 targets) | `python/periph/chips/<category>/<chip>.py` |
| Python examples | `python/examples/<category>/<chip>/{minimal,complete,demo}.py` |
| C++ driver (Arduino + Linux + Zephyr) | `cpp/src/chips/<category>/<Chip>.h` and `<Chip>.cpp` |
| C++ Arduino examples | `cpp/examples/<Chip>_{Minimal,Complete,Demo}/<Chip>_{Minimal,Complete,Demo}.ino` |
| C++ Zephyr examples | `cpp/examples/<Chip>_{Minimal,Complete,Demo}_Zephyr/{src/main.cpp,CMakeLists.txt,prj.conf}` |
| Node.js driver | `nodejs/packages/periph/src/chips/<category>/<chip>.js` |
| Node.js examples | `nodejs/packages/periph/examples/<category>/<chip>/{minimal,complete,demo}.js` |
| Node-RED node | `nodejs/packages/node-red-contrib-periph-<category>/nodes/<chip>/{<chip>.js,<chip>.html}` |
| Node-RED demo flow | `nodejs/packages/node-red-contrib-periph-<category>/examples/<chip>/demo.json` |
| Rust driver (no_std, embedded-hal) | `rust/periph/src/chips/<category>/<chip>.rs` |
| Rust examples (Linux) | `rust/examples/<chip>_{minimal,complete,demo}/{Cargo.toml,src/main.rs}` |
| JVM Java driver | `jvm/periph-java/src/main/java/it/uhde/periph/chips/<category>/<Chip>Minimal.java` and `<Chip>Full.java` |
| JVM Kotlin driver | `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/<category>/<Chip>Minimal.kt` and `<Chip>Full.kt` |
| JVM Groovy driver | `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/<category>/<Chip>Minimal.groovy` and `<Chip>Full.groovy` |
| JVM examples (Java) | `jvm/examples/java/<category>/<chip>/{Minimal,Complete,Demo}.java` |
| JVM examples (Kotlin) | `jvm/examples/kotlin/<category>/<chip>/{Minimal,Complete,Demo}.kt` |
| JVM examples (Groovy) | `jvm/examples/groovy/<category>/<chip>/{Minimal,Complete,Demo}.groovy` |
| Sigrok decoder | `sigrok/<chip>/pd.py` and `sigrok/<chip>/__init__.py` |

For test file paths and runner scripts, see [TESTING.md](TESTING.md). When adding a chip, every platform's test must be added too — Linux, MicroPython, CircuitPython, Arduino, Zephyr, Node.js, Rust Linux, Rust ESP32-S3, and JVM.

Remove `.gitkeep` from a target directory when adding the first real file.

For chips with I²C or SMBus transport, add the chip's default I²C address to `chip_defaults`.

## Connection interface

> **When implementing a transport:** open `specs/transport_<name>.md` first and work through its `## Implementation Checklist` top-to-bottom. Every platform listed there must be delivered before the PR is opened.

Chip drivers accept a single `Connection` object and must only call `connection.read()` / `connection.write()`. Never access the underlying bus transport directly; never import or reference a concrete transport class. See `specs/feature_connection_design.md` for the full design.

```python
# Python
connection.read(reg: int, length: int) -> bytes   # write reg address, read length bytes
connection.write(reg: int, data: bytes | int)     # write reg address + data
```

```cpp
// C++ — same signatures on Arduino, Linux, and Zephyr
connection.read(uint8_t reg, uint8_t* buf, size_t len);
connection.write(uint8_t reg, const uint8_t* data, size_t len);
```

```js
// Node.js — camelCase, Buffers, async
await connection.read(reg, length)   // returns Buffer
await connection.write(reg, data)    // data: Buffer | number
```

```rust
// Rust — Connection<BUS>; chip driver stores conn: Connection<I2C>
self.conn.read(self.addr, REG_ADDR, &mut buf)?;
self.conn.write(self.addr, REG_ADDR, &data)?;
```

All register reads follow this pattern:

```python
# Python
raw   = self._conn.read(REG_ADDR, 2)
value = (raw[0] << 8) | raw[1]                # big-endian, unsigned
value = struct.unpack('>h', raw)[0]            # big-endian, signed
```

```js
// Node.js
const raw   = await this._conn.read(REG_ADDR, 2);
const value = raw.readUInt16BE(0);             // unsigned
const value = raw.readInt16BE(0);              // signed
```

```rust
// Rust
let mut buf = [0u8; 2];
self.conn.read(self.addr, REG_ADDR, &mut buf)?;
let value = ((buf[0] as u16) << 8) | buf[1] as u16;   // unsigned
let value = value as i16;                              // signed
```

## Class structure

Full extends Minimal by **adding** API surface; it never re-implements what Minimal already does. The mechanism for "extends" varies by language:

```python
# Python — inheritance
class INA226Minimal:
    def __init__(self, connection): ...
    # store as self._conn

class INA226Full(INA226Minimal):
    def __init__(self, connection):
        super().__init__(connection)
```

```cpp
// C++ — public inheritance
class INA226Minimal {
public:
    INA226Minimal(Connection& conn, ...);
protected:
    Connection& _conn;
};
class INA226Full : public INA226Minimal {
public:
    INA226Full(Connection& conn, ...);
};
```

```js
// Node.js — ES class extends
class INA226Full extends INA226Minimal { /* only new methods */ }
module.exports = { INA226Minimal, INA226Full };
```

```rust
// Rust — composition, since Rust has no inheritance.
// Full owns a Minimal and re-exports its methods as one-line delegates,
// then adds its own. This is the Rust analog of "Full never duplicates Minimal".
pub struct Ina226Minimal<I2C> { conn: Connection<I2C>, addr: u8, ... }
pub struct Ina226Full<I2C>    { inner: Ina226Minimal<I2C>, mode: u8 }
impl<I2C: I2c> Ina226Full<I2C> {
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> { self.inner.voltage() }
    // ... and Full-only methods below
}
```

## IO Expander drivers

IO expander chips follow the same two-stage pattern as other chips, but their primary API is a **GPIO facade**: the driver exposes individual `Pin` proxy objects that implement each platform's native GPIO interface. Users interact with pins exactly as they would with hardware GPIO.

The spec for every IO expander chip uses `specs/_template_chip_io_expander.md` instead of the standard chip template.

### Pin proxy pattern

The driver class (`<Chip>Minimal`, `<Chip>Full`) owns the transport and exposes a `pin(n)` factory. Each call returns a `Pin` proxy object that holds a back-reference to the driver and the pin index. Pin objects do not cache state — every read/write goes to the chip over the transport.

Platform details follow.

### Python — MicroPython

Implement an inner `Pin` class in the same file as the driver. It mirrors `machine.Pin` so existing code that accepts a `machine.Pin` also accepts an IO expander pin.

```python
class PCF8574Minimal:
    IN  = 0
    OUT = 1
    PULL_UP = 2
    IRQ_RISING  = 0x01
    IRQ_FALLING = 0x02

    def pin(self, n):
        return self._Pin(self, n)

    class _Pin:
        def __init__(self, chip, n, mode=0):
            self._chip = chip
            self._n = n
            self._mode = mode

        def init(self, mode, pull=None): ...
        def value(self, x=None):
            if x is None:
                return (self._chip.read_port(self._n // 8) >> (self._n % 8)) & 1
            else:
                self._chip._set_pin(self._n, x)
        def on(self):     self.value(1)
        def off(self):    self.value(0)
        def toggle(self): self.value(1 - self.value())
```

Full adds `watch(handler, trigger)` / `unwatch()` to the pin proxy. Interrupt delivery on MicroPython uses the chip's INT pin; pass a `MicroPythonPin` as `int_pin` in the `Connection` constructor and it will be available as `self._conn.int_pin` inside the driver.

### Python — CircuitPython

Implement a `_Pin` class matching `digitalio.DigitalInOut`. Mirror the property names exactly so the pin is a drop-in for CircuitPython GPIO:

```python
import digitalio

class _Pin:
    def __init__(self, chip, n):
        self._chip = chip
        self._n = n
        self._direction = digitalio.Direction.INPUT

    @property
    def direction(self): return self._direction
    @direction.setter
    def direction(self, d):
        self._direction = d
        self._chip._set_direction(self._n, d == digitalio.Direction.OUTPUT)

    @property
    def value(self):
        return bool((self._chip.read_port(self._n // 8) >> (self._n % 8)) & 1)
    @value.setter
    def value(self, v): self._chip._set_pin(self._n, int(v))

    def switch_to_input(self, pull=None): self.direction = digitalio.Direction.INPUT
    def switch_to_output(self, value=False, drive_mode=digitalio.DriveMode.PUSH_PULL):
        self.direction = digitalio.Direction.OUTPUT
        self.value = value
    def deinit(self): pass
```

Full adds `pull` and `drive_mode` properties.

### Python — Linux

Use the same `_Pin` interface as MicroPython (not CircuitPython) so a single mental model covers both embedded targets. The Linux target is host-only; if the chip supports interrupts, deliver them via `LinuxPollingPin` (5 ms thread) by default when `Connection.int_pin` is `None`, or via `LinuxSysfsPin` when a GPIO number is wired.

### C++

Define `IOExpanderPin` as a nested class (or a separate header `IOExpanderPin.h` if it grows large). Reuse Arduino GPIO constants so the API is immediately familiar:

```cpp
class PCF8574Minimal {
public:
    class IOExpanderPin {
    public:
        IOExpanderPin(PCF8574Minimal& chip, uint8_t n) : _chip(chip), _n(n) {}
        void mode(uint8_t m);        // INPUT, OUTPUT, INPUT_PULLUP
        void write(uint8_t v);       // HIGH, LOW
        uint8_t read();
        void high()   { write(HIGH); }
        void low()    { write(LOW);  }
        void toggle() { write(!read()); }
    private:
        PCF8574Minimal& _chip;
        uint8_t _n;
    };

    IOExpanderPin pin(uint8_t n) { return IOExpanderPin(*this, n); }
};
```

The same `IOExpanderPin` class compiles on Arduino, Linux GCC, and Zephyr. Use `#ifdef __linux__` or `#ifdef CONFIG_GPIO` only where interrupt delivery differs (Linux: `poll()` thread; Zephyr: `gpio_add_callback()`).

Full adds `attachInterrupt(void (*handler)(void), uint8_t mode)` / `detachInterrupt()` to `IOExpanderPin`. `mode` uses Arduino constants: `RISING`, `FALLING`, `CHANGE`, `HIGH`, `LOW`.

### Node.js

Implement a `_Pin` class matching the [`onoff`](https://www.npmjs.com/package/onoff) `Gpio` subset. This lets IO expander pins work with any code written against `onoff`:

```js
class _Pin {
    constructor(chip, n, direction) {
        this._chip = chip;
        this._n = n;
        this._direction = direction;  // 'in' | 'out'
    }
    get direction() { return this._direction; }
    readSync()           { return (this._chip._readPort(this._n >> 3) >> (this._n & 7)) & 1; }
    writeSync(v)         { this._chip._setPin(this._n, v); }
    read(cb)             { try { cb(null, this.readSync()); } catch(e) { cb(e); } }
    write(v, cb)         { try { this.writeSync(v); cb(null); } catch(e) { cb(e); } }
    unexport()           {}
}
```

Full adds `watch(callback, trigger)` / `unwatch()` to the pin proxy. Interrupt delivery uses `this._conn.intPin` (an `EpollGpioPin` or `PollingGpioPin`); if `intPin` is null, `on_interrupt` falls back to a 5 ms polling interval.

### Rust

The driver wraps its I2C bus in `core::cell::RefCell<I2C>` so multiple `Pin` objects can hold a shared `&'_` reference without fighting the borrow checker. Each pin operation borrows and releases the `RefCell` atomically — this is single-threaded and safe on embedded targets, but must not be used from multiple ISR contexts simultaneously (document this with a `# Safety` note).

```rust
use core::cell::RefCell;
use embedded_hal::i2c::I2c;
use embedded_hal::digital::{OutputPin, InputPin, Error, ErrorType};

pub struct Pcf8574Minimal<I2C> {
    i2c: RefCell<I2C>,
    addr: u8,
    out_state: u8,
}

pub struct ExPin<'a, I2C> {
    chip: &'a Pcf8574Minimal<I2C>,
    n: u8,
}

impl<I2C> Pcf8574Minimal<I2C> {
    pub fn pin(&self, n: u8) -> ExPin<'_, I2C> { ExPin { chip: self, n } }
}

impl<I2C: I2c> ErrorType for ExPin<'_, I2C> {
    type Error = I2C::Error;
}

impl<I2C: I2c> OutputPin for ExPin<'_, I2C> {
    fn set_high(&mut self) -> Result<(), I2C::Error> { self.chip.set_pin(self.n, true) }
    fn set_low(&mut self)  -> Result<(), I2C::Error> { self.chip.set_pin(self.n, false) }
}

impl<I2C: I2c> InputPin for ExPin<'_, I2C> {
    fn is_high(&mut self) -> Result<bool, I2C::Error> { ... }
    fn is_low(&mut self)  -> Result<bool, I2C::Error> { ... }
}
```

Full adds `embedded_hal::digital::StatefulOutputPin` where the chip can read back output latch state.

Re-export pin types from the category `mod.rs`:
```rust
pub use pcf8574::{Pcf8574Minimal, Pcf8574Full, ExPin};
```

## Connection construction and power management

All chip constructors accept a single `Connection` object. Construct the underlying bus transport as usual, then wrap it:

**Python:**
```python
from periph.transport.i2c_linux import I2CLinux
from periph.transport.gpio import LinuxSysfsPin
from periph.transport.output_pin import LinuxOutputPin
from periph.transport.connection import Connection

bus  = I2CLinux(bus=1, address=0x68)
conn = Connection(bus)                                      # bus only
conn = Connection(bus, int_pin=LinuxSysfsPin(17))           # with INT pin
conn = Connection(bus, en_pin=LinuxOutputPin(18))           # with EN pin
conn = Connection(bus, int_pin=LinuxSysfsPin(17), en_pin=LinuxOutputPin(18))
```

**C++:**
```cpp
Connection conn(bus);                        // bus only
Connection conn(bus, &gpioPin);              // with INT pin
Connection conn(bus, &gpioPin, &enPin);      // with INT + EN pin
```

**Node.js:**
```js
const conn = new Connection(bus);
const conn = new Connection(bus, intPin, enPin);
```

**JVM:**
```java
Connection conn = new Connection(new I2CTransport(bus, addr));
Connection conn = new Connection(new I2CTransport(bus, addr), gpioPin, enPin);
```

**Rust** — `Connection` wraps bus + enabled state only; INT and EN pins are managed by the caller directly via `embedded_hal::digital` traits.
```rust
let conn = Connection::new(i2c);
```

### Enable / disable

`conn.enable()` / `conn.disable()` are the unified on/off switch. When disabled, all `connection.read()` / `connection.write()` calls are silently no-ops (reads return zeros). If an `en_pin` is wired, the pin is driven accordingly. Chip drivers do not need any code changes to support this — gating is transparent.

```python
conn.disable()    # stop all bus access; drive EN pin low if wired
conn.enable()     # resume; drive EN pin high if wired
conn.is_enabled() # query state
```

## Interrupt support

Interrupts are implemented in the `Full` driver class for all chips with an INT output.
Level 1 = single fixed condition; Level 2 = selectable sources; Level 3 = multiple INT lines.
See `specs/feature_connection_design.md` for design rationale and the full platform matrix.

### Vocabulary

Adapt capitalisation to the language convention (snake_case Python/Rust, camelCase JS/JVM/C++).

| Concept | Method |
|---------|--------|
| Subscribe to INT assertions | `on_interrupt(callback)` |
| Unsubscribe | `off_interrupt()` |
| Read & clear status | `poll_interrupt() -> int` |
| Enable one interrupt source | `enable_interrupt(source)` — Level 2/3 only |
| Disable one interrupt source | `disable_interrupt(source)` — Level 2/3 only |
| Per-pin subscribe | `watch(handler, trigger)` — IO expanders only |
| Per-pin unsubscribe | `unwatch()` — IO expanders only |

### Per-language implementation rules

**Python (MicroPython / CircuitPython)**
`on_interrupt` calls `self._conn.int_pin.on_edge(self._int_handler, GpioPin.FALLING)`.
`_int_handler` calls `poll_interrupt()` and dispatches to the stored callback.
If `self._conn.int_pin is None`, start a 5 ms polling `Thread` as fallback.
Keep the handler short — no I/O beyond the register read.

**Python (Linux)**
Default to `LinuxPollingPin` (5 ms thread) when `int_pin` is `None`; expose `LinuxSysfsPin(gpio_num)` as opt-in for lower latency.

**C++**
Use `conn.intPin()` to access the `GpioPin*`. Platform `#ifdef` guards belong exclusively in `GpioPinLinux.h` / `GpioPinArduino.h` / `GpioPinZephyr.h`.

**Node.js**
`onInterrupt` calls `this._conn.intPin.onEdge(…)`. `pollInterrupt` is `async`.
If `this._conn.intPin` is `null`, start a 5 ms `setInterval` polling fallback.

**Rust**
Full drivers expose only `poll_interrupt() -> Result<u8, E>`.
Document in the driver docstring: caller is responsible for wiring this into an ISR or polling loop.

**JVM**
`onInterrupt(IntConsumer)` is the driver-level API.
If `connection.intPin()` is `null`, default to `new PollingGpioPin(5)` internally.

### Interrupt sources (Level 2/3 chips)

Define a companion `<Chip>Source` constants class / object / enum in the same file as the driver. One constant per condition, values matching the chip's interrupt-status register bit layout. Threshold values and other parameters are set via separate `Full` setter methods, not through `enable_interrupt`.

```python
class Mpu6050Source:
    DATA_READY    = 0x01
    MOTION        = 0x40
    FIFO_OVERFLOW = 0x10
```

## Python conventions

Three supported targets: **MicroPython** (primary), **CircuitPython**, **Linux kernel** (via `smbus2`). The chip driver is the same single file for all three; each target has its own transport.

### Chip drivers

Chip drivers are platform-agnostic — they only call the transport interface. Write to the most restrictive common denominator:

- No f-strings, no walrus operator, no `match` statements — MicroPython lags CPython
- Avoid heap allocation in frequently-called methods; reuse `bytearray` buffers where practical
- Use `struct.pack` / `struct.unpack` for multi-byte register values
- No type annotations — MicroPython does not enforce them and they add overhead
- Constants as class-level variables prefixed with `_` (e.g. `_REG_CONFIG = 0x00`)

### Transport implementations

| File | Platform | Bus object |
|------|----------|------------|
| `i2c_micropython.py` | MicroPython | `machine.I2C` / `machine.SoftI2C` |
| `i2c_circuitpython.py` | CircuitPython | `busio.I2C` |
| `i2c_linux.py` | Linux kernel | `smbus2.SMBus` or bus number (int) |

Users import the transport for their target:
```python
from periph.transport.i2c_micropython import I2CTransport    # MicroPython
from periph.transport.i2c_circuitpython import I2CTransport  # CircuitPython
from periph.transport.i2c_linux import I2CTransport          # Linux
```

## C++ conventions

Three supported targets: **Arduino**, **Linux GCC**, **Zephyr RTOS**. The chip driver (`cpp/src/chips/<category>/<Chip>.{h,cpp}`) is shared across all three; each target has its own transport.

### Chip drivers

- No STL (`std::vector`, `std::string`, etc.) — not available on all Arduino targets
- No exceptions — use return codes or a `valid()` flag pattern for errors (see `SMBusTransport`)
- No heap allocation in drivers (`new` / `malloc`) — use stack or member variables only
- Register constants as `static constexpr uint8_t` in the class header
- 16-bit register reads: receive two bytes, combine as `(buf[0] << 8) | buf[1]`
- Signed 16-bit: cast as `static_cast<int16_t>((buf[0] << 8) | buf[1])`

### Transport implementations

| File | Platform | Bus object |
|------|----------|------------|
| `I2CTransport.h/.cpp` | Arduino | `Wire` (or any `TwoWire&`) |
| `I2CTransportLinux.h/.cpp` | Linux GCC | `/dev/i2c-N` via `linux/i2c-dev.h` |
| `I2CTransportZephyr.h` | Zephyr RTOS | `const struct device*` from devicetree, header-only |
| `SMBusTransport.h/.cpp`, `SMBusTransportLinux.h/.cpp` | PEC-capable variants | |
| `SPITransport.h/.cpp` | Arduino SPI | |

Linux-only transport classes are guarded with `#ifdef __linux__` so the Arduino library compiles cleanly.

### Zephyr examples

Each Zephyr example is a separate Zephyr application directory: `cpp/examples/<Chip>_<Tier>_Zephyr/` containing `src/main.cpp`, `CMakeLists.txt`, and `prj.conf`. The CMake file pulls the chip driver source from `cpp/src/chips/<category>/`:

```cmake
cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(<chip>_minimal_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../..)

target_sources(app PRIVATE
    src/main.cpp
    ${CPP_DIR}/src/chips/<category>/<Chip>.cpp
)
target_include_directories(app PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/<category>
)
```

Standard `prj.conf`:
```
CONFIG_I2C=y
CONFIG_CPP=y
CONFIG_STD_CPP17=y
CONFIG_NEWLIB_LIBC=y
CONFIG_FPU=y
```

The example uses `DEVICE_DT_GET(DT_NODELABEL(i2c0))` by default; this works on most boards. For boards with a different I²C node label, ship a board overlay rather than hard-coding.

## Node.js connection interface

JS chip drivers use `connection.read(reg, length)` / `connection.write(reg, data)` in camelCase (see Connection interface section above).

## Node.js driver structure

Plain JS driver (in `nodejs/packages/periph/src/chips/<category>/<chip>.js`):

```js
'use strict';

class INA226Minimal {
    constructor(connection, rShunt = 0.1, maxCurrent = 2.0) {
        this._conn = connection;
        this._currentLsb = maxCurrent / 32768;
        this._cal = Math.trunc(0.00512 / (this._currentLsb * rShunt));
        this._writeReg(REG_CONFIG, CONFIG_DEFAULT);
        this._writeReg(REG_CAL, this._cal);
    }
    async _writeReg(reg, value) { await this._conn.write(reg, value); }
    async _readReg(reg) { return this._conn.read(reg, 2); }
}

class INA226Full extends INA226Minimal { ... }

module.exports = { INA226Minimal, INA226Full };
```

- CommonJS (`require`/`module.exports`) — required for Node-RED compatibility
- camelCase for methods and properties; UPPER_SNAKE for constants
- No TypeScript, no ES modules syntax

## Node-RED node structure

Each chip has two files in `nodejs/packages/node-red-contrib-periph-<category>/nodes/<chip>/`:

**`<chip>.js`** — runtime node, registered as `periph-<chip>`:
```js
'use strict';
module.exports = function(RED) {
    function INA226Node(config) {
        RED.nodes.createNode(this, config);
        const transport = /* build from config */;
        const sensor = new (require('periph/src/chips/<category>/<chip>')).INA226Minimal(transport);
        this.on('input', function(msg) {
            msg.payload = { voltage: sensor.voltage(), current: sensor.current(), power: sensor.power() };
            this.send(msg);
        });
    }
    RED.nodes.registerType('periph-ina226', INA226Node);
};
```

**`<chip>.html`** — editor UI: defines the node's config panel, label, and color using `RED.nodes.registerType`.

The `index.js` at the package root auto-discovers nodes — never edit it manually.

When a new node is added, update the `"node-red": { "nodes": {} }` field in the package's `package.json` to register the node name and file path.

## Rust conventions

Two supported targets: **Linux** (host, via `linux-embedded-hal`) and **ESP32-S3** (bare metal, via `esp-hal`). The chip driver crate (`rust/periph`) is `no_std` and generic over `embedded-hal::i2c::I2c`, so it runs on both unchanged.

### Chip drivers

- The driver crate is `no_std`. Do not import `std`, `alloc`, or anything outside `core` and `embedded-hal`.
- Generic over the I²C bus via `Connection`: `pub struct <Chip>Minimal<I2C> { conn: Connection<I2C>, addr: u8, ... }` with `impl<I2C: I2c> <Chip>Minimal<I2C> { ... }`. The chip owns the `Connection`, which owns the bus.
- All fallible methods return `Result<T, I2C::Error>` — propagate the bus error type, never wrap it. Use `?` everywhere.
- Struct names use Rust title-case: `Ina226Minimal`, `Ina226Full` (not `INA226Minimal`).
- Register addresses are file-private `const u8`; public bit/flag constants are `pub const u16` (or appropriate width) at module scope, **not** inside an `impl`.
- Helper functions (`read_reg`, `write_reg`, `read_reg_signed`) live as free functions at the bottom of the file. No traits, no macros — flat is fine.
- `Full` cannot inherit from `Minimal`. Use composition: `pub struct <Chip>Full<I2C> { inner: <Chip>Minimal<I2C>, ... }`. Re-expose Minimal's public methods as one-line delegates (`self.inner.voltage()`), then add Full-only methods. The one-line forwards are the Rust equivalent of "Full never duplicates Minimal".
- Re-export the chip's public types from the category `mod.rs`: `pub use ina226::{Ina226Minimal, Ina226Full, BOL, ...};` so users write `periph::chips::power::Ina226Full`.

### Workspace layout

The `rust/Cargo.toml` workspace contains the library crate, the three Linux examples, and the Linux test crate. The ESP32-S3 test crate is **excluded** from the workspace (it requires the Espressif `esp` toolchain selected via `rust-toolchain.toml`); add it under `[workspace] exclude = [...]` rather than `members`. See `rust/tests/power/ina226_test_esp32s3/` for the standalone-crate template.

When adding a new chip, update `rust/Cargo.toml`:
- Add `examples/<chip>_minimal`, `examples/<chip>_complete`, `examples/<chip>_demo`, and `tests/<category>/<chip>_test` to `members`.
- Add `tests/<category>/<chip>_test_esp32s3` to `exclude`.

### Examples

Linux Rust examples use `linux-embedded-hal::I2cdev` directly — there is no separate Rust transport-wrapper crate.

```rust
use linux_embedded_hal::I2cdev;
use periph::transport::Connection;
use periph::chips::<category>::<Chip>Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);
    let mut chip = <Chip>Minimal::new(conn, addr, 0.1, 2.0).expect("init");
    // ... primary-value loop ...
}
```

There is **no** ESP32-S3 example crate — only an ESP32-S3 *test* crate. Embedded smoke testing happens via `rust/test_esp32s3.sh` (see TESTING.md).

## JVM Java/Kotlin/Groovy conventions

Three languages, one transport library. The chip driver is implemented independently in each language; all three depend only on `periph-transport` (Java) and never on each other.

Target platform: **Linux host via i2c-dev / FFM** (all three languages use the same `I2CTransport`).

### Connection interface

Chip drivers receive a `Connection` and call its two methods. All fallible methods throw `IOException`.

```java
// Java / Groovy
conn.read(int reg, int length) throws IOException;    // returns byte[]
conn.write(int reg, byte[] data) throws IOException;
```

```kotlin
// Kotlin — same signatures; IOException is an unchecked exception in Kotlin
conn.read(reg: Int, length: Int): ByteArray
conn.write(reg: Int, data: ByteArray)
```

Register reads follow the big-endian pattern:

```java
// Java / Groovy — unsigned 16-bit
byte[] b = conn.read(reg, 2);
int value = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);

// Java / Groovy — signed 16-bit
int value = (short) (((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
```

```kotlin
// Kotlin — unsigned 16-bit
val b = conn.read(reg, 2)
val value = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)

// Kotlin — signed 16-bit
val value = (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toShort().toInt()
```

### Class structure

**Java** — classical public inheritance; register constants as `protected static final int`:

```java
public class Ina226Minimal {
    protected static final int REG_CONFIG = 0x00;
    protected static final int DEFAULT_CONFIG = 0x4127;

    protected final Connection conn;
    protected final double currentLsb;
    protected final int cal;

    public Ina226Minimal(Connection conn) throws IOException { this(conn, 0.1, 2.0); }
    public Ina226Minimal(Connection conn, double rShunt, double maxCurrent) throws IOException { ... }

    public double voltage() throws IOException { ... }
    protected void writeReg(int reg, int val) throws IOException { ... }
    protected int  readReg(int reg)           throws IOException { ... }
    protected int  readRegSigned(int reg)     throws IOException { ... }
}

public class Ina226Full extends Ina226Minimal {
    public Ina226Full(Connection conn, double rShunt, double maxCurrent) throws IOException {
        super(conn, rShunt, maxCurrent);
    }
    // Full-only methods only
}
```

**Kotlin** — `open class` for Minimal; constants in `companion object`; `@JvmOverloads` on constructors with defaults:

```kotlin
open class Ina226Minimal @JvmOverloads constructor(
    protected val conn: Connection,
    rShunt: Double = 0.1,
    maxCurrent: Double = 2.0
) {
    companion object {
        const val REG_CONFIG    = 0x00
        const val DEFAULT_CONFIG = 0x4127
    }

    protected val currentLsb: Double = maxCurrent / 32768.0
    protected val cal: Int = (0.00512 / (currentLsb * rShunt)).toInt()

    init { writeReg(REG_CONFIG, DEFAULT_CONFIG); writeReg(REG_CAL, cal) }

    fun voltage(): Double = readReg(REG_BUS) * 1.25e-3

    protected fun writeReg(reg: Int, value: Int) { ... }
    protected fun readReg(reg: Int): Int { ... }
    protected fun readRegSigned(reg: Int): Int { ... }
}

class Ina226Full @JvmOverloads constructor(
    conn: Connection,
    rShunt: Double = 0.1,
    maxCurrent: Double = 2.0
) : Ina226Minimal(conn, rShunt, maxCurrent) {
    // Full-only methods only
}
```

**Groovy** — `@CompileStatic` for type safety; otherwise mirrors the Java structure:

```groovy
@CompileStatic
class Ina226Minimal {
    protected static final int REG_CONFIG    = 0x00
    protected static final int DEFAULT_CONFIG = 0x4127

    protected final Connection conn
    protected final double currentLsb
    protected final int    cal

    Ina226Minimal(Connection conn)                              { this(conn, 0.1d, 2.0d) }
    Ina226Minimal(Connection conn, double rShunt, double maxCurrent) {
        this.conn       = conn
        this.currentLsb = maxCurrent / 32768.0d
        this.cal        = (int)(0.00512d / (currentLsb * rShunt))
        writeReg(REG_CONFIG, DEFAULT_CONFIG)
        writeReg(REG_CAL, cal)
    }

    double voltage() { readReg(REG_BUS) * 1.25e-3d }

    protected void writeReg(int reg, int val) { ... }
    protected int  readReg(int reg)           { ... }
    protected int  readRegSigned(int reg)     { ... }
}

@CompileStatic
class Ina226Full extends Ina226Minimal {
    Ina226Full(Connection conn, double rShunt, double maxCurrent) {
        super(conn, rShunt, maxCurrent)
    }
    // Full-only methods only
}
```

- Always annotate Groovy chip driver classes with `@CompileStatic`.
- Use `double` (not `Double`) for primitive fields.
- Both driver classes go in **separate files** — never combine Minimal and Full in one `.java`, `.kt`, or `.groovy` file.

### Inline documentation

**Java** — Javadoc `/** */` with `@param`, `@return`, `@throws`:

```java
/**
 * Read the bus voltage.
 *
 * @return bus voltage in V (1.25 mV LSB, unsigned 16-bit)
 * @throws IOException on I²C error
 */
public double voltage() throws IOException { ... }
```

**Kotlin** — KDoc `/** */` with `@param`, `@return`; use Markdown headers (not HTML):

```kotlin
/**
 * Read the bus voltage.
 *
 * @return bus voltage in V (1.25 mV LSB, unsigned 16-bit)
 */
fun voltage(): Double = ...
```

**Groovy** — Groovydoc (same syntax as Javadoc, `/** */` with `@param`, `@return`):

```groovy
/**
 * Read the bus voltage.
 *
 * @return bus voltage in V (1.25 mV LSB, unsigned 16-bit)
 */
double voltage() { ... }
```

### Examples — JBang scripts

All JVM examples are standalone JBang scripts. Use these headers exactly:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT        // or periph-kotlin / periph-groovy
```

For Kotlin examples, the JBang shebang and dependency lines are identical; file extension is `.kt`.  
For Groovy examples, same headers but `.groovy` extension and `//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT`.

Resource management:

- **Java:** `try (var conn = new Connection(new I2CTransport(bus, addr))) { ... }` — `AutoCloseable`, try-with-resources
- **Kotlin:** `Connection(I2CTransport(bus, addr)).use { conn -> ... }` — `Closeable.use { }`
- **Groovy:** `def conn = new Connection(new I2CTransport(bus, addr)); try { ... } finally { conn.close() }` — explicit finally block

### Tests — JBang scripts

JVM tests use the same JBang headers as examples. All three languages use `I2C_BUS` / `I2C_ADDR` environment variables, and print the standard `PASS`/`FAIL`/`===DONE===` output:

```java
int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
int addr = Integer.parseInt(
        System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);
```

The test file name is `<Chip>Test.java` (or `.kt` / `.groovy`). Run with `jbang <Chip>Test.java`. No runner script — tests are executed directly on Raspberry Pi hardware.

---

## Sigrok decoders

A sigrok protocol decoder sits on top of the `i2c` (or `spi`) sigrok decoder and annotates bus transactions with chip-specific register names and decoded field values. Decoders are Python 3, constrained to the `sigrokdecode` API; no external packages.

### File layout

```
sigrok/<chip>/
    pd.py          # decoder implementation
    __init__.py    # re-exports Decoder
sigrok/tests/<chip>/
    <Chip>-Test.sr # captured sigrok session used for manual verification
```

No Minimal/Full stages — one decoder per chip. The decoder covers all registers defined in the chip spec.

### Decoder skeleton

```python
import sigrokdecode as srd

ADDRS = set(range(0x40, 0x50))   # chip's valid I²C addresses

REGS = {
    0x00: 'Config',
    0x01: 'Value',
    # ...
}

ANN_WRITE   = 0
ANN_READ    = 1
ANN_WARNING = 2


class Decoder(srd.Decoder):
    api_version = 3
    id = 'chipname'            # lowercase, matches directory name
    name = 'ChipName'
    longname = 'ChipName full description'
    desc = 'Decode ChipName I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['chipname']
    tags = ['IC', 'Category']  # e.g. 'Sensor', 'Power', 'DAC', 'IO'

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf  = []
            self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.state   = 'GET_DATA_READ' if self.is_read else 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = pdata
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []
```

### `__init__.py`

Always just re-exports `Decoder`:

```python
"""
<ChipName> sigrok protocol decoder.

<One-line description of what it decodes.>
"""

from .pd import Decoder
```

### Annotation conventions

- Provide at least two strings per `put()` call: a long form and a short form. sigrok shows the shortest one that fits.
- Use `0x%02X` for single-byte values, `0x%04X` for 16-bit values.
- Emit a `WARNING` annotation for unexpected read/write lengths, unknown addresses, or protocol violations — never raise exceptions.
- All numeric values in annotations use SI units (µV, mV, V, µA, mA, A, etc.).

### `START REPEAT` handling

For chips that use a repeated-start read (write register pointer, repeated start, read data), preserve `reg_ptr` across the repeated start instead of resetting it:

```python
if ptype in ('START', 'START REPEAT'):
    if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
        pass  # pointer already set; keep databuf and state
    else:
        self._finish_transaction()
        self.databuf  = []
        self.is_read  = False
    self.ss_block = ss
    self.state    = 'GET_ADDR'
```

### Tests

The `sigrok/tests/<chip>/` directory holds one `.sr` session file captured from real hardware. There is no automated runner — open the `.sr` file in PulseView with the decoder loaded and verify that annotations match the expected register values. The `.sr` file is committed alongside the decoder.

---

## Examples

Each chip has three examples per language (same branch as the driver):

| File | Class | Content | Comments |
|------|-------|---------|---------|
| `minimal` | `*Minimal` | Construct with transport, read primary values in a loop, print to serial/stdout. | Tier-1 signature comment on every call. |
| `complete` | `*Full` | Every method in the API called once — configuration, alerts, shutdown/wake, IDs. | Tier-1 + Tier-2 (what-it-does line below each call). |
| `demo` | `*Full` | The scenario from the spec's Demo section. | Tier-1 + Tier-3 (context block at each logical section boundary). |

See `## Documentation → Example tiers` below for the exact comment format.

The demo scenario is defined in the chip spec. The minimal and complete examples are fully implied by the API tables — implement them mechanically.

For C++ Arduino, the directory name must exactly match the `.ino` filename: `INA226_Minimal/INA226_Minimal.ino`.

For C++ Zephyr, each example is a standalone Zephyr app directory `<Chip>_<Tier>_Zephyr/` (see Zephyr examples above).

For Rust, each example is its own crate at `rust/examples/<chip>_<tier>/` with a `Cargo.toml` and `src/main.rs`.

Node-RED gets one example per chip: `examples/<chip>/demo.json` — an importable flow showing the node wired in a realistic scenario. No minimal or complete flows.

## Tests

Every chip needs hardware tests for **every** supported platform:

| Platform | Path |
|----------|------|
| Arduino | `cpp/tests/<category>/<chip>_test/<chip>_test.ino` |
| Linux GCC | `cpp/tests/<category>/<chip>_test_linux/<chip>_test_linux.cpp` |
| Zephyr | `cpp/tests/<category>/<chip>_test_zephyr/{src/main.cpp,CMakeLists.txt,prj.conf}` |
| MicroPython | `python/tests/<category>/<chip>_test.py` |
| CircuitPython | `python/tests/<category>/<chip>_test_cp.py` |
| Linux kernel (Python) | `python/tests/<category>/<chip>_test_linux.py` |
| Node.js | `nodejs/tests/<category>/<chip>_test.js` |
| Rust Linux | `rust/tests/<category>/<chip>_test/{Cargo.toml,src/main.rs}` |
| Rust ESP32-S3 | `rust/tests/<category>/<chip>_test_esp32s3/{Cargo.toml,src/main.rs,.cargo/config.toml,rust-toolchain.toml}` |
| JVM | `jvm/tests/<category>/<chip>/<Chip>Test.java` |

All tests print `PASS <label>` / `FAIL <label>` lines and end with `===DONE: N passed, N failed===`. See [TESTING.md](TESTING.md) for the full templates and runner scripts.

## Documentation

### Source files — inline API documentation

Every public class, constructor, and method must be documented inline using the platform-native format. Tool-generated docs (Sphinx, Doxygen, JSDoc, `cargo doc`) must work without extra configuration.

**Python** — Google-style docstrings on every class and public method:
```python
class INA219Minimal:
    """Power monitor: measures bus voltage, shunt current, and power.

    Configured at construction for continuous shunt+bus conversion with
    sensible defaults. Primary use case: read V, I, P in a loop.
    """

    def voltage(self):
        """Read bus voltage.

        Returns:
            float: Bus voltage in volts.
        """
```

**C++** — Doxygen `/** */` in headers, one `@brief` per class and per method:
```cpp
/** @brief Power monitor: measures bus voltage, shunt current, and power. */
class INA219Minimal {
public:
    /**
     * @brief Construct and initialise the INA219.
     * @param transport  I²C transport bound to the chip's address.
     * @param r_shunt    Shunt resistor value in ohms (default 0.1).
     * @param max_current Maximum expected current in amps (default 2.0).
     */
    INA219Minimal(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    /**
     * @brief Read bus voltage.
     * @return Bus voltage in volts.
     */
    float voltage();
};
```

**Node.js** — JSDoc `/** */` on every class and exported method:
```js
/**
 * Power monitor: measures bus voltage, shunt current, and power.
 */
class INA219Minimal {
    /**
     * @param {object} transport - I²C transport bound to the chip's address.
     * @param {number} [rShunt=0.1] - Shunt resistor in ohms.
     * @param {number} [maxCurrent=2.0] - Maximum expected current in amps.
     */
    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) { ... }

    /**
     * Read bus voltage.
     * @returns {number} Bus voltage in volts.
     */
    voltage() { ... }
}
```

**Rust** — `///` rustdoc on every `pub` item; `//!` module doc at the top of each file:
```rust
//! INA219 power monitor driver.

/// Power monitor: measures bus voltage, shunt current, and power.
pub struct Ina219Minimal<I2C> { ... }

impl<I2C: I2c> Ina219Minimal<I2C> {
    /// Construct and initialise the INA219.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `addr` — 7-bit I²C address (typically `0x40`).
    /// * `r_shunt` — Shunt resistor in ohms.
    /// * `max_current` — Maximum expected current in amps.
    pub fn new(i2c: I2C, addr: u8, r_shunt: f32, max_current: f32) -> Result<Self, I2C::Error> { ... }

    /// Read bus voltage.
    ///
    /// Returns bus voltage in volts.
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> { ... }
}
```

**Node-RED** — `<script type="text/html" data-help-name="…">` section in the `.html` file, following the [Node-RED help style guide](https://nodered.org/docs/creating-nodes/help-style-guide):
```html
<script type="text/html" data-help-name="periph-ina219">
    <p>Reads bus voltage, load current, and power from an INA219 power monitor.</p>
    <h3>Inputs</h3>
    <dl class="message-properties">
        <dt>payload <span class="property-type">any</span></dt>
        <dd>Any incoming message triggers a measurement.</dd>
    </dl>
    <h3>Outputs</h3>
    <dl class="message-properties">
        <dt>payload.voltage <span class="property-type">number</span></dt>
        <dd>Bus voltage in V.</dd>
        <dt>payload.current <span class="property-type">number</span></dt>
        <dd>Load current in A.</dd>
        <dt>payload.power <span class="property-type">number</span></dt>
        <dd>Power in W.</dd>
    </dl>
    <h3>Configuration</h3>
    <p>Set the I²C bus number, the chip's 7-bit address (default <code>0x40</code>),
    the shunt resistor value, and the maximum expected current.</p>
</script>
```

### Example tiers — comment format

The three example tiers use an **additive** comment system.

**Tier-1 signature comment** — present on every call in all three tiers. Trailing, on the same line as the call:

```
# <short verb phrase>, (<params>) → <type> <unit>   ← return-value calls
# <short verb phrase>, (<param>=<default> <unit>, …)  ← void calls and constructors
```

Parameter list follows the method signature (names, defaults, units) — not the call-site values.

**Minimal — Tier-1 only:**
```python
ina = INA219Full(transport)                          # Create INA219 driver, (transport, r_shunt=0.1 Ω, max_current=2.0 A)
v   = ina.voltage()                                  # Read bus voltage, () → float V
i   = ina.current()                                  # Read load current, () → float A
ok  = ina.conversion_ready()                         # Check conversion done, () → bool
ina.shutdown()                                       # Put chip into power-down mode, () → None
```

**Complete — Tier-1 + Tier-2** (one additional line immediately below each call, explaining what it does):
```python
v = ina.voltage()                                    # Read bus voltage, () → float V
                                                     # converts raw bus register to volts (1.25 mV LSB)
ina.configure(avg=4, vbus_ct=4, vsh_ct=4, mode=7)   # Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None
                                                     # sets averaging count, conversion time, and operating mode
ina.set_alert(INA219Full.POL, limit=1.5)             # Set alert threshold, (function, limit, polarity=False, latch=False) → None
                                                     # arms the ALERT pin when the computed power exceeds limit W
```

**Demo — Tier-1 on each call + Tier-3 block at each logical section boundary** (Tier-2 per-call lines are omitted):
```python
# --- Configure for noise-sensitive power rail monitoring ---
# 128-sample averaging suppresses switching noise on a noisy 5 V rail;
# continuous mode avoids re-triggering overhead between measurements.
ina.configure(avg=7, vbus_ct=4, vsh_ct=4, mode=7)   # Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None

# --- Sample 10 times and characterise idle vs loaded power ---
# User is prompted to connect a load at n=5 so both states appear in one run.
for n in range(10):
    while not ina.conversion_ready():                # Check conversion done, () → bool
        pass
    v = ina.voltage()                                # Read bus voltage, () → float V
    i = ina.current()                                # Read load current, () → float A
```

The Tier-1 comment format is the same across all languages; adjust the comment character (`//` for C++/JS/Rust) and the type name to match the language.

For **C++**, the return type is usually already visible in the declaration, so the comment may use just the unit: `// Read bus voltage, () → V`.

For **Rust**, include the `Result` unwrapping in the format: `// Read bus voltage, () → f32 V`.

For **Node-RED** `demo.json`, there are no inline comments. The tab node's `info` field is the Tier-3 equivalent — a paragraph explaining the scenario, what to observe, and what to adjust.

## Commit convention

One commit per stage per platform. Message format:

```
Add INA226Minimal for Python/MicroPython

Co-Authored-By: OpenCode <noreply@opencode.ai>
```

Use these platform labels consistently:

| Platform | Label |
|----------|-------|
| Python MicroPython | `Python/MicroPython` |
| Python CircuitPython | `Python/CircuitPython` |
| Python Linux | `Python/Linux` |
| C++ Arduino | `C++/Arduino` |
| C++ Linux GCC | `C++/Linux` |
| C++ Zephyr | `C++/Zephyr` |
| Node.js | `Node.js` |
| Node-RED | `Node-RED` |
| Rust Linux | `Rust/Linux` |
| Rust ESP32-S3 | `Rust/ESP32-S3` |
| JVM Java | `JVM/Java` |
| JVM Kotlin | `JVM/Kotlin` |
| JVM Groovy | `JVM/Groovy` |
| Sigrok decoder | `Sigrok` |
