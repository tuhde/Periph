# Feature Design: Connection — Register Access

**Status:** Ready
**Branch:** `feature/register-access`
**Scope:** All languages × I²C/SMBus/SPI platform variants — `RegisterConnection` abstraction,
sign-extension helpers, spec template, AGENTS.md guidance

**Origin:** GitHub issue #85 ("Transport Abstraction") — check whether often-used chip-level
functions can be abstracted down into transports.

---

## 1. Problem Statement

`Connection` (see `specs/feature_connection_design.md`) deliberately stays at the raw-byte
level: `read(n)` / `write(data)` / `write_read(data, n)`, with no concept of a register
address. As a result, essentially every register-based chip driver — the large majority of
this library's ~37+ chips — hand-rolls the same plumbing on top of those three primitives:

1. **Register read/write dispatch**, including the I²C-vs-SPI branch. I²C/SMBus register
   access is one line (`write_read([reg], n)`); SPI needs a command byte built from the
   register address plus a chip-specific R/W bit and (for some chip families) a multi-byte/
   auto-increment bit. Confirmed duplicated, nearly verbatim, in Python and C++ alike:

   ```python
   # python/periph/chips/accelerometer/adxl345.py
   def _read_reg(self, reg):
       if self._bus_type == _BUS_SPI:
           cmd = self._cmd_byte(reg, read=True, multi=False)
           return self._connection.write_read(bytes([cmd]), 1)[0]
       return self._connection.write_read(bytes([reg & 0xFF]), 1)[0]
   ```

   ```cpp
   // cpp/src/chips/accelerometer/ADXL345.cpp — same shape, different syntax
   void ADXL345Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) { ... }
   ```

   Every dual-bus chip (`adxl345`, `bme280`/`bmp280`/`bmp384`/`bmp581`/`lps22df`, `neo6`,
   `mfrc522`, …) repeats this branch, and the two SPI conventions in active use differ
   subtly: ADXL345 uses a 6-bit address with R/W at bit 7 and an explicit multi-byte bit at
   bit 6; BME280 and its siblings use a 7-bit address with R/W at bit 7 only (no multi-byte
   bit — the chip always auto-increments), relying on their register constants already
   living at addresses ≥ `0x80` to avoid needing an explicit OR. Neither convention is
   documented anywhere; each chip driver silently re-derives it.

2. **Sign-extension of multi-byte registers.** Every chip with 16-bit or 24-bit signed
   registers re-derives two's-complement conversion inline, worded slightly differently
   each time:

   ```python
   if rx & 0x8000: rx -= 0x10000                    # adxl345.py
   if raw_press >= 0x800000: raw_press -= 0x1000000  # lps33hw.py
   if value & 0x800000: ...                          # lps28dfw.py
   ```

3. **Documentation already promises the fix, but it was never built.** `AGENTS.md` §
   "Connection interface" already documents a `connection.read(reg, length)` /
   `connection.write(reg, data)` contract as *the* thing chip drivers should call — but no
   `Connection` implementation in any language actually exposes it (`I2CConnection`,
   `SPIConnection`, `SMBusConnection` all still only implement the byte-level `_read`/
   `_write`/`_write_read` hooks), and the AGENTS.md text never addresses how SPI's
   command-byte convention fits into a single `read(reg, length)` call. No existing chip
   driver conforms to the documented contract. Rust is the closest exception: its optional
   `Connection<BUS>` wrapper (`rust/periph/src/connection/connection.rs`) already implements
   register-style `read`/`write` for I²C — but the methods are `pub(crate)` (unusable
   outside the crate, so no real chip driver can call them) and no SPI equivalent exists.
   This design closes that gap for real, across every language, for both bus kinds.

