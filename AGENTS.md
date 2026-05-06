# AGENTS.md

This file provides guidance to OpenCode when implementing chip and transport drivers in this repository.

Read [CLAUDE.md](CLAUDE.md) first for repo layout, category structure, and the Minimal/Full implementation pattern. Read [TESTING.md](TESTING.md) for the per-platform test runners and test-file templates. This file only covers what is specific to writing code.

## Role

OpenCode implements. It does not modify specs, datasheets, CLAUDE.md, or transport implementations unless a spec explicitly requires a transport change.

The spec in `specs/<category>/<chip>.md` is the single source of truth. Implement exactly what it defines — no more, no less.

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

Every chip is implemented across all four languages and every supported platform within each language. Replace `<chip>` with the lowercase chip name (e.g. `ina226`) and `<Chip>` with the title-case chip name (e.g. `INA226`).

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

For test file paths and runner scripts, see [TESTING.md](TESTING.md). When adding a chip, every platform's test must be added too — Linux, MicroPython, CircuitPython, Arduino, Zephyr, Node.js, Rust Linux, and Rust ESP32-S3.

Remove `.gitkeep` from a target directory when adding the first real file.

## Transport interface

Chip drivers must only call the three transport methods. Never import or reference a concrete transport class.

```python
# Python
transport.write(data: bytes)
transport.read(n: int) -> bytes
transport.write_read(data: bytes, n: int) -> bytes
```

```cpp
// C++ — same signatures used on Arduino, Linux, and Zephyr
transport.write(const uint8_t* data, size_t len);
transport.read(uint8_t* buf, size_t len);
transport.write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len);
```

```js
// Node.js — camelCase, Buffers
transport.write(buffer)                       // Buffer in
transport.read(length)                        // returns Buffer
transport.writeRead(writeBuffer, readLength)  // returns Buffer
```

```rust
// Rust — generic over embedded-hal 1.0; the chip driver owns the bus
use embedded_hal::i2c::I2c;
i2c.write(addr, &buf)?;
i2c.read(addr, &mut buf)?;
i2c.write_read(addr, &reg, &mut buf)?;   // combined write-then-read
```

All INA226-style register reads follow this pattern:

```python
# Python
raw = transport.write_read(bytes([REG_ADDR]), 2)
value = (raw[0] << 8) | raw[1]                # big-endian, unsigned
value = struct.unpack('>h', raw)[0]            # big-endian, signed
```

```js
// Node.js
const raw = transport.writeRead(Buffer.from([REG_ADDR]), 2);
const value = raw.readUInt16BE(0);             // unsigned
const value = raw.readInt16BE(0);              // signed
```

```rust
// Rust
let mut buf = [0u8; 2];
i2c.write_read(addr, &[REG_ADDR], &mut buf)?;
let value = ((buf[0] as u16) << 8) | buf[1] as u16;   // unsigned
let value = value as i16;                              // signed
```

## Class structure

Full extends Minimal by **adding** API surface; it never re-implements what Minimal already does. The mechanism for "extends" varies by language:

```python
# Python — inheritance
class INA226Minimal:
    def __init__(self, transport, ...): ...

class INA226Full(INA226Minimal):
    def __init__(self, transport, ...):
        super().__init__(transport, ...)
```

```cpp
// C++ — public inheritance
class INA226Minimal {
public:
    INA226Minimal(Transport& transport, ...);
protected:
    Transport& _transport;
};
class INA226Full : public INA226Minimal {
public:
    INA226Full(Transport& transport, ...);
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
pub struct Ina226Full<I2C> { inner: Ina226Minimal<I2C>, mode: u8 }
impl<I2C: I2c> Ina226Full<I2C> {
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> { self.inner.voltage() }
    // ... and Full-only methods below
}
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

## Node.js transport interface

JS chip drivers use the same three-method contract, in camelCase (see Transport interface section above).

## Node.js driver structure

Plain JS driver (in `nodejs/packages/periph/src/chips/<category>/<chip>.js`):

```js
'use strict';

class INA226Minimal {
    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) {
        this._transport = transport;
        this._currentLsb = maxCurrent / 32768;
        this._cal = Math.trunc(0.00512 / (this._currentLsb * rShunt));
        this._writeReg(REG_CONFIG, CONFIG_DEFAULT);
        this._writeReg(REG_CAL, this._cal);
    }
    _writeReg(reg, value) { ... }
    _readReg(reg) { ... }
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
- Generic over the I²C bus: `pub struct <Chip>Minimal<I2C> { i2c: I2C, addr: u8, ... }` with `impl<I2C: I2c> <Chip>Minimal<I2C> { ... }`. The chip **owns** the bus.
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
use periph::chips::<category>::<Chip>Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = <Chip>Minimal::new(dev, addr, 0.1, 2.0).expect("init");
    // ... primary-value loop ...
}
```

There is **no** ESP32-S3 example crate — only an ESP32-S3 *test* crate. Embedded smoke testing happens via `rust/test_esp32s3.sh` (see TESTING.md).

## Examples

Each chip has three examples per language (same branch as the driver):

| File | Class | Content |
|------|-------|---------|
| `minimal` | `*Minimal` | Construct with transport, read primary values in a loop, print to serial/stdout. No comments. |
| `complete` | `*Full` | Every method in the API called once — configuration, alerts, shutdown/wake, IDs. No comments. |
| `demo` | `*Full` | The scenario from the spec's Demo section. One short why-comment per logical block. |

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

All tests print `PASS <label>` / `FAIL <label>` lines and end with `===DONE: N passed, N failed===`. See [TESTING.md](TESTING.md) for the full templates and runner scripts.

## Documentation

The spec is the reference documentation. No separate docs directory.

- `minimal` and `complete` examples: no comments
- `demo` example: one short comment per logical block explaining *why* — e.g. `# configure for 16x averaging to reduce noise`, not `# set AVG bits`

## Commit convention

One commit per stage (Minimal, Full). Message format:

```
Add INA226Minimal for MicroPython and Arduino

Co-Authored-By: OpenCode <noreply@opencode.ai>
```
