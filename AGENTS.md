# AGENTS.md

This file provides guidance to OpenCode when implementing chip and transport drivers in this repository.

Read [CLAUDE.md](CLAUDE.md) first for repo layout, category structure, and the Minimal/Full implementation pattern. This file only covers what is specific to writing code.

## Role

OpenCode implements. It does not modify specs, datasheets, CLAUDE.md, or transport implementations unless a spec explicitly requires a transport change.

The spec in `specs/<category>/<chip>.md` is the single source of truth. Implement exactly what it defines — no more, no less.

## Finding the work

When given an issue number, find the "Ready for implementation" comment on that issue. It contains:

- **Spec** — path to the spec file (e.g. `specs/power/ina226.md`)
- **Branch** — the feature branch to check out and commit to
- **Stages** — which stages (Minimal, Full) are requested

Then:

- Python driver: `python/periph/chips/<category>/<chip>.py`
- Python examples: `python/examples/<category>/<chip>/minimal.py`, `complete.py`, `demo.py`
- C++ driver: `cpp/src/chips/<category>/<Chip>.h` and `cpp/src/chips/<category>/<Chip>.cpp`
- C++ examples: `cpp/examples/<Chip>_Minimal/<Chip>_Minimal.ino`, `<Chip>_Complete/<Chip>_Complete.ino`, `<Chip>_Demo/<Chip>_Demo.ino`
- Node.js driver: `nodejs/packages/periph/src/chips/<category>/<chip>.js`
- Node.js examples: `nodejs/packages/periph/examples/<category>/<chip>/minimal.js`, `complete.js`, `demo.js`
- Node-RED node: `nodejs/packages/node-red-contrib-periph-<category>/nodes/<chip>/<chip>.js` and `<chip>.html`
- Remove `.gitkeep` from the target directory when adding the first real file

## Transport interface

Chip drivers must only call the three transport methods. Never import or reference a concrete transport class.

```python
# Python
transport.write(data: bytes)
transport.read(n: int) -> bytes
transport.write_read(data: bytes, n: int) -> bytes
```

```cpp
// C++
transport.write(const uint8_t* data, size_t len);
transport.read(uint8_t* buf, size_t len);
transport.write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len);
```

All INA226-style register reads follow this pattern:
```python
raw = transport.write_read(bytes([REG_ADDR]), 2)
value = (raw[0] << 8) | raw[1]          # big-endian, unsigned
value = struct.unpack('>h', raw)[0]      # big-endian, signed
```

## Class structure

```python
class INA226Minimal:
    def __init__(self, transport, ...): ...

class INA226Full(INA226Minimal):
    def __init__(self, transport, ...):
        super().__init__(transport, ...)
```

```cpp
class INA226Minimal {
public:
    INA226Minimal(Transport& transport, ...);
    ...
protected:
    Transport& _transport;
};

class INA226Full : public INA226Minimal {
public:
    INA226Full(Transport& transport, ...);
    ...
};
```

Full never duplicates Minimal — it only adds.

## Python conventions

Three supported targets: **MicroPython** (primary), **CircuitPython**, **Linux kernel** (via `smbus2`).

### Chip drivers (all platforms)

Chip drivers are platform-agnostic — they only call the transport interface. Write them to the most restrictive common denominator so they run on all three targets:

- No f-strings, no walrus operator, no `match` statements — MicroPython lags CPython
- Avoid heap allocation in frequently-called methods; reuse `bytearray` buffers where practical
- Use `struct.pack` / `struct.unpack` for multi-byte register values
- No type annotations — MicroPython does not enforce them and they add overhead
- Constants as class-level variables prefixed with `_` (e.g. `_REG_CONFIG = 0x00`)

### Transport implementations

Each platform has its own transport file. Use platform-native APIs:

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

## C++ conventions (Arduino)

- No STL (`std::vector`, `std::string`, etc.) — not available on all Arduino targets
- No exceptions — use return codes or a `valid()` flag pattern for errors (see `SMBusTransport`)
- No heap allocation in drivers (`new` / `malloc`) — use stack or member variables only
- Register constants as `static constexpr uint8_t` in the class header
- 16-bit register reads: receive two bytes, combine as `(buf[0] << 8) | buf[1]`
- Signed 16-bit: cast as `static_cast<int16_t>((buf[0] << 8) | buf[1])`

## Node.js transport interface

JS chip drivers use the same three-method contract, in camelCase:

```js
transport.write(buffer)                        // Buffer
transport.read(length)                         // returns Buffer
transport.writeRead(writeBuffer, readLength)   // returns Buffer
```

Big-endian 16-bit register reads:
```js
const raw = transport.writeRead(Buffer.from([REG_ADDR]), 2);
const value = raw.readUInt16BE(0);   // unsigned
const value = raw.readInt16BE(0);    // signed (2's complement)
```

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

## Examples

Each chip has three examples per language (same branch as the driver):

| File | Class | Content |
|------|-------|---------|
| `minimal` | `*Minimal` | Construct with transport, read primary values in a loop, print to serial/stdout. No comments. |
| `complete` | `*Full` | Every method in the API called once — configuration, alerts, shutdown/wake, IDs. No comments. |
| `demo` | `*Full` | The scenario from the spec's Demo section. One short why-comment per logical block. |

The demo scenario is defined in the chip spec. The minimal and complete examples are fully implied by the API tables — implement them mechanically.

For C++ (Arduino), the directory name must exactly match the `.ino` filename: `INA226_Minimal/INA226_Minimal.ino`.

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
