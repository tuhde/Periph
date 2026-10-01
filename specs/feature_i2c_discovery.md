# Feature Design: I²C Bus Auto-Discovery

**Issue:** #128
**Status:** Spec — awaiting implementation
**Scope:** Host targets only — Python (Linux/smbus2), C++ (Linux GCC), Node.js, Rust (Linux host), JVM (Java), Go (Linux). MicroPython, CircuitPython, Arduino, Zephyr, ESP-IDF, Pico SDK and TinyGo are out of scope (firmware knows its wiring at build time; the registry would cost flash/RAM for nothing).

---

## 1. Problem Statement

Given an opened I²C bus, report which of the library's supported chips are connected, without the caller knowing the wiring.

Two things are missing today:

1. **No bus-scan primitive.** `specs/transport_i2c.md` defines only `write` / `read` / `write_read`, and every `I2CConnection` is bound to **one** address at construction. Enumerating addresses is a bus-level operation, so it cannot be a method on a per-device connection.
2. **No machine-readable address registry.** Each chip spec documents its address as prose under `## Transport Configuration`.

A raw address scan cannot name a chip because addresses collide. From the current specs:

| Address | Chips (all in this repo) |
|---|---|
| `0x38` | AHT21, ADE7953, PCF8576, PCF8574A |
| `0x39` | APDS9960, APDS-9930, PCF8576 (alt), PCF8574A |
| `0x40`–`0x4F` | INA219, INA226, INA3221 (`0x40`–`0x43`), TMP117 (`0x48`–`0x4B`), PCF8591 (`0x48`–`0x4F`) |
| `0x20`–`0x27` | PCF8574, PCF8575, MCP23017 |
| `0x60`–`0x67` | MCP4725, MCP4728, DRV8830 (`0x60`–`0x68`) |
| `0x68`/`0x69` | MPU6050, MPU9250, MPU9255, L3G4200D, DS3231 (`0x68`), PCF8523 (`0x68`) |
| `0x76`/`0x77` | BMP280, BME280, BME680, BMP384; `0x77` also BMP180 / BMP085 |
| `0x5C`/`0x5D` | LPS22DF, LPS28DFW, LPS33HW |
| `0x29` | VL53L0X, VL53L1X |
| `0x5A`–`0x5D` | MPR121 (overlaps the `0x5C`/`0x5D` pressure sensors) |

