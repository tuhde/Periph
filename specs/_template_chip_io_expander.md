# Chip Spec: <ChipName> (IO Expander)

**Manufacturer:** <Manufacturer>  
**Datasheet:** `datasheets/io_expander/<filename>`  
**Category:** `io_expander`  
**Transports:** <I²C | SPI | both>

## Overview

<!-- One paragraph: what the chip does and why you'd use it. -->

## Transport Configuration

### I²C
- **Address:** `0x??` (default) — `0x??` (alternate, if applicable)
- **Max clock:** <e.g. 400 kHz>

### SPI
- **Mode:** CPOL=? CPHA=? (Mode ?)
- **Max clock:** <e.g. 10 MHz>
- **Bit order:** MSB first
- **CS active:** low

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00`  | NAME | R   | `0x00`| |

### Bit Fields

#### `REGISTER_NAME` (`0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4  | FIELD_A | |
| 3:0  | FIELD_B | |

## Pin Capabilities

| Capability | Support |
|-----------|---------|
| Pin count | <N> |
| Ports | <N> × 8-bit |
| Direction | per-pin / per-port / output-only / input-only |
| Pull-up | yes / no |
| Pull-down | yes / no |
| Open-drain output | yes / no |
| Push-pull output | yes / no |
| Interrupt output | yes / no — active-low open-drain |
| Interrupt modes | edge (rising / falling / both) / level (high / low) |
| Drive strength | yes / no |

## Initialization Sequence

1. <step>
2. <step>
3. Wait <N> ms for <reason>

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal.

The defining characteristic of an IO expander driver is that it exposes individual **Pin objects** that implement each platform's native GPIO interface — see the GPIO Interface section below. Users obtain a pin from the driver and use it exactly like a hardware GPIO pin.

### Minimal

Goal: expose all chip pins as GPIO objects with direction control and value read/write. No interrupt or pull configuration required.

**Driver API**

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | Reset to all-inputs with safe defaults |
| `pin` | `n: int` | Pin | Return a Pin proxy for pin number `n` (0-based) |
| `read_port` | `port: int` | int | Read all 8 pins of a port as a bitmask |
| `write_port` | `port: int`, `mask: int` | — | Write all 8 output pins of a port at once |

**Sensible defaults:** <!-- list register settings baked in for Minimal, e.g. all pins as inputs, interrupts disabled -->

**Pin API — Minimal**

| Operation | MicroPython | CircuitPython | C++ | Node.js | Rust |
|-----------|-------------|---------------|-----|---------|------|
| Get pin | `chip.pin(n, Pin.IN)` | `chip.pin(n)` | `chip.pin(n)` | `chip.pin(n, 'in')` | `chip.pin(n)` |
| Set direction | `pin.init(Pin.OUT)` | `pin.direction = Direction.OUTPUT` | `pin.mode(OUTPUT)` | `pin.setDirection('out', cb)` | *(type-level: `OutputPin` vs `InputPin`)* |
| Set high | `pin.on()` | `pin.value = True` | `pin.high()` | `pin.writeSync(1)` | `pin.set_high()?` |
| Set low | `pin.off()` | `pin.value = False` | `pin.low()` | `pin.writeSync(0)` | `pin.set_low()?` |
| Read | `pin.value()` | `pin.value` | `pin.read()` | `pin.readSync()` | `pin.is_high()?` |
| Toggle | `pin.toggle()` | *(manual)* | `pin.toggle()` | *(manual)* | *(manual)* |
| Release / close | — | `pin.deinit()` | — | `pin.unexport()` | *(drop)* |

### Full

Goal: expose complete chip functionality. Extends Minimal.

**Driver API additions**

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `configure_interrupt` | `port`, `trigger`, `callback` | — | Enable interrupt output; trigger: RISING, FALLING, BOTH, LEVEL_HIGH, LEVEL_LOW |
| `clear_interrupt` | — | int | Read and clear interrupt flag register; returns bitmask of triggered pins |

**Pin API additions — Full**

