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

- Python output: `python/periph/chips/<category>/<chip>.py`
- C++ output: `cpp/src/chips/<category>/<Chip>.h` and `cpp/src/chips/<category>/<Chip>.cpp`
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

## Python conventions (MicroPython primary, CircuitPython untested)

Target is MicroPython. CircuitPython may work but is not tested — do not use CircuitPython-specific APIs (e.g. `busio.I2C.writeto_then_readfrom`, `busio.I2C.readfrom_into` without `stop`).

- No f-strings, no walrus operator, no `match` statements — MicroPython lags CPython
- Avoid heap allocation in frequently-called methods; reuse `bytearray` buffers where practical
- Use `struct.pack` / `struct.unpack` for multi-byte register values
- No type annotations — MicroPython does not enforce them and they add overhead
- Constants as class-level variables prefixed with `_` (e.g. `_REG_CONFIG = 0x00`)

## C++ conventions (Arduino)

- No STL (`std::vector`, `std::string`, etc.) — not available on all Arduino targets
- No exceptions — use return codes or a `valid()` flag pattern for errors (see `SMBusTransport`)
- No heap allocation in drivers (`new` / `malloc`) — use stack or member variables only
- Register constants as `static constexpr uint8_t` in the class header
- 16-bit register reads: receive two bytes, combine as `(buf[0] << 8) | buf[1]`
- Signed 16-bit: cast as `static_cast<int16_t>((buf[0] << 8) | buf[1])`

## Commit convention

One commit per stage (Minimal, Full). Message format:

```
Add INA226Minimal for MicroPython and Arduino

Co-Authored-By: OpenCode <noreply@opencode.ai>
```