Many of these chips have an identity register and can be told apart. Many do not (PCF8574 / MCP23017, DS3231 / PCF8523, INA219, AS5600, MPR121, DRV8830, AHT21, ADE7953, MCP4725/4728 …). **An ambiguous result is a legitimate final answer** (decision on issue #128, §10).

---

## 2. Design Goals

1. **One source of truth, outside the language trees.** The registry is a language-neutral data file that can later move to its own GitHub/GitLab repository where anyone can add chip IDs via issues (§4.4).
2. **Safe by default.** A scan must not change the state of any device; an identity probe must never write to a device that interprets a write as data (§7).
3. **Honest results.** `identified` is set only when an identity read confirms **exactly one** chip. Everything else is reported as candidates.
4. **No driver required.** The registry may name chips this library has no driver for, so the external registry can grow faster than the drivers.
5. **Small per-language code.** Each host language implements only: scan, register-read probe, and a matcher over a generated native table.

Non-goals: automatic driver instantiation (a `DiscoveredDevice` may carry a `driver` name, but constructing it is the caller's job); SPI/UART discovery; heuristics that try a candidate's init sequence (rejected in #128 Q3).

---

## 3. Scan Primitive

### 3.1 Placement

The scan is **bus-level**. It is a static function on the platform's `I2CConnection` class (a free function in Rust and Go, matching how those languages already expose the connection), taking the platform bus handle — never an instance bound to an address.

```
scan(bus, first=0x08, last=0x77) -> sorted list of 7-bit addresses that ACK
read_register(bus, addr, reg, reg_bytes, length) -> bytes      # used only by discover()
```

`0x00`–`0x07` and `0x78`–`0x7F` are reserved by UM10204 and are never scanned.

### 3.2 Probe method per address (mirrors `i2cdetect -y`)

| Address range | Method | Why |
|---|---|---|
| `0x08`–`0x2F`, `0x38`–`0x4F`, `0x60`–`0x77` | SMBus **quick write** (address byte + W bit, zero data bytes) | Clocks no data into the device |
| `0x30`–`0x37`, `0x50`–`0x5F` | SMBus **read byte** | These ranges hold EEPROMs/NVRAM; a quick write there can disturb the address pointer or start a write cycle |

Fallback: if the adapter does not advertise quick-write support (`I2C_FUNC_SMBUS_QUICK` missing), use **read byte** for every address. This is a fallback on **adapter capability**, not on NACK — a NACK always means "no device at this address". (The original issue text suggested falling back on NACK-on-write; that is incorrect and is replaced by this rule.)

Linux `i2c-dev` returns `EBUSY` for an address claimed by a kernel driver. `scan()` treats `EBUSY` as **present** (like `i2cdetect`'s `UU`) and `discover()` reports such a device with `in_use_by_kernel=true` and skips identity probing for it (the read would be refused anyway).

### 3.3 Error handling

| Condition | Behaviour |
|---|---|
| NACK / `ENXIO` | Address absent — omit |
| `EBUSY` | Present, kernel-bound — include, flagged |
| Bus-level failure (`EIO`/timeout on **every** address, or the device node cannot be opened) | Raise `OSError` / return error; do not return an empty list |

### 3.4 Per-platform mapping

| Language | Quick write | Read byte | Capability check |
|---|---|---|---|
| Python | `SMBus.write_quick(addr)` | `SMBus.read_byte(addr)` | `SMBus.funcs` (`I2cFunc.SMBUS_QUICK`) |
| C++ (Linux) | `ioctl(I2C_SLAVE)` + `i2c_smbus_access(I2C_SMBUS_QUICK)` | `I2C_SMBUS_BYTE` read | `ioctl(I2C_FUNCS)` |
| Node.js | `i2cWrite(addr, 0, Buffer.alloc(0))` (zero-length write) | `receiveByte(addr)` | `i2cFuncs()` |
| Rust | `i2c_smbus_access` via `linux-embedded-hal`'s underlying `i2cdev` | same | `I2C_FUNCS` |
| JVM | FFM `ioctl(I2C_SMBUS)` with `I2C_SMBUS_QUICK` | `I2C_SMBUS_BYTE` | FFM `ioctl(I2C_FUNCS)` |
| Go | `unix.Syscall(SYS_IOCTL, …, I2C_SMBUS)` | same | `I2C_FUNCS` |

Implementations that cannot issue a true zero-byte quick write on a given binding (Node.js is the likely one) must document the deviation and fall back to read byte; read-byte-only is always acceptable (§3.2 fallback).

---

## 4. Registry

### 4.1 Location and layout

```
registry/
  README.md             # how to add an entry; what a reviewer checks
  schema.json           # JSON Schema (draft 2020-12) for chips.json
  chips.json            # the registry — source of truth
  scripts/
    validate.js         # schema + semantic checks; --check mode used by CI
    generate.js         # emits the native tables below; --check verifies they are current
```

Nothing under `registry/` imports or references `python/`, `cpp/`, `nodejs/`, `rust/`, `jvm/` or `go/`. It must be possible to split it out with `git subtree split --prefix=registry` and have it stand alone (§4.4).

### 4.2 `chips.json` format

```json
{
  "schema_version": 1,
  "chips": [
    {
      "id": "bme280",
      "name": "BME280",
      "category": "environmental",
      "addresses": ["0x76", "0x77"],
      "id_probe": {
        "register": "0xD0",
        "reg_bytes": 1,
        "length": 1,
        "byte_order": "big",
        "mask": "0xFF",
        "expected": ["0x60"]
      },
      "probe_safety": "register_pointer",
      "driver": "bme280",
      "datasheet": "https://…"
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Lower-case unique slug; equals the spec/driver file stem when a driver exists |
| `name` | yes | Display name |
| `category` | yes | One of the categories in `CLAUDE.md`; `other` for chips with no driver |
| `addresses` | yes | 7-bit addresses the chip can respond on. Entries are `"0xNN"` or a range `"0xNN..0xMM"` (inclusive) |
| `id_probe` | yes | Object, or `null` when the chip has no identity register |
| `id_probe.register` | with `id_probe` | Register address **as sent on the wire**, including any command bits (e.g. `0x92` for APDS-9930's ID register `0x12` with command bit `0x80`) |
| `id_probe.reg_bytes` | with `id_probe` | Register address width, 1–4 (VL53L1X, ADE7953 use 2); matches `RegisterConnection`'s `reg_bytes` |
| `id_probe.length` | with `id_probe` | Bytes to read, 1–4 |
| `id_probe.byte_order` | with `id_probe` | `big` or `little` (ENS160 `PART_ID` is little-endian) |
| `id_probe.mask` | with `id_probe` | Bitmask applied to the value read before comparing (MPU6050 bits 6:1 → `0x7E`; TMP117 lower 12 bits → `0x0FFF`) |
| `id_probe.expected` | with `id_probe` | Non-empty list of accepted masked values |
| `aliased` | no | See §7.5 |
| `probe_safety` | yes | `register_pointer` — a 1-byte write is only a register-pointer select; **or** `write_sensitive` — a write is interpreted as data/command (§7). Chips with `id_probe: null` use the value that describes their write behaviour anyway, so a future ID-bearing sibling at the same address is gated correctly |
| `driver` | no | Driver module name in this repo, or absent/`null` for chips without one |
| `datasheet` | yes | URL or repo path of the source for every value above |

Chips with the same `id_probe.register`/`reg_bytes`/`length`/`byte_order`/`mask` form a **probe group**; `discover()` reads each group once per address (§5).

### 4.3 Semantic validation (`validate.js`)

CI fails on any of:

- schema violation, unknown `schema_version`, duplicate `id`;
- an address outside `0x08`–`0x77`;
- two chips whose `addresses` overlap **and** whose `id_probe` is in the same probe group with an overlapping `expected` value — this is *not* an error by itself (LPS22DF and LPS28DFW both read `0xB4`) but must be listed in `registry/known_ambiguities.json`, so a new overlap is a conscious, reviewed decision;
- a chip with `driver` set whose driver file is missing (checked only when run inside this repo; skipped in the standalone registry repo);
- `probe_safety: "register_pointer"` combined with a register address wider than `reg_bytes`.

### 4.4 Path to an external community registry

The design assumes the registry will move to its own repo. Concretely:

1. **Now:** `registry/` lives in this repo; every chip spec change that adds an I²C chip adds its `chips.json` entry (§9).
2. **Later:** `registry/` is split out. An **issue form** collects *chip name, addresses, ID register, expected value(s), datasheet link, probe safety*; a workflow turns an accepted issue into a PR that edits `chips.json`; `validate.js` runs on that PR. The form fields are exactly the §4.2 fields — nothing else is needed, because entries are pure data.
3. **Consumption then:** `generate.js` takes `--registry <path-or-url>` and an optional pinned release tag; the default stays the local path. Libraries pin a registry release, so a community edit can never silently change a released library.

Only step 1 and the `--registry` flag are in scope for this issue; the issue form and workflow are not.

### 4.5 Generated native tables

`generate.js` emits one file per language and `--check` (CI) fails when any is stale, like the README generators:

| Language | Generated file |
|---|---|
| Python | `python/periph/discovery_registry.py` |
| C++ | `cpp/src/discovery/DiscoveryRegistry.h` (constexpr table, no heap) |
| Node.js | `nodejs/packages/periph/src/discovery/registry.js` |
| Rust | `rust/periph/src/discovery/registry.rs` (`const` slice) |
| JVM | `jvm/periph-java/src/main/java/it/uhde/periph/discovery/DiscoveryRegistry.java` |
| Go | `go/periph/discovery/registry.go` |

Generated files carry a `// GENERATED — do not edit; run registry/scripts/generate.js` header. Ranges are expanded to explicit addresses in the generated tables.

---

## 5. `discover()`

```
discover(bus, registry=<built-in>, active=False) -> list[DiscoveredDevice]
```

```
DiscoveredDevice
  address:              int
  candidates:           list[str]    # chip ids, sorted; [] = present but not in registry
  identified:           str | None   # chip id confirmed by an identity read, else None
  in_use_by_kernel:     bool
  aliases:              list[int]    # other addresses merged into this device (§7.5); usually []
  probe_skipped_reason: str | None   # "kernel_bound" | "write_sensitive_candidate" | None
```

`candidates` and `identified` hold **registry chip ids** (e.g. `"bme280"`); `DiscoveredDevice` additionally exposes the matching registry entry's `driver` name when set.

### 5.1 Algorithm (per address returned by `scan`)

1. `cands` = all registry chips listing the address.
2. If `cands` is empty → `candidates=[]`, `identified=None`. (Present but unknown to the registry.)
3. If the address is kernel-bound → report `cands`, `identified=None`, `probe_skipped_reason="kernel_bound"`.
4. If any chip in `cands` is `write_sensitive` and `active` is false → report `cands`, `identified=None`, `probe_skipped_reason="write_sensitive_candidate"`.
5. Otherwise, for each probe group among the `cands` that have an `id_probe`, read the register once.
   A failed read (NACK, bus error) is treated as "no match" for that group.
6. `matched` = chips whose group value (after `mask`) is in `expected`.
7. - `len(matched) == 1` → `identified = that chip`, `candidates = [that chip]`.
   - `len(matched) > 1` → `identified = None`, `candidates = matched` (e.g. LPS22DF / LPS28DFW both `0xB4`).
   - `len(matched) == 0` → `identified = None`, `candidates = ` the `cands` that have **no** `id_probe`. A device that fails every available ID check cannot be one of the ID-bearing candidates.
     If that leaves `candidates` empty, the device is reported as present-but-unknown (`candidates=[]`).

A chip that is the **only** candidate at its address but has no `id_probe` (AS5600 at `0x36`) is reported as `candidates=["as5600"]`, `identified=None`. An address match alone is not an identification.

### 5.2 Worked examples

| Bus contents | Result |
|---|---|
| BME280 at `0x76` | probe groups at `0x76`: `0xD0/1` and BMP384's `0x00/1`; `0xD0`→`0x60` → `identified="bme280"` |
| DS3231 at `0x68` alone | cands `{mpu6050, mpu9250, mpu9255, l3g4200d, ds3231, pcf8523}`; `0x75`→ no match, `0x0F` → no match; `candidates=["ds3231","pcf8523"]`, `identified=None` |
| INA226 at `0x40` | `0xFF` die ID `0x2260` → `identified="ina226"` |
| INA219 at `0x40` | die-ID read matches nothing → `candidates=["ina219"]` (the only candidate without an ID register at `0x40`) |
| LPS28DFW at `0x5C` | `0x0F`→`0xB4` matches two → `candidates=["lps22df","lps28dfw"]`, `identified=None` |
| PCF8574 at `0x20` | cands include write-sensitive chips, `active=False` → `candidates=["mcp23017","pcf8574","pcf8575"]`, `probe_skipped_reason="write_sensitive_candidate"` |
| Unknown device at `0x1B` | `candidates=[]` |

---

## 6. Language API

Names follow each language's conventions; semantics are identical.

| Language | Module | Entry points |
|---|---|---|
| Python | `periph/discovery.py` | `scan(bus)`, `discover(bus, active=False)`; `DiscoveredDevice` is a dataclass |
| C++ | `cpp/src/discovery/Discovery.h/.cpp` (Linux only) | `std::vector<uint8_t> scan(int bus)`, `std::vector<DiscoveredDevice> discover(int bus, bool active=false)` |
| Node.js | `nodejs/packages/periph/src/discovery/discovery.js` | `scan(bus)`, `discover(bus, {active})` |
| Rust | `rust/periph/src/discovery/mod.rs` | `scan(bus: &mut impl I2c)`, `discover(...)` — generic over `embedded_hal::i2c::I2c`; `0x30`–`0x37` / `0x50`–`0x5F` use a 1-byte read, other ranges a zero-length write (`write(addr, &[])`) — the `embedded-hal` equivalent of quick write |
| JVM | `jvm/periph-java/.../discovery/Discovery.java` | `scan(int bus)`, `discover(int bus, boolean active)` — **Java only**; Kotlin/Groovy callers invoke the Java class from JBang (a real Maven dependency of periph-groovy on periph-java is forbidden — see the known JVM Groovy dependency bug) |
| Go | `go/periph/discovery/discovery.go` | `Scan(bus int) ([]uint8, error)`, `Discover(bus int, active bool) ([]Device, error)` (Linux build tag only) |

`discover()` reads identity registers through the existing `RegisterConnection` layer (`reg_bytes`, byte order handled there), so no new register-access code is written. The scan primitive is the only new transport-level code.

---

## 7. Safety Audit

Audit basis: the `## Transport Configuration`, register map and Implementation Notes of each chip spec in `specs/`. The five chips flagged *verify* in the first draft (DRV8830, AHT21, ADE7953, MPR121, 24AA02UID) were then checked against the committed datasheets in `datasheets/`; the findings are in §7.4. Every other `probe_safety` value rests on the chip specs only, not on a datasheet re-read.

### 7.1 Scan (quick write / read byte)

- An address-only write clocks **no data byte**, so none of the supported chips can interpret it as a command or data. No chip in the current specs is known to react to it.
- Read byte at `0x30`–`0x37` and `0x50`–`0x5F` is used because those ranges hold EEPROM-class devices (24AA02UID at `0x50`–`0x57`; any EEPROM added later). A read byte on these is a current-address read, a pure read.
- Within `0x30`–`0x37` / `0x50`–`0x5F` the only chips in the registry today are the 24AA02UID and, at `0x5A`–`0x5D`, the MPR121 and the LPS pressure sensors. Datasheet check of the MPR121 (§7.4) found no read side effect.

### 7.2 Identity probe (1+ byte register-pointer write, then read)

Writing a register address to a chip that has **no** register pointer means the byte is data. These chips are `write_sensitive`:

| Chip | Why a pointer write is not harmless |
|---|---|
| PCF8574, PCF8574A, PCF8575 | The byte is written to the output port; pins change |
| PCF8591 | Byte is the control byte; changes channel / enables the DAC output |
| MCP4725, MCP4728 | First bytes are DAC command/data; can change the output voltage |
| PCF8576 | Byte is a mode/command word for the LCD driver |
| RDA5807M | **No register pointer at all** — writes always start at register `0x02` and the bytes configure the tuner |
| AHT21 | Command-driven; the first byte is a command. The datasheet defines only `0x71` (read status) and `0xAC` (trigger measurement); behaviour for any other byte is undocumented |
| ADE7953 | Register addresses are 16 bits; a 1-byte write is an incomplete address frame, and the datasheet does not say how the device recovers |
| NEO-6 (DDC, `0x42`) | Write stream; DDC reads return a byte stream, not register contents |

**Rule (§5.1 step 4):** if *any* candidate at an address is `write_sensitive`, `discover()` does not probe that address unless the caller passes `active=True`. Candidates are still reported.

All other chips in the registry are `register_pointer`: BMP085/180/280/384/581/BME280/BME680, MPU6050/9250/9255, L3G4200D, L3GD20H, LPS22DF/28DFW/33HW, ADXL345, APDS9960/9930, ENS160, INA219/226/3221, MCP9808, TMP117, VL53L0X/L1X, DS3231, PCF8523, MPR121, AS5600, HMC5883L, MCP23017, 24AA02UID, DRV8830.
`discover()` never writes more than the register address, so on an EEPROM the pointer write cannot start a write cycle.

### 7.3 Known unavoidable ambiguities (to be listed in `known_ambiguities.json`)

| Group | Reason |
|---|---|
| `lps22df` / `lps28dfw` at `0x5C`/`0x5D` | both `WHO_AM_I` = `0xB4` |
| `pcf8574` / `mcp23017` / `pcf8575` at `0x20`–`0x27` | no ID registers |
| `ds3231` / `pcf8523` at `0x68` | no ID registers |
| `mcp4725` / `mcp4728` / `drv8830` at `0x60`–`0x67` | no ID registers |
| `ina219` vs unknown INA-family parts at `0x40`–`0x4F` | no ID registers |
| `aht21` / `ade7953` / `pcf8576` / `pcf8574a` at `0x38` | no ID registers |

### 7.4 Datasheet check of the flagged chips

Checked against `datasheets/…/<chip>.pdf`.

| Chip | Finding | Effect on this spec |
|---|---|---|
| **DRV8830** | Addresses confirmed: write addresses `0xC0`–`0xD0` (Table 5) = 7-bit `0x60`–`0x68`. The byte after the address is always the **subaddress** (`0x00` CONTROL, `0x01` FAULT); a data byte only follows a subaddress (Figure 9). A one-byte write therefore selects a subaddress and never writes CONTROL. The first-draft claim that it "can start the motor" was **wrong**. Behaviour for subaddresses other than `0x00`/`0x01` is not documented | Reclassified `register_pointer`; remaining uncertainty is only the undocumented subaddresses |
| **AHT21** | Address `0x38` confirmed. The only commands the datasheet documents are `0x71` (status) and `0xAC` + `0x33 0x00` (trigger). A stray byte equal to `0xAC` would start a measurement (harmless); behaviour for other first bytes is not documented | Stays `write_sensitive` — conservative, because behaviour is undocumented rather than known-safe |
| **ADE7953** | Address `0111000X` = `0x38` confirmed. Write = slave address, **16-bit** register address, then data; read = write of the 16-bit address, then repeated start. The datasheet says nothing about an aborted 1-byte address. No version or identity register appears in this datasheet | Stays `write_sensitive`; `id_probe: null` |
| **MPR121** | Addresses `0x5A`–`0x5D` confirmed (ADDR = VSS/VDD/SDA/SCL). Reads are allowed at any time in Run or Stop mode; only register **writes** are restricted to Stop Mode (§5.1). Nothing in the datasheet says a read clears a status or the IRQ line. No identity register | `register_pointer` confirmed; `id_probe: null` |
| **24AA02UID** | **Address bits A2:A0 are "don't cares"** (§5.0; pins not connected on the 24AA02UID), so the chip ACKs on **all eight** addresses `0x50`–`0x57`. Control code `1010`. A byte write after the word address starts a 5 ms write cycle; a lone word-address write does not | See §7.5 — real design change |

### 7.5 Aliased addresses (found by the 24AA02UID check)

A single 24AA02UID answers on `0x50`–`0x57`, so `scan()` will report **eight** addresses for one chip. The registry therefore gains a field:

| Field | Required | Meaning |
|---|---|---|
| `aliased` | no | `true` when the chip ACKs on every address in `addresses`, not just the one its pins select. Default `false` |

`discover()` gives an aliased chip's addresses special handling: if every address of the alias set ACKs and is mapped only to that chip, they are merged into one `DiscoveredDevice` at the lowest address with `aliases=[…]` listing the rest. If only some of the set ACK, they are reported individually (a different EEPROM, e.g. a 24AA025UID with real chip-select pins, may be present — the 24AA025UID is not in this repo, so that case falls under ambiguity). `validate.js` rejects an `aliased` chip whose `addresses` are not a contiguous block.

The 24AA02UID entry is `addresses: ["0x50..0x57"]`, `aliased: true`. This overrides the chip spec's "use `0x50` as the canonical address", which is a driver-construction convention and does not describe what answers on the bus.

---

## 8. Initial Registry Contents

The full `chips.json` is produced by implementation, one entry per I²C-capable chip, with each value traced to the chip's spec. The entries below fix the **ID data** that disambiguates colliding chips; they are the acceptance data for the tests in §11.

| Chip | Addresses | ID register (wire) | reg_bytes | len / order | mask | expected |
|---|---|---|---|---|---|---|
| BMP085 | `0x77` | `0xD0` | 1 | 1 | `0xFF` | `0x55` |
| BMP180 | `0x77` | `0xD0` | 1 | 1 | `0xFF` | `0x55` |
| BMP280 | `0x76`,`0x77` | `0xD0` | 1 | 1 | `0xFF` | `0x58` |
| BME280 | `0x76`,`0x77` | `0xD0` | 1 | 1 | `0xFF` | `0x60` |
| BME680 | `0x76`,`0x77` | `0xD0` | 1 | 1 | `0xFF` | `0x61` |
| BMP384 | `0x76`,`0x77` | `0x00` | 1 | 1 | `0xFF` | `0x50` |
| BMP581 | `0x46`,`0x47` | `0x01` | 1 | 1 | `0xFF` | `0x50` |
| MPU6050 | `0x68`,`0x69` | `0x75` | 1 | 1 | `0x7E` | `0x68` |
| MPU9250 | `0x68`,`0x69` | `0x75` | 1 | 1 | `0xFF` | `0x71` |
| MPU9255 | `0x68`,`0x69` | `0x75` | 1 | 1 | `0xFF` | `0x73` |
| L3G4200D | `0x68`,`0x69` | `0x0F` | 1 | 1 | `0xFF` | `0xD3` |
| L3GD20H | `0x6A`,`0x6B` | `0x0F` | 1 | 1 | `0xFF` | `0xD7`, `0xD4` |
| LPS22DF | `0x5C`,`0x5D` | `0x0F` | 1 | 1 | `0xFF` | `0xB4` |
| LPS28DFW | `0x5C`,`0x5D` | `0x0F` | 1 | 1 | `0xFF` | `0xB4` |
| LPS33HW | `0x5C`,`0x5D` | `0x0F` | 1 | 1 | `0xFF` | `0xB1` |
| ADXL345 | `0x53`,`0x1D` | `0x00` | 1 | 1 | `0xFF` | `0xE5` |
| APDS9960 | `0x39` | `0x92` | 1 | 1 | `0xFF` | `0xAB` |
| APDS-9930 | `0x39` | `0x92` (ID `0x12` + command bit `0x80`) | 1 | 1 | `0xFF` | `0x39` |
| ENS160 | `0x52`,`0x53` | `0x00` | 1 | 2 / little | `0xFFFF` | `0x0160` |
| INA226 | `0x40`–`0x4F` | `0xFF` (Die ID) | 1 | 2 / big | `0xFFFF` | `0x2260` |
| INA3221 | `0x40`–`0x43` | `0xFF` (Die ID) | 1 | 2 / big | `0xFFFF` | `0x3220` |
| MCP9808 | `0x18`–`0x1F` | `0x07` (Device ID/Rev) | 1 | 2 / big | `0xFF00` | `0x0400` |
| TMP117 | `0x48`–`0x4B` | `0x0F` | 1 | 2 / big | `0x0FFF` | `0x0117` |
| VL53L0X | `0x29` | `0xC0` | 1 | 1 | `0xFF` | `0xEE` |
| VL53L1X | `0x29` | `0x010F` | 2 | 2 / big | `0xFFFF` | `0xEACC` |
| HMC5883L | `0x1E` | `0x0A` | 1 | 3 / big | `0xFFFFFF` | `0x483433` |

ID-less chips (`id_probe: null`): AS5600, DS3231, PCF8523, INA219, MPR121, AHT21, ADE7953, PCF8574/8574A/8575/8576, PCF8591, MCP4725/4728, MCP23017, DRV8830, RDA5807M, 24AA02UID (`aliased`, `0x50..0x57`), NEO-6.

Notes:

- INA226 and INA3221 share Manufacturer ID `0x5449` at `0xFE`; the **Die ID** (`0xFF`) is the distinguishing register (per `specs/power/ina3221.md`).
- The MCP9808 `Device ID/Rev` byte is masked to the upper byte; the lower byte is silicon revision.
- ADXL345's `0x53`/`0x1D` addresses and `DEVID=0xE5` come from its spec; check for collisions with other registry entries is done by `validate.js`, not by hand.
- BMP581 listed as `0x46`/`0x47` per `specs/pressure/bmp581.md`.

---

## 9. Process Changes

### 9.1 `CLAUDE.md` — Flow for chip issues

Step 2 gains a sub-step: for any chip with I²C transport, Claude Code adds the chip's entry to `registry/chips.json` (§4.2) and records any new unavoidable collision in `registry/known_ambiguities.json`, using the address and identity-register data it has just written into the spec. CI (`registry/scripts/validate.js --check` and `generate.js --check`) fails if the entry is missing, malformed, or the generated tables are stale.

### 9.2 `specs/_template_chip.md` and `_template_chip_io_expander.md`

Under `### I²C`, add:

```
- **Identity register:** `0x??` = `0x??` (<name>) — or "none; chip has no ID register"
- **Probe safety:** register_pointer | write_sensitive  — <one line why>
```

### 9.3 `AGENTS.md`

A new "Discovery registry" note in the new-chip checklist: add the `chips.json` entry, run `node registry/scripts/generate.js`, commit generated tables.

---

## 10. Design Decisions (answers to the open questions on #128)

| # | Question | Decision |
|---|---|---|
| 1 | Where does the registry live? | **(a)** a single manifest, `registry/chips.json`, structured so `registry/` can later be split into its own GitHub/GitLab repository fed by an issue form |
| 2 | Process change | **Yes.** Step 2 of the chip flow gains the registry sub-step (§9.1), enforced by CI |
| 3 | Ambiguous results | **Final.** `discover()` returns several `candidates` and no `identified`; no init-sequence heuristics |
| 4 | Scan side effects | Quick write is safe for all current chips; EEPROM-class ranges use read byte; ID probes are gated by `probe_safety` (§7) |

Additional decisions made while writing this spec:

- `identified` requires an identity read matching **exactly one** chip; a sole address candidate without an ID register stays unconfirmed.
- Scan is bus-level; it is *not* a method on `Connection` (connections are bound to one address).
- Go (Linux) is included; it did not exist when #128 was filed.
- JVM discovery is Java-only to avoid the periph-java ↔ periph-groovy dependency collision.

---

## 11. Implementation Checklist

### Registry
- [ ] `aliased` handling (§7.5) in all six `discover()` implementations, with a test using a fake bus where all of `0x50`–`0x57` ACK
- [ ] `registry/schema.json`, `registry/chips.json` (one entry per I²C chip, values traced to specs), `registry/known_ambiguities.json`, `registry/README.md`
- [ ] `registry/scripts/validate.js` (§4.3) with `--check`; unit tests for each failure class
- [ ] `registry/scripts/generate.js` emitting the six tables (§4.5) with `--check`; `--registry <path-or-url>` option
- [ ] CI jobs for both `--check` modes

### Python
- [ ] `python/periph/discovery.py` (`scan`, `discover`, `DiscoveredDevice`), `discovery_registry.py` (generated)
- [ ] Unit tests with a fake bus: scan ranges and method selection (quick vs read byte), `EBUSY`, all §5.2 examples, every row of §8 identifies correctly, failed ID read handling
- [ ] Example `python/examples/discovery/discover.py` (minimal/complete/demo tiers per `CLAUDE.md`)

### C++ (Linux GCC)
- [ ] `Discovery.h/.cpp`, `DiscoveryRegistry.h` (generated); unit tests against a mock bus; excluded from the Arduino/Zephyr/ESP-IDF/Pico SDK builds (verify by running `cpp/scripts/build-all.sh` on all five platforms)
- [ ] Linux examples (minimal/complete/demo)

### Node.js
- [ ] `discovery.js`, `registry.js` (generated); tests; examples; document any quick-write deviation (§3.4)

### Rust
- [ ] `discovery` module generic over `embedded_hal::i2c::I2c`; `no_std` is **not** required; tests with `embedded-hal-mock`; Linux example crates

### JVM
- [ ] `Discovery.java`, `DiscoveryRegistry.java` (generated); JUnit tests; JBang examples (Java only)

### Go
- [ ] `go/periph/discovery`; `go test`; Linux examples (no TinyGo build)

### Docs
- [ ] `CLAUDE.md` chip flow sub-step (§9.1); `_template_chip*.md` fields (§9.2); `AGENTS.md` note (§9.3); wiki page `I2C-Discovery.md` with quick-start snippets and platform matrix, linked from Home and the sidebar

### Hardware-in-loop
- [ ] On a Linux host with at least two chips from different collision groups (e.g. a BME280 and an MPU6050), `discover()` reports both with `identified` set; an ID-less chip at a shared address reports `candidates` only