**Non-goal:** protocols with no register-address concept (HX711, NeoPixel, SiPo, DHTxx,
UART/NMEA for GNSS, MFRC522's FIFO command set) are unaffected — they keep using
`Connection`'s raw `read`/`write`/`write_read` exactly as today.

---

## 2. Design Goals

1. **One call site, any register-capable bus.** A chip driver calls
   `connection.read(reg, length)` / `connection.write(reg, data)` without knowing or caring
   whether the underlying bus is I²C, SMBus, or SPI. This is what actually removes the
   `if bus_type == 'spi': ...` branch from every dual-bus chip driver — the branch moves
   into the `SPIConnection` class, written once per language instead of once per chip.
2. **Additive, not replacing.** The raw byte-level `read(n)` / `write(data)` /
   `write_read(data, n)` primitives stay exactly as they are on `Connection`; register
   access is a new capability layered on top, for buses that have the concept.
3. **Cover both SPI conventions already in this codebase**, and be extensible to a third
   without redesign: the command-byte shape (R/W bit position, optional multi-byte bit
   position, address width) is a per-instance configuration on `SPIConnection`, not a
   hardcoded assumption.
4. **Fulfil, don't further diverge from, the existing AGENTS.md contract.** The
   `connection.read(reg, length)` / `connection.write(reg, data)` shape already documented
   is kept as-is; this design fills in what AGENTS.md left unsaid (SPI's command byte) and
   makes it real.
5. **Shared sign-extension helpers**, so `to_signed(value, bits)` (or the per-language
   idiom) replaces the repeated inline two's-complement math.
6. **Migration is opportunistic, not a flag day.** Mandatory for every new chip spec from
   this point on; existing chip drivers adopt it only when touched for other reasons (see
   §7). No mechanical, all-at-once rewrite of already-merged chips.

---

## 3. `RegisterConnection` — the new layer

`RegisterConnection` sits between `Connection` and the three register-capable concrete
classes. `I2CConnection`, `SMBusConnection`, and `SPIConnection` implement/extend it instead
of `Connection` directly; every other concrete connection (`UARTConnection`,
`HX711Connection`, `NeoPixelConnection`, `SiPoConnection`, `DHTxxConnection`) is unaffected
and continues to extend `Connection` only.

| Method | Behavior |
|--------|----------|
| `read(reg, length)` | Read `length` bytes starting at register `reg`. |
| `write(reg, data)` | Write `data` (bytes, or a single int for a 1-byte register) to register `reg`. |

Default implementation (used as-is by `I2CConnection` and `SMBusConnection`):

```
read(reg, length)  = write_read([reg], length)
write(reg, data)   = write([reg] + data)
```

`SPIConnection` overrides both to build the command byte from its configured convention
(§4) before delegating to the same underlying `write_read` / `write`.

Chip drivers that need register access declare their connection parameter as
`RegisterConnection` (or the language's equivalent typing), not the bare `Connection`, so
the compiler/type-checker rules out passing an `HX711Connection` or `UARTConnection` by
mistake. Dual-bus chips (I²C or SPI) keep working unchanged at the call site: both concrete
classes satisfy `RegisterConnection`.

```python
# was, per chip, duplicated:
def _read_reg(self, reg):
    if self._bus_type == 'spi':
        cmd = self._cmd_byte(reg, read=True, multi=False)
        return self._connection.write_read(bytes([cmd]), 1)[0]
    return self._connection.write_read(bytes([reg & 0xFF]), 1)[0]

# becomes, regardless of bus:
def _read_reg(self, reg):
    return self._connection.read(reg, 1)[0]
```

The `bus_type` constructor parameter chip drivers currently carry (`ADXL345Minimal(conn,
bus_type='i2c')`, `BME280Minimal(conn, bus_type='spi')`, …) is no longer needed for register
dispatch once `RegisterConnection` handles it — see §7 for whether to drop it during
migration or leave it for other bus-specific behavior a given chip might still need.

---

## 4. SPI Register-Addressing Convention

`SPIConnection` takes an addressing convention at construction, defaulting to the more
common of the two conventions already in this codebase:

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `read_bit` | `0x80` | Bit ORed into the command byte for a read; `0` if the chip has no such bit. |
| `multi_byte_bit` | `None` / `0` | Bit ORed in for multi-byte (burst) transfers when `length > 1`; `None` if the chip has no such bit and always auto-increments. |

Command byte: `cmd = reg | (read_bit if reading else 0) | (multi_byte_bit if length > 1 and multi_byte_bit else 0)`.

| Chip family | `read_bit` | `multi_byte_bit` | Matches |
|-------------|-----------|-------------------|---------|
| ADXL345-style (Analog Devices) | `0x80` | `0x40` | 6-bit register address, explicit MB bit |
| BME280-style (Bosch) | `0x80` | *(none)* | 7-bit register address, always auto-increments |

A chip needing a third convention (e.g. a 16-bit register address, or R/W at a different
bit position) passes explicit values; nothing here hardcodes a chip-family name.

```python
# python — BME280-style chip (no multi-byte bit)
conn = SPIConnection(bus_num=0, device_num=0, multi_byte_bit=None)

# python — ADXL345-style chip (explicit multi-byte bit)
conn = SPIConnection(bus_num=0, device_num=0, multi_byte_bit=0x40)
```

For I²C and SMBus there is no equivalent parameter — register access on those buses is
always a plain `write_read([reg], length)`, matching every chip's I²C behavior today.

---

## 5. Sign-Extension Helpers

A small shared utility, alongside `RegisterConnection` rather than on it (this is pure
integer math, not bus access):

| Language | Location | Signature |
|----------|----------|-----------|
| Python | `python/periph/connection/register.py` | `to_signed(value: int, bits: int) -> int` |
| C++ | `cpp/src/connection/Register.h` (header-only, `inline`) | `int32_t toSigned(uint32_t value, uint8_t bits)` |
| Node.js | `nodejs/packages/periph/src/connection/register.js` | `toSigned(value, bits)` |
| Rust | `rust/periph/src/connection/register.rs` | `pub fn to_signed(value: u32, bits: u32) -> i32` |
| JVM | `it.uhde.periph.connection.Register` | `static int toSigned(int value, int bits)` |
| Go | `go/periph/connection/register.go` | `func ToSigned(value uint32, bits uint) int32` |

```python
def to_signed(value: int, bits: int) -> int:
    """Interpret the low `bits` bits of `value` as two's-complement signed."""
    sign_bit = 1 << (bits - 1)
    return (value & (sign_bit - 1)) - (value & sign_bit)
```

Usage replaces the inline idiom:

```python
# was: if rx & 0x8000: rx -= 0x10000
rx = to_signed(rx, 16)
```

This is a pure function, not a `RegisterConnection` method — it has no bus dependency and
is equally useful to a chip driver that isn't register-based at all (e.g. HX711's 24-bit
ADC result).

---

## 6. Per-Language Implementation

### 6.1 Python (`python/periph/connection/`)

```python
# base.py — unchanged (see feature_connection_design.md)

# register_connection.py — new file
from abc import ABC, abstractmethod
from .base import Connection

class RegisterConnection(Connection, ABC):
    """Connection with register-addressed read/write, for I2C/SMBus/SPI-style buses."""

    def read(self, reg: int, length: int) -> bytes:
        return self.write_read(bytes([reg]), length)

    def write(self, reg: int, data) -> None:
        payload = bytes([reg]) + (bytes([data]) if isinstance(data, int) else bytes(data))
        self._write_gated(payload)
```

`I2CConnection` and `SMBusConnection` change their base class to `RegisterConnection` and
need no further change — the default `read`/`write` above is exactly their existing
behavior. `SPIConnection` also extends `RegisterConnection` but overrides both methods:

```python
class SPIConnection(RegisterConnection):
    def __init__(self, bus_num, device_num, mode=0, max_speed_hz=1_000_000,
                 read_bit=0x80, multi_byte_bit=None, int_pin=None, en_pin=None):
        super().__init__(int_pin, en_pin)
        ...
        self._read_bit = read_bit
        self._multi_byte_bit = multi_byte_bit

    def read(self, reg: int, length: int) -> bytes:
        cmd = reg | self._read_bit
        if length > 1 and self._multi_byte_bit:
            cmd |= self._multi_byte_bit
        return self.write_read(bytes([cmd]), length)

    def write(self, reg: int, data) -> None:
        payload = bytes([data]) if isinstance(data, int) else bytes(data)
        cmd = reg | (self._multi_byte_bit if len(payload) > 1 and self._multi_byte_bit else 0)
        self._write_gated(bytes([cmd]) + payload)
```

(`_write_gated` is the existing gated-write path already on `Connection` — naming shown for
clarity; the actual implementation reuses `Connection.write`'s existing gating rather than
introducing a second gate.)

### 6.2 C++ (`cpp/src/connection/`)

`RegisterConnection.h` — new abstract class:

```cpp
#pragma once
#include "Connection.h"

class RegisterConnection : public Connection {
public:
    using Connection::Connection;

    virtual void read(uint8_t reg, uint8_t* buf, size_t len) {
        uint8_t r = reg;
        write_read(&r, 1, buf, len);
    }
    virtual void write(uint8_t reg, const uint8_t* data, size_t len) {
        uint8_t payload[17];
        payload[0] = reg;
        memcpy(payload + 1, data, len);
        Connection::write(payload, len + 1);
    }
};
```

`I2CConnection` / `SMBusConnection` (all platform variants: Linux, Zephyr, ESP-IDF, Pico
SDK, Arduino) change their base to `RegisterConnection`; the default above matches their
current behavior unchanged. `SPIConnection` (all platform variants) also extends
`RegisterConnection`, overriding `read`/`write` to build the command byte:

```cpp
class SPIConnection : public RegisterConnection {
public:
    SPIConnection(SPIClass& bus, uint8_t cs_pin, SPISettings settings,
                  uint8_t readBit = 0x80, uint8_t multiByteBit = 0,
                  InputPin* intPin = nullptr, OutputPin* enPin = nullptr);

    void read(uint8_t reg, uint8_t* buf, size_t len) override;
    void write(uint8_t reg, const uint8_t* data, size_t len) override;
    ...
private:
    uint8_t _readBit;
    uint8_t _multiByteBit;   // 0 = chip has no such bit
};
```

### 6.3 Node.js (`nodejs/packages/periph/src/connection/`)

```js
// register_connection.js — new file
const { Connection } = require('./connection');

class RegisterConnection extends Connection {
    async read(reg, length) {
        return this.writeRead(Buffer.from([reg]), length);
    }
    async write(reg, data) {
        const payload = Buffer.isBuffer(data) ? data : Buffer.from([data]);
        return this._write(Buffer.concat([Buffer.from([reg]), payload]));
    }
}
module.exports = { RegisterConnection };
```

`I2CConnection` / `SMBusConnection` extend `RegisterConnection` unchanged in behavior.
`SPIConnection` extends it and overrides both methods with the command-byte logic, taking
`readBit` / `multiByteBit` constructor options mirroring §6.1/§6.2.

### 6.4 Rust (`rust/periph/src/connection/`)

Rust chip drivers are generic directly over `embedded_hal::i2c::I2c` /
`embedded_hal::spi::SpiDevice` in most cases (see `specs/feature_connection_design.md`
§4.5) rather than a periph-owned wrapper — so the register helpers are free functions, not
methods requiring a `Connection<BUS>` wrapper. This also fixes the existing
`Connection<BUS>::read`/`write` visibility bug (§1.3) as part of the same change: promote
both to `pub`.

```rust
// register.rs — new file
use embedded_hal::i2c::I2c;
use embedded_hal::spi::{Operation, SpiDevice};

pub fn read_register<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

pub fn write_register<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, data: &[u8]) -> Result<(), I2C::Error> {
    let mut buf = [0u8; 17];
    buf[0] = reg;
    buf[1..=data.len()].copy_from_slice(data);
    i2c.write(addr, &buf[..=data.len()])
}

/// SPI register convention: which bits the command byte sets for a read / burst transfer.
pub struct SpiRegisterConvention {
    pub read_bit: u8,
    pub multi_byte_bit: Option<u8>,
}

pub fn spi_read_register<SPI: SpiDevice>(
    spi: &mut SPI, conv: &SpiRegisterConvention, reg: u8, buf: &mut [u8],
) -> Result<(), SPI::Error> {
    let mut cmd = reg | conv.read_bit;
    if buf.len() > 1 { if let Some(mb) = conv.multi_byte_bit { cmd |= mb; } }
    spi.transaction(&mut [Operation::Write(&[cmd]), Operation::Read(buf)])
}

pub fn spi_write_register<SPI: SpiDevice>(
    spi: &mut SPI, conv: &SpiRegisterConvention, reg: u8, data: &[u8],
) -> Result<(), SPI::Error> {
    let mut cmd = reg;
    if data.len() > 1 { if let Some(mb) = conv.multi_byte_bit { cmd |= mb; } }
    spi.transaction(&mut [Operation::Write(&[cmd]), Operation::Write(data)])
}
```

`Connection<BUS>` (the optional I²C-only software-enable wrapper) keeps its existing
`read`/`write`, now `pub`, implemented in terms of `read_register`/`write_register` above to
avoid duplicating the same two lines twice.

### 6.5 JVM (`jvm/periph-connection/src/main/java/it/uhde/periph/connection/`)

```java
public abstract class RegisterConnection extends AbstractConnection {
    protected RegisterConnection(InputPin intPin, OutputPin enPin) { super(intPin, enPin); }

    public byte[] read(int reg, int length) {
        return writeRead(new byte[]{(byte) reg}, length);
    }

    public void write(int reg, byte[] data) {
        byte[] payload = new byte[data.length + 1];
        payload[0] = (byte) reg;
        System.arraycopy(data, 0, payload, 1, data.length);
        write(payload);
    }
}
```

`I2CConnection` / `SMBusConnection` extend `RegisterConnection` unchanged in behavior.
`SPIConnection` extends it, overriding both with the command-byte convention (constructor
gains `int readBit` / `Integer multiByteBit`, mirroring §6.1–6.3).

### 6.6 Go (`go/periph/connection/`)

Go has no method overloading, so the register methods get distinct names, and — since not
every `Connection` has them — a separate interface embedding `Connection`:

```go
package connection

// RegisterConnection is a Connection that additionally supports
// register-addressed access (I2C, SMBus, SPI-style buses).
type RegisterConnection interface {
    Connection
    ReadReg(reg byte, length int) ([]byte, error)
    WriteReg(reg byte, data []byte) error
}
```

`I2CConnection` and `SMBusConnection` (Linux and TinyGo variants) add:

```go
func (c *I2CConnection) ReadReg(reg byte, length int) ([]byte, error) {
    return c.WriteRead([]byte{reg}, length)
}

func (c *I2CConnection) WriteReg(reg byte, data []byte) error {
    return c.Write(append([]byte{reg}, data...))
}
```

`SPIConnection` (Linux and TinyGo) adds the same two methods, building the command byte
from `ReadBit` / `MultiByteBit` fields set at construction (`0` meaning "chip has no such
bit", since Go has no `Option<T>`).

Chip drivers for register-based chips accept `connection.RegisterConnection` instead of
`connection.Connection`; chips with no register concept (HX711, DHTxx, NeoPixel, SiPo,
GNSS/UART) are unaffected and keep accepting plain `connection.Connection`.

---

## 7. Migration Plan for Existing Chip Drivers

**Not a flag day.** Every existing chip driver keeps its current hand-rolled `_read_reg` /
`_write_reg` working exactly as today — nothing in this design breaks or deprecates it.
Adoption happens opportunistically:

- **Mandatory for every new chip spec** from this point on (§8 template changes) — a new
  chip's spec and implementation are expected to accept `RegisterConnection` and call
  `connection.read(reg, length)` / `connection.write(reg, data)` directly, with no
  chip-local `_read_reg`/`_write_reg` and no `bus_type` branch.
- **Existing chips migrate only when already being touched** for an unrelated bug fix,
  feature addition, or the chip's own PR review — never as a dedicated repo-wide sweep.
  When migrating a chip, drop its `bus_type` constructor parameter along with the
  `_read_reg`/`_write_reg` methods it replaces, since bus-type dispatch is now the
  connection's job.
- Dual-bus chips already in the tree that are prime (low-risk) migration candidates when
  next touched: `adxl345`, `bme280`, `bmp280`, `bmp384`, `bmp581`, `lps22df` — all use one
  of the two documented SPI conventions exactly, with no additional bus-specific quirks
  beyond register dispatch.
- `mfrc522` and `neo6` are **not** candidates: MFRC522 layers a FIFO/command protocol on
  top of plain register access (not a pure register-addressed device), and NEO-6 talks
  UART/NMEA, which has no register concept at all.

---

## 8. Spec Template Changes

### 8.1 `specs/_template_chip.md` — `## Transport Configuration` → `### SPI`

Add the register-addressing convention as an explicit, required field so every new SPI
chip spec states it up front instead of leaving it to be reverse-engineered from the
datasheet during implementation:

```markdown
### SPI
- **Mode:** CPOL=? CPHA=? (Mode ?)
- **Max clock:** <e.g. 10 MHz>
- **Bit order:** MSB first
- **CS active:** low
- **Register addressing:** read bit `0x??` (or "none"); multi-byte bit `0x??` (or "none — always auto-increments")
```

### 8.2 `## Implementation Notes` (existing section)

Add a line reminding the implementer of the shared layer:

```markdown
Register-based chips accept `RegisterConnection`, not the bare `Connection`, and call
`connection.read(reg, length)` / `connection.write(reg, data)` directly — no chip-local
`_read_reg`/`_write_reg` or `bus_type` branch. See `specs/feature_register_access_design.md`.
```

---

## 9. AGENTS.md Changes

### 9.1 Replace the existing `## Connection interface` register-access snippet

The current snippet (added by `cd359e0c`, never implemented) is replaced with one that
matches what's actually built, and calls out which concrete classes provide it:

````markdown
## Connection interface

> **When implementing a transport:** open `specs/transport_<name>.md` first...
> (unchanged)

Register-based chip drivers accept a `RegisterConnection` and call only
`connection.read(reg, length)` / `connection.write(reg, data)` — never a chip-local
`_read_reg`/`_write_reg`, and never a `bus_type` branch. `I2CConnection`, `SMBusConnection`,
and `SPIConnection` all implement `RegisterConnection`; `SPIConnection` additionally takes
the chip's register-addressing convention (read bit, optional multi-byte bit) at
construction. See `specs/feature_register_access_design.md` for the full design.

Chips with no register concept (HX711, NeoPixel, SiPo, DHTxx, UART-based GNSS, MFRC522's
FIFO protocol) accept plain `Connection` and keep using `read(n)` / `write(data)` /
`write_read(data, n)` exactly as before.

```python
# Python
data = connection.read(REG_ADDR, 2)     # register-addressed, any bus
connection.write(REG_ADDR, bytes([value]))
```

*(equivalent snippets per language, mirroring the existing ones already in this section)*

Sign-extension: use the shared `to_signed(value, bits)` helper (per-language location in
`specs/feature_register_access_design.md` §5) instead of inline two's-complement math.
````

### 9.2 New chips checklist

Add a line to the per-chip implementation checklist guidance: "Accepts `RegisterConnection`
(not bare `Connection`) if the chip is register-addressed."

---

## 10. Design Decisions

1. **`RegisterConnection` is a new intermediate type, not methods added straight onto
   `Connection`.** Putting `read(reg, length)` on the universal `Connection` base would
   make it callable (and misleadingly present) on `UARTConnection`/`HX711Connection`/etc.,
   which have no register concept. A separate type lets register-based chip drivers
   declare their dependency precisely and keeps non-register connections unchanged.
2. **SPI convention is two small parameters (`read_bit`, `multi_byte_bit`), not a named
   enum like `'adi'` / `'bosch'`.** Only two conventions exist in this codebase today, but
   a third chip family with yet another bit layout is more likely than a rename of "ADI
   style" — explicit bits describe any convention without needing a new enum value each
   time one shows up.
3. **Go gets distinct method names (`ReadReg`/`WriteReg`)** rather than reusing
   `Read`/`Write`, since Go has no overloading and `Connection.Read(n int)` already exists
   with an incompatible signature.
4. **Rust gets free functions, not a mandatory wrapper type**, consistent with
   `specs/feature_connection_design.md` §4.5's decision that Rust chip drivers stay generic
   over `embedded_hal` traits directly. `Connection<BUS>`'s own `read`/`write` are fixed
   (promoted to `pub`) and reimplemented in terms of the same free functions, rather than
   maintaining two copies of the same two lines.
5. **`to_signed` is a standalone helper, not part of `RegisterConnection`.** Sign extension
   has no bus dependency — it's equally useful for HX711's 24-bit result, which has no
   register address at all.
6. **No mandatory backfill of existing chips.** Per the scope decided for GitHub issue #85,
   this design ships for new chips only; existing chips adopt it opportunistically (§7).
