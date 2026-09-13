# Chip Spec: TPIC6B595

**Manufacturer:** Texas Instruments
**Datasheet:** `datasheets/io_expander/tpic6b595.pdf`
**Category:** `io_expander`
**Transports:** SiPo (see `specs/transport_sipo.md`)

## Overview

The TPIC6B595 is a monolithic, high-voltage, medium-current power 8-bit shift register intended for driving relays, solenoids, LED clusters, and other medium-current or high-voltage loads directly from a microcontroller. Internally it is an 8-bit serial-in shift register feeding an 8-bit D-type storage register (classic SIPO topology, protocol-compatible with the SN74HC595 family — see `specs/transport_sipo.md`), but instead of small-signal push-pull outputs it drives eight independent low-side, open-drain DMOS transistor outputs (DRAIN0–DRAIN7) rated 50 V / 150 mA continuous each, with a built-in avalanche voltage clamp for inductive-load transient protection. A write is a two-step SPI-shaped operation: shift a byte (or one byte per cascaded device) in on SER IN/SRCK, then pulse the register clock (RCK) to latch the shifted data into the storage register that actually drives the outputs. An active-low output enable (`G`) forces every output off without disturbing the storage register — useful for glitch-free power-up and for global PWM-style dimming — and an active-low shift-register clear (`SRCLR`) resets the shift register alone. Devices cascade indefinitely: SER OUT of one device wires to SER IN of the next, letting a single SPI-shaped transfer address any number of chained TPIC6B595s.