| Operation | MicroPython | CircuitPython | C++ | Node.js | Rust |
|-----------|-------------|---------------|-----|---------|------|
| Set pull | `pin.init(pull=Pin.PULL_UP)` | `pin.pull = Pull.UP` | `pin.mode(INPUT_PULLUP)` | *(via driver)* | *(trait: `embedded_hal::digital::InputPin`)* |
| Set drive mode | *(via driver)* | `pin.drive_mode = DriveMode.OPEN_DRAIN` | `pin.mode(OUTPUT_OPEN_DRAIN)` | *(via driver)* | *(via driver)* |
| Attach interrupt | `pin.irq(handler, Pin.IRQ_RISING)` | *(via driver)* | `pin.attachInterrupt(handler, RISING)` | `pin.watch(handler)` | *(via driver callback)* |

**Additional configuration options:** <!-- pull resistors, polarity inversion, drive strength, per-pin interrupt mask, etc. -->

## GPIO Interface

This section defines the per-platform contracts for Pin objects. See `AGENTS.md` for implementation patterns.

### Python — MicroPython

Pin objects implement a compatible subset of `machine.Pin`. The `id` parameter to `Pin.__init__` is replaced by the chip's `pin(n)` factory — users never instantiate Pin directly.

Required interface (Minimal): `init(mode)`, `value([x])`, `on()`, `off()`, `toggle()`  
Full adds: `init(mode, pull)`, `irq(handler, trigger)` — constants `Pin.PULL_UP`, `Pin.PULL_DOWN`, `Pin.IRQ_RISING`, `Pin.IRQ_FALLING`

### Python — CircuitPython

Pin objects implement `digitalio.DigitalInOut`.

Required interface (Minimal): `direction` (read/write), `value` (read/write), `switch_to_input()`, `switch_to_output()`  
Full adds: `pull` (read/write), `drive_mode` (read/write)

### Python — Linux

Same interface as MicroPython for consistency. The Linux target is host-only; interrupt support via a polling thread is acceptable and should be documented.

### C++

Pin objects expose an `IOExpanderPin` proxy class. Arduino GPIO constants are reused (`INPUT`, `OUTPUT`, `INPUT_PULLUP`, `HIGH`, `LOW`).

Required interface (Minimal):
```cpp
class IOExpanderPin {
public:
    void mode(uint8_t mode);         // INPUT, OUTPUT
    void write(uint8_t value);       // HIGH, LOW
    uint8_t read();
    void high();
    void low();
    void toggle();
};
```
Full adds: `INPUT_PULLUP` / `INPUT_PULLDOWN` support in `mode()`; `attachInterrupt(callback, mode)` / `detachInterrupt()`.

The same `IOExpanderPin` class is used on Arduino, Linux GCC, and Zephyr — guarded with platform `#ifdef` only where interrupt delivery differs.

### Node.js