Because the chip has no I²C/SPI address and no addressable register map of its own, this driver is a thin layer over the SiPo connection: it owns the per-device shadow byte(s), presents each output as a GPIO-style `OutputPin`, and handles the wire-order reversal that cascading requires (see [Data Conversion](#data-conversion)).

## Transport Configuration

### SiPo

This chip is the reference device for `specs/transport_sipo.md` — wire it exactly as that spec describes:

| Pin | Function | Notes |
|-----|----------|-------|
| SER IN | Serial data in | SiPo connection MOSI (hardware or bit-banged) |
| SRCK | Shift register clock | SiPo connection SCK (hardware or bit-banged) |
| RCK | Register clock | Mandatory plain GPIO; latches shifted data into the storage/output register |
| SRCLR | Shift-register clear, active-low | Optional but recommended — lets `clear()`/power-up reset the shift register deterministically |
| G | Output enable, active-low | Optional but recommended — lets `set_output_enable()` blank all outputs globally without touching the storage register |
| SER OUT | Serial data out (cascade) | Wire to the next device's SER IN; not read by the connection |

Recommended default: hardware SPI at 1 MHz, mode 0 (CPOL=0, CPHA=0), MSB-first — comfortably inside the datasheet's 20 ns setup/hold and 40 ns minimum pulse width. Software (bit-banged) SPI works identically at a much lower, GPIO-call-bound rate; see `specs/transport_sipo.md`'s [Hardware vs. Software SPI](transport_sipo.md#hardware-vs-software-spi).

**Cascading:** the number of chained devices is fixed at construction (`num_devices`, default 1), analogous to a NeoPixel strip's pixel count — see `specs/led/ws2812b.md`. There is no wiring or connection-config difference between one device and many; only the buffer size and the wire-order handling change.

## Pin Capabilities

| Capability | Support |
|-----------|---------|
| Pin count | 8 × `num_devices` (DRAIN0–DRAIN7 per cascaded device) |
| Ports | `num_devices` × 8-bit |
| Direction | Output-only, fixed — no direction register exists |
| Pull-up | No |
| Pull-down | No |
| Open-drain output | Yes — every output is a low-side, current-sinking DMOS transistor (50 V / 150 mA continuous, ~500 mA typical current limit) |
| Push-pull output | No — outputs can only sink, never source; an external pull-up/load supply is required for the "off"/high state |
| Interrupt output | No — the chip has no input pins and no INT line |
| Interrupt modes | N/A |
| Drive strength | No — fixed by the DMOS current limit; not configurable |

## Shift Register Layout

The TPIC6B595 has no addressable registers — no address, no register map, just an 8-bit shift register cascaded into an 8-bit storage register per device, exactly as described in `specs/transport_sipo.md`. Writing `num_devices` bytes shifts the whole chain and, on the RCK pulse that follows, latches all of it into the output storage registers simultaneously.

### Per-device byte (DRAIN mapping)

| Bit | DRAIN pin |
|-----|-----------|
| 7   | DRAIN7 |
| 6   | DRAIN6 |
| 5   | DRAIN5 |
| 4   | DRAIN4 |
| 3   | DRAIN3 |
| 2   | DRAIN2 |
| 1   | DRAIN1 |
| 0   | DRAIN0 |

A bit of `1` turns the corresponding DMOS output ON (sink-current capable, pulls the pin toward GND through an external load); a bit of `0` turns it OFF (high-impedance).

## Initialization Sequence

1. Construct/configure the `SiPoConnection` for the target platform (hardware or software SPI, RCK, and — recommended — SRCLR/G) per `specs/transport_sipo.md`. The connection's own `init()` already drives RCK LOW, SRCLR HIGH (if configured), and G LOW (if configured).
2. Construct `Tpic6b595Minimal(connection, num_devices=1)`. The constructor allocates a `num_devices`-byte shadow register, all zero.
3. If SRCLR is wired, the constructor calls `connection.clear()` to reset the shift register — TI recommends clearing the device during power-up (datasheet §7.3.2).
4. The constructor then writes the all-zero shadow buffer (reversed per [Data Conversion](#data-conversion)) via `connection.write()` and pulses RCK, guaranteeing every output starts OFF regardless of the chip's undefined power-on storage-register content (`SRCLR` alone does **not** clear the storage register — see `specs/transport_sipo.md`, "Clear and Output Enable").

No additional delay is required; the chip is usable immediately after step 4.

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal.

The defining characteristic of an IO expander driver is that it exposes individual **Pin objects** that implement each platform's native GPIO interface — see [GPIO Interface](#gpio-interface). Because every TPIC6B595 pin is a fixed output, these Pin objects only ever implement the `OutputPin` side of that contract.

### Minimal

Goal: drive up to 8 outputs (or more, if cascaded) with sensible defaults — construct, set a pin or fill, done. No output-enable or shift-register-clear control required beyond the safe power-up sequence already performed at construction.

**Driver API**

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `__init__` | `connection`, `num_devices: int = 1` | — | Runs the [Initialization Sequence](#initialization-sequence) above |
| `pin` | `n: int` | Pin | Return an `OutputPin` proxy for global pin index `n` (0-based across all cascaded devices; device = `n // 8`, bit = `n % 8`) |
| `write_port` | `port: int`, `mask: int` | — | Write all 8 outputs of cascaded device `port` (0 = nearest the controller) from `mask`; updates the shadow register, re-transmits the whole chain, and latches |
| `fill` | `value: bool` | — | Set every pin on every cascaded device to `value`; sends immediately — the fast path for "all on"/"all off" |
| `off` | — | — | Equivalent to `fill(False)` — the safe/default state, also used at construction |

**Sensible defaults:** all outputs OFF at construction (shadow register zeroed and latched); SRCLR cleared first if wired; output enable left at its connection-level default (G driven LOW / enabled if wired).

**Pin API — Minimal**

| Operation | MicroPython | CircuitPython | C++ | Node.js | Rust |
|-----------|-------------|---------------|-----|---------|------|
| Get pin | `chip.pin(n)` | `chip.pin(n)` | `chip.pin(n)` | `chip.pin(n)` | `chip.pin(n)` |
| Set high (ON, sinks current) | `pin.on()` | `pin.value = True` | `pin.high()` | `pin.writeSync(1)` | `pin.set_high()?` |
| Set low (OFF) | `pin.off()` | `pin.value = False` | `pin.low()` | `pin.writeSync(0)` | `pin.set_low()?` |
| Read back (shadow, not a bus read) | `pin.value()` | `pin.value` | `pin.read()` | `pin.readSync()` | *(n/a — write-only trait)* |
| Toggle | `pin.toggle()` | *(manual)* | `pin.toggle()` | *(manual)* | *(manual)* |
| As `OutputPin` | `pin` (duck-typed) | `pin` (duck-typed) | `pin` (`OutputPin*`) | `pin` (duck-typed) | `pin` (`ExPin`) |

The chip has no bus read-back at all (see `specs/transport_sipo.md`: "This connection is write-only"), so every "read" above returns the driver's own shadow-register bit, not a live measurement — document this prominently in each platform's inline API docs. Pins implement the project's `OutputPin` interface (`set(high)` / `embedded_hal::digital::OutputPin` in Rust) and can be used directly as `en_pin`/`rck`/etc. in another chip's `Connection`, same as any other IO-expander output pin.

### Full

Goal: expose the shift-register-clear and output-enable hardware features, plus a bulk multi-device write. Extends Minimal.

**Driver API additions**

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `clear` | — | — | Pulses SRCLR to clear the shift register only (per `specs/transport_sipo.md`, the storage register/outputs are unaffected until the next RCK pulse); raises/returns an error if SRCLR was not wired |
| `set_output_enable` | `enabled: bool` | — | Forwards to `connection.set_output_enable()`; drives G LOW (`enabled=True`) or HIGH (`enabled=False`) to force every output off without touching the shadow register — the datasheet's documented use case is global PWM-style dimming (§7.3.3); raises/returns an error if G was not wired |
| `write_all` | `values: Sequence[int]` (length `num_devices`) | — | Write every cascaded device's byte in one call (index 0 = nearest the controller); updates the whole shadow register and performs exactly one transmit + latch |

**Pin API additions — Full:** none. Every TPIC6B595 pin is a fixed, capability-less output; there is nothing left to configure per-pin (no pull, no drive mode, no watch/interrupt — the chip has no input pins at all).

**Additional configuration options:** shift-register clear (`clear()`) and global output enable/PWM dimming (`set_output_enable()`), both of which map directly onto the SiPo connection's own optional SRCLR/G lines.

## GPIO Interface

This section defines the per-platform contracts for Pin objects. See `AGENTS.md` for implementation patterns.

### Python — MicroPython

Pin objects implement the output-only subset of `machine.Pin`.

Required interface (Minimal): `value([x])`, `on()`, `off()`, `toggle()`, `set(high: bool)`

`set(high)` delegates to `on()` / `off()` and satisfies the `OutputPin` duck-type contract. There is no `init(mode)` — direction is fixed, so it is omitted rather than accepted and ignored.

### Python — CircuitPython

Pin objects implement the output-only subset of `digitalio.DigitalInOut`.

Required interface (Minimal): `value` (read/write), `set(high: bool)`

`direction`/`switch_to_input()` are intentionally not implemented (there is no input mode); `set(high)` assigns `self.value = high` and satisfies the `OutputPin` duck-type contract.

### Python — Linux

Same interface as MicroPython for consistency.

### C++

Pin objects expose an `IOExpanderPin` proxy class. Arduino GPIO constants are reused (`HIGH`, `LOW`).

Required interface (Minimal):
```cpp
class IOExpanderPin : public OutputPin {   // inherits: virtual void set(bool high) = 0
public:
    void write(uint8_t value);       // HIGH, LOW
    uint8_t read();                  // shadow read-back, not a bus read
    void high();
    void low();
    void toggle();
    void set(bool high) override;    // OutputPin impl → high() / low()
};
```

The same `IOExpanderPin` class is used on Arduino, Linux GCC, Zephyr, ESP-IDF, and Pico SDK — no platform `#ifdef` is needed since there is no interrupt delivery to differ.

### Node.js

Pin objects expose `async read()`/`write(value)` (not synchronous `readSync()`/`writeSync()` — `Connection` is async everywhere).

Required interface (Minimal): `async read()`, `async write(value)`, `stop()`, `asGpio()`

`asGpio()` returns a synchronous facade implementing the [`opengpio`](https://www.npmjs.com/package/opengpio) `Output` shape (boolean `value` setter, `stop()`) so a pin can be passed anywhere real opengpio-shaped GPIO is expected — e.g. as `rck`/`g` in another `SiPoConnection`, or as `enPin` elsewhere. The facade reads back the shadow register directly (authoritative — there is no bus round-trip to be eventually-consistent about, unlike a real input pin).

### JVM

`Pin` is an inner class of the driver implementing the project's `OutputPin` interface.

```java
class Pin implements OutputPin {        // OutputPin: void set(boolean high)
    boolean read();                     // shadow read-back, not a bus read
    @Override void set(boolean high);   // OutputPin impl → shift + latch HIGH / LOW
    @Override void close();             // AutoCloseable — no-op for virtual pins
}
```

Because `Pin implements OutputPin`, it can be passed directly as `rck`/`g`/`enPin` to another chip's `Connection`.

### Rust

Output pins implement `embedded_hal::digital::OutputPin`. There is no `InputPin` implementation anywhere in this driver — the chip has no readable pins.

The driver stores its `SiPoConnection` in a `core::cell::RefCell<CONN>`. Pin objects hold a shared reference `&'_ RefCell<...>` and borrow it only during each operation; document in the module-level doc comment that simultaneous access from different execution contexts is not safe.

```rust
impl<CONN: SiPoWrite> embedded_hal::digital::OutputPin for ExPin<'_, CONN> {
    type Error = CONN::Error;
    fn set_high(&mut self) -> Result<(), Self::Error> { ... }
    fn set_low(&mut self) -> Result<(), Self::Error> { ... }
}
```

Full adds `embedded_hal::digital::StatefulOutputPin`, reading back the shadow register (the only "state" that exists).

## Data Conversion

```
pin n  →  device n // 8, bit n % 8   (bit 7 = DRAIN7 ... bit 0 = DRAIN0)
```

**Cascading reverses the wire order.** Within one device, a byte shifted MSB-first ends up exactly as sent: bit 7 (sent first) travels furthest along the internal 8-flip-flop chain and lands on DRAIN7; bit 0 (sent last) barely moves and lands on DRAIN0. Cascading N devices chains those flip-flops end to end (SER OUT → SER IN), so whichever byte is transmitted **first** over the wire is pushed all the way through every downstream device's register and ends up in the **farthest** device from the controller; the byte transmitted **last** barely moves and ends up in the **nearest** device. Concretely, for device index 0 = nearest the controller:

```
wire_bytes = [shadow[num_devices - 1], shadow[num_devices - 2], ..., shadow[1], shadow[0]]
connection.write(bytes(wire_bytes))   # then pulse RCK — connection.write() does this already
```

Every write (`write_port`, `fill`, `write_all`, or a single pin's `set()`) must build this reversed buffer from the *entire* shadow register and retransmit it — there is no way to update only one cascaded device's outputs without re-shifting the whole chain, since a device's bits are pushed downstream by every subsequent clock.

## Node-RED

Node name: `periph-tpic6b595`
Package: `node-red-contrib-periph-io-expander`

| Input `msg.payload` | Output `msg.payload` | Notes |
|--------------------|----------------------|-------|
| `{ pin: N, value: 0\|1 }` | — | Set pin N (global index across all cascaded devices) |
| `{ port: N, value: 0x?? }` | — | Set all 8 outputs of cascaded device N at once |
| `{ command: "off" }` | — | Turn every output off (`fill(false)`) |
| `{ outputEnable: true\|false }` | — | Global blank / un-blank via G (Full only) |

Config panel: SPI bus/device number (or bit-banged SER IN/SRCK pin numbers), RCK pin number, optional SRCLR/G pin numbers, and number of cascaded devices.

### Demo flow

An Inject node fires every 150 ms driving a "knight rider" chase pattern across two cascaded devices (16 outputs); a second Inject node toggles `{ outputEnable: false }` for 500 ms every 5 seconds to demonstrate global blanking without disturbing the chase pattern's state.

## Examples

### Demo

Reproduce the datasheet's own typical application (§8.2, Figure 8-1): two cascaded TPIC6B595s driving 16 LEDs as an automotive-cluster-style indicator bank. Run a chase ("knight rider") pattern that walks a single lit LED across all 16 outputs and back, using `write_all()` each step so both devices latch together. Every few full sweeps, call `set_output_enable(False)` for half a second to demonstrate glitch-free global blanking (the chase pattern's shadow state is untouched — the LEDs simply resume exactly where they left off when re-enabled), and print the current pattern position and output-enable state at each step to show both API paths together.

## Timing Constraints

Protocol-level timing (SER IN/SRCK setup & hold, minimum SRCK/RCK/SRCLR pulse width) belongs entirely to the SiPo transport and is already documented and implemented there — see `specs/transport_sipo.md`'s Timing section. This chip adds no independent register-access timing of its own (there is no address phase, no conversion delay, no busy flag to poll).

The following are informational electrical characteristics from the datasheet, relevant to load design rather than to driver sequencing:

- Output switching (`V_CC` = 5 V, `T_C` = 25 °C): rise time `t_r` and fall time `t_f` ≈ 200 ns typical; propagation delay from a `G` transition to the output, `t_PLH` ≈ 150 ns / `t_PHL` ≈ 90 ns typical
- Recommended logic supply: 4.5 V–5.5 V; operation up to 6 V works but the datasheet flags reduced long-term reliability above 5.5 V (§7.4.2) — not enforced by the driver
- Continuous drain current: 150 mA per output rated, ~500 mA typical current limit at `T_C` = 25 °C; the current limit and the number of outputs that can conduct simultaneously derate with case temperature (datasheet Figures 5-6/5-7) — a hardware/PCB concern, not something the driver can or should enforce
- No thermal-shutdown protection exists on-chip; document this in inline API docs as a caller responsibility

## Implementation Notes

- **Outputs only sink current — they never source it.** Writing a pin `1` turns its DMOS transistor ON, letting it pull the DRAIN pin toward GND through whatever external load is attached; writing `0` leaves it high-impedance. Wire loads accordingly: an LED's anode goes to the supply (through a series resistor) and its cathode to the DRAIN pin (`1` = ON); a relay coil's other end goes to the supply rail, not GND.
- **Cascaded writes always retransmit the whole chain**, reversed as described in [Data Conversion](#data-conversion) — the single most common bug when porting a driver from a single-device shift register (74HC595-style) mental model that only ever wrote one byte.
- **`clear()` does not blank the outputs.** Per `specs/transport_sipo.md`, SRCLR only clears the shift register; the storage register (and therefore the DRAIN outputs) keeps its last-latched value until the next RCK pulse. This is why the constructor always follows a `clear()` (if SRCLR is wired) with an explicit all-zero `write`/latch — never rely on `clear()` alone to reach an all-off state.
- **`set_output_enable(False)` is non-destructive.** It forces every output off via `G` without touching the shadow register or the storage register's contents — exactly the mechanism the datasheet recommends for global PWM-style brightness dimming (§7.3.3). Toggling it back to `True` resumes exactly the previously-latched pattern.
- **No per-device addressing exists on the wire.** Every write shifts and latches the entire cascade at once; there is no way to touch a single downstream device without re-sending the whole chain's data, which is why `write_port()`/`write_all()` always rebuild and retransmit the full shadow register.

## Sigrok Decoder

Two-layer stack: `logic → sipo → tpic6b595`.

The `sipo` transport decoder (id `sipo`, input `['logic']`, see `specs/transport_sipo.md`) handles all SER IN/SRCK/RCK/SRCLR/G timing and framing; it emits `OUTPUT_PYTHON` packets — `('LATCH', bytes)` on every RCK rising edge (the bytes shifted in since the previous latch, in wire order) and `('CLEAR', None)` on every SRCLR LOW pulse.

The `tpic6b595` chip decoder (id `tpic6b595`, input `['sipo']`) reverses each `LATCH` payload back into per-device order (device 0 = nearest the controller, per [Data Conversion](#data-conversion)) and annotates, for every cascaded device, which of DRAIN0–DRAIN7 are ON vs OFF. It emits a `CLEARED` annotation on `CLEAR` packets. A configurable decoder option (`num_devices`, default 1) tells it how many bytes to expect per `LATCH`; it emits a warning annotation if a `LATCH` payload's length is not an exact multiple of `num_devices`.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/io_expander/tpic6b595.py` — Google-style docstring on every class and public method; includes `_Pin` inner class
- [ ] Examples `python/examples/io_expander/tpic6b595/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/io_expander/tpic6b595/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/io_expander/tpic6b595/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/io_expander/tpic6b595_test.py` (MicroPython)
- [ ] Tests `python/tests/io_expander/tpic6b595_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/io_expander/tpic6b595_test_linux.py` (Linux)
- [ ] Unit test `python/tests/io_expander/tpic6b595_test_unit.py` — deferred; no SiPo/SPI-connection mock exists yet in this codebase (see `specs/testing_framework.md`; same repo-wide gap noted in `specs/accelerometer/adxl362.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/io_expander/tpic6b595/tpic6b595.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/io_expander/tpic6b595/tpic6b595_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped (pin write, write_port, write_all, clear, set_output_enable)
- [ ] Generated `python/uiflow1/io_expander/tpic6b595/tpic6b595.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/io_expander/tpic6b595/Tpic6b595.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/io_expander/tpic6b595/Tpic6b595.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/io_expander/TPIC6B595.h` — Doxygen `/** @brief */` on every class and public method; includes `IOExpanderPin` nested class
- [ ] Driver `cpp/src/chips/io_expander/TPIC6B595.cpp`
- [ ] Examples `cpp/examples/arduino/io_expander/TPIC6B595/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/io_expander/TPIC6B595/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/io_expander/TPIC6B595/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/linux/io_expander/TPIC6B595/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/io_expander/TPIC6B595/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/io_expander/TPIC6B595/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/io_expander/TPIC6B595/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/io_expander/TPIC6B595/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/io_expander/TPIC6B595/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/io_expander/TPIC6B595/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/io_expander/TPIC6B595/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/io_expander/TPIC6B595/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/io_expander/TPIC6B595/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/io_expander/TPIC6B595/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/io_expander/TPIC6B595/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/io_expander/tpic6b595_test/tpic6b595_test.ino` (Arduino)
- [ ] Tests `cpp/tests/io_expander/tpic6b595_test_linux/tpic6b595_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/io_expander/tpic6b595_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/io_expander/tpic6b595_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/io_expander/tpic6b595_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Unit test `cpp/tests/io_expander/tpic6b595_test_unit/tpic6b595_test_unit.cpp` — deferred, same reason as Python

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/io_expander/tpic6b595.js` — JSDoc on every class and exported method; includes `_Pin` inner class
- [ ] Examples `nodejs/packages/periph/examples/io_expander/tpic6b595/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/io_expander/tpic6b595/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/io_expander/tpic6b595/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/io_expander/tpic6b595_test.js`
- [ ] Unit test `nodejs/tests/io_expander/tpic6b595_test_unit.js` — deferred, same reason as Python

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-io-expander/nodes/tpic6b595/tpic6b595.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-io-expander/nodes/tpic6b595/tpic6b595.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-io-expander/examples/tpic6b595/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/io_expander/tpic6b595.rs` — `//!` module doc + `///` on every `pub` item; includes `ExPin` implementing `OutputPin`
- [ ] Re-export from `rust/periph/src/chips/io_expander/mod.rs`: `pub use tpic6b595::{Tpic6b595Minimal, Tpic6b595Full, ExPin};`
- [ ] Examples `rust/examples/linux/io_expander/tpic6b595/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/io_expander/tpic6b595/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/io_expander/tpic6b595/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Examples `rust/examples/embedded/esp32s3/io_expander/tpic6b595/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/embedded/esp32s3/io_expander/tpic6b595/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/embedded/esp32s3/io_expander/tpic6b595/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/io_expander/tpic6b595_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/io_expander/tpic6b595_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` — deferred, same reason as above

### Go
- [ ] Driver `go/periph/chips/io_expander/tpic6b595.go` — Go doc comment on every exported type and method; `TPIC6B595Pin` proxy type holds back-reference to driver
- [ ] Examples `go/examples/linux/io_expander/tpic6b595/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/io_expander/tpic6b595/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/io_expander/tpic6b595/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/io_expander/tpic6b595/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/io_expander/tpic6b595/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/io_expander/tpic6b595/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/io_expander/tpic6b595_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/io_expander/tpic6b595_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo / Pico W)
- [ ] Unit test `go/periph/chips/io_expander/tpic6b595_test.go` — deferred, same reason as above

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/io_expander/Tpic6b595Minimal.java` — Javadoc on every class and public method; includes `Pin` inner class
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/io_expander/Tpic6b595Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/io_expander/Tpic6b595Minimal.kt` — KDoc on every class and public method; includes `Pin` inner class
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/io_expander/Tpic6b595Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/io_expander/Tpic6b595Minimal.groovy` — Groovydoc on every class and public method; includes `Pin` inner class
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/io_expander/Tpic6b595Full.groovy` — Groovydoc on every class and public method
- [ ] `jvm/periph-java/src/main/java/module-info.java` updated with `exports it.uhde.periph.chips.io_expander;` (if not already exported)
- [ ] Examples `jvm/examples/java/io_expander/tpic6b595/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/io_expander/tpic6b595/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/io_expander/tpic6b595/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/io_expander/tpic6b595/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/io_expander/tpic6b595/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/io_expander/tpic6b595/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/io_expander/tpic6b595/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/io_expander/tpic6b595/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/io_expander/tpic6b595/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/io_expander/tpic6b595/Tpic6b595Test.java` (Linux hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/io_expander/Tpic6b595Test.java` — deferred, same reason as above
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/io_expander/Tpic6b595Test.kt` — deferred, same reason as above
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/io_expander/Tpic6b595Spec.groovy` — deferred, same reason as above

### Sigrok
- [ ] Decoder `sigrok/tpic6b595/__init__.py` — module docstring describing transport input (`sipo`), the `num_devices` option, and what is annotated
- [ ] Decoder `sigrok/tpic6b595/pd.py` — reverses `LATCH` payloads into per-device order and annotates DRAIN0–DRAIN7 ON/OFF per cascaded device; annotates `CLEARED` on `CLEAR` packets; produces `OUTPUT_ANN` only