Pin objects implement the [`onoff`](https://www.npmjs.com/package/onoff) `Gpio` interface subset so they are drop-in replacements.

Required interface (Minimal): `readSync()`, `writeSync(value)`, `read(callback)`, `write(value, callback)`, `direction` property, `unexport()`  
Full adds: `watch(callback)`, `unwatch(callback)`, `setActiveLow(invert)`

### Rust

Output pins implement `embedded_hal::digital::OutputPin`; input pins implement `embedded_hal::digital::InputPin`. Both are in `core` — no `std` required.

The driver stores its I2C bus in a `core::cell::RefCell<I2C>`. Pin objects hold a shared reference `&'_ RefCell<...>` and borrow it only during each operation. Multiple pin objects may coexist, but simultaneous access from different execution contexts is not safe — document this in the driver's module-level doc comment.

```rust
// Minimal — output pin
impl<I2C: I2c> embedded_hal::digital::OutputPin for OutputPin<'_, I2C> {
    type Error = I2C::Error;
    fn set_high(&mut self) -> Result<(), Self::Error> { ... }
    fn set_low(&mut self) -> Result<(), Self::Error> { ... }
}

// Minimal — input pin
impl<I2C: I2c> embedded_hal::digital::InputPin for InputPin<'_, I2C> {
    type Error = I2C::Error;
    fn is_high(&self) -> Result<bool, Self::Error> { ... }
    fn is_low(&self) -> Result<bool, Self::Error> { ... }
}
```

Full adds `embedded_hal::digital::StatefulOutputPin` where the chip supports reading back output state.

## Data Conversion

<!-- Bitmask / port-byte mapping to pin indices. Any polarity-inversion notes. -->

```
pin n  →  port n/8, bit n%8
```

## Node-RED

Node name: `periph-<chip>`  
Package: `node-red-contrib-periph-io-expander`

| Input `msg.payload` | Output `msg.payload` | Notes |
|--------------------|----------------------|-------|
| `{ pin: N, value: 0\|1 }` | — | Set output pin |
| `{ pin: N }` | `{ pin: N, value: 0\|1 }` | Read input pin |
| `{ port: N }` | `{ port: N, value: 0x?? }` | Read full port |

<!-- Describe the config panel: I2C address, bus number, number of pins, etc. -->

### Demo flow

<!-- Describe the Node-RED demo flow. -->

## Examples

### Demo

<!-- Describe the demo scenario. Suggested: toggle outputs in a pattern while reading back input pin states, printing mismatches. Shows both read and write paths. -->

## Timing Constraints

<!-- Startup time, I²C transaction latency, interrupt response latency. -->

## Implementation Notes

<!-- Quirks, errata, or non-obvious datasheet behavior. -->

## Sigrok Decoder

<!-- One paragraph describing what the sigrok decoder annotates: which registers,
     which bit fields, which computed values are shown. Mention input transport
     (i2c or logic), the decoder id, and the addresses / channels it matches. -->

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/io_expander/<chip>.py` — Google-style docstring on every class and public method; includes `_Pin` inner class
- [ ] Examples `python/examples/io_expander/<chip>/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/io_expander/<chip>/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/io_expander/<chip>/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/io_expander/<chip>_test.py` (MicroPython)
- [ ] Tests `python/tests/io_expander/<chip>_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/io_expander/<chip>_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/io_expander/<Chip>.h` — Doxygen `/** @brief */` on every class and public method; includes `IOExpanderPin` nested class
- [ ] Driver `cpp/src/chips/io_expander/<Chip>.cpp`
- [ ] Examples `cpp/examples/<Chip>_Minimal/<Chip>_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/<Chip>_Complete/<Chip>_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/<Chip>_Demo/<Chip>_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/<Chip>_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/<Chip>_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/<Chip>_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/io_expander/<chip>_test/<chip>_test.ino` (Arduino)
- [ ] Tests `cpp/tests/io_expander/<chip>_test_linux/<chip>_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/io_expander/<chip>_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/io_expander/<chip>.js` — JSDoc on every class and exported method; includes `_Pin` inner class
- [ ] Examples `nodejs/packages/periph/examples/io_expander/<chip>/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/io_expander/<chip>/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/io_expander/<chip>/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/io_expander/<chip>_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-io-expander/nodes/<chip>/<chip>.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-io-expander/nodes/<chip>/<chip>.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-io-expander/examples/<chip>/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/io_expander/<chip>.rs` — `//!` module doc + `///` on every `pub` item; includes `ExPin` type implementing `OutputPin` / `InputPin`
- [ ] Re-export from `rust/periph/src/chips/io_expander/mod.rs`: `pub use <chip>::{<Chip>Minimal, <Chip>Full, ExPin};`
- [ ] Examples `rust/examples/<chip>_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/<chip>_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/<chip>_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/io_expander/<chip>_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/io_expander/<chip>_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/io_expander/<Chip>Minimal.java` — Javadoc on every class and public method; includes `Pin` inner class
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/io_expander/<Chip>Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/io_expander/<Chip>Minimal.kt` — KDoc on every class and public method; includes `Pin` inner class
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/io_expander/<Chip>Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/io_expander/<Chip>Minimal.groovy` — Groovydoc on every class and public method; includes `Pin` inner class
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/io_expander/<Chip>Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/io_expander/<chip>/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/io_expander/<chip>/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/io_expander/<chip>/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/io_expander/<chip>/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/io_expander/<chip>/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/io_expander/<chip>/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/io_expander/<chip>/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/io_expander/<chip>/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/io_expander/<chip>/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/io_expander/<chip>/<Chip>Test.java` (Pi hardware, JBang)

### Sigrok
- [ ] Decoder `sigrok/<chip>/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/<chip>/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only
