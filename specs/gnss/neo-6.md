# Chip Spec: NEO-6

**Manufacturer:** u-blox  
**Datasheet:** `datasheets/gnss/neo-6.pdf`  
**Protocol spec:** `datasheets/gnss/neo-6.pdf` (hardware); protocol details from u-blox 6 Receiver Description Including Protocol Specification GPS.G6-SW-10018-F  
**Category:** `gnss`  
**Transports:** UART (primary), I²C (DDC), SPI

## Overview

The NEO-6 series are stand-alone 50-channel GPS receiver modules from u-blox, built around the u-blox 6 positioning engine. They deliver a Time-To-First-Fix (TTFF) of under 1 second (hot start), –162 dBm tracking sensitivity, 2.5 m horizontal position accuracy (GPS, 2.0 m with SBAS), and 0.1 m/s velocity accuracy. The module outputs data over UART, DDC (I²C-compatible), or SPI — all three interfaces expose the same NMEA/UBX protocol byte stream; the transport choice affects only how bytes are framed and clocked.

The module ships in a miniature 16.0 × 12.2 × 2.4 mm LCC package and requires no external calibration or trimming.

## Transport Configuration

### UART

- **Baud rate:** 9600 (default, configurable: 4800 / 9600 / 19200 / 38400 / 57600 / 115200)
- **Frame:** 8N1 (8 data bits, no parity, 1 stop bit)
- **Flow control:** none
- **Pins (NEO-6 module):** TxD1 = pin 20 (module output), RxD1 = pin 21 (module input)
- **Default output:** NMEA sentences at 1 Hz; configurable via UBX CFG-PRT and CFG-MSG

The UART interface is always active regardless of CFG_COM pin state; changing baud rate requires a matching CFG-PRT command sent at the current baud first.

### I²C (DDC — Display Data Channel)

- **Address:** `0x42` (default, configurable via CFG-PRT for DDC target)
- **Max clock:** 100 kHz (I²C Standard Mode only; High-Speed Mode not supported)
- **Pull-ups:** required on SDA and SCL
- **Pins:** SDA2 = pin 18, SCL2 = pin 19

**DDC register layout:**

| Register | Description |
|----------|-------------|
| `0x00`–`0xFC` | Undefined data registers (reserved; do not use) |
| `0xFD` | High byte of available byte count in transmit buffer |
| `0xFE` | Low byte of available byte count in transmit buffer |
| `0xFF` | Data stream: each read returns the next byte from the message FIFO; returns `0xFF` if no data is available |

**Read procedure:** perform a random-read to address `0xFF`, then read up to N bytes in one I²C transaction; the module's internal address counter auto-increments and saturates at `0xFF`, so subsequent current-address reads continue the byte stream. Padding bytes read beyond the available data are `0xFF`.

**Write procedure:** address the device for write and send UBX or NMEA message bytes directly (no register prefix required). The minimum write is 2 bytes (to distinguish from the 1-byte random-read address set).

**Idle timeout:** if the host does not read for ≥ 2 seconds the module discards pending output and schedules no new messages for DDC. The 4 kB transmit buffer is also the hard limit; bytes beyond it are silently dropped.

### SPI

- **Mode:** Mode 0 (CPOL=0, CPHA=0) by default; configurable to modes 0–3 via CFG-PRT for SPI target
- **Max clock:** 200 kHz (firmware 7.x); 100 kHz (firmware 6.02)
- **Bit order:** MSB first
- **CS active:** low (SS_N = pin 2, module)
- **CS timing:** SS_N must be asserted ≥ 500 μs before SCK starts (t_INIT); SS_N must stay high ≥ 1 ms after the last clock edge (t_DES)
- **Pins:** MOSI/CFG_COM0 = pin 14, MISO/CFG_COM1 = pin 15, SCK/CFG_GPS0 = pin 16, SS_N = pin 2

**SPI back-to-back access:** the module does not implement the DDC register layout on SPI. Only the raw UBX/NMEA byte stream is accessible. Every SPI transfer is full-duplex: the host sends filler bytes (`0xFF`) on MOSI while simultaneously reading received bytes on MISO. The module outputs `0xFF` on MISO when it has no data to send. The host must filter out leading `0xFF` bytes from MISO; the first non-`0xFF` byte starts a message.

**Parser idle:** if the module receives 50 consecutive `0xFF` bytes on MOSI it stops the incoming parser (configurable via `mode.ffCnt` in CFG-PRT for SPI). The parser restarts when the first non-`0xFF` byte arrives.

**Note:** using SPI disables the CFG_COM0, CFG_COM1, and CFG_GPS0 hardware boot-time configuration pins (they are shared with SPI signals). Configure the port parameters via UBX messages instead.

**Idle timeout:** same 2-second idle rule as DDC applies to SPI.

## Protocol Overview

All three interfaces support NMEA 0183 (version 2.3, ASCII) and UBX (binary, u-blox proprietary). Both protocols can be active simultaneously on one port. By default (CFG_COM0=1, CFG_COM1=1) the module outputs NMEA with these sentences enabled at 1 Hz: GGA, GLL, GSA, GSV, RMC, VTG, TXT.

### NMEA Sentences

**GGA — Global Positioning Fix Data** (primary position sentence)

```
$GPGGA,hhmmss.ss,lat,N,lon,E,FS,NoSV,HDOP,msl,M,Altref,M,DiffAge,DiffStation*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | UTC Time | `hhmmss.ss` |
| 2–3 | Latitude | `ddmm.mmmm,N` (degrees + decimal minutes, N/S) |
| 4–5 | Longitude | `dddmm.mmmm,E` (degrees + decimal minutes, E/W) |
| 6 | Fix Status | 0=no fix, 1=GPS, 2=DGPS |
| 7 | Satellites Used | 0–12 |
| 8 | HDOP | Horizontal dilution of precision |
| 9–10 | MSL Altitude | Meters above mean sea level |
| 11–12 | Geoid Separation | Meters |

**RMC — Recommended Minimum Data**

```
$GPRMC,hhmmss.ss,status,lat,N,lon,E,spd,cog,ddmmyy,,,mode*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 2 | Status | A=valid, V=warning |
| 7 | Speed | Knots over ground (must convert to m/s: × 0.514444) |
| 8 | Course | Degrees over ground, 0–360 |
| 9 | Date | `ddmmyy` |

**GSA** — DOP and active satellites (PDOP, HDOP, VDOP, fix type 1/2/3)  
**GSV** — Satellites in view (PRN, elevation °, azimuth °, SNR dBHz per SV)  
**VTG** — Course and speed over ground (also gives speed in km/h)  
**GLL** — Latitude/longitude with status  
**TXT** — Text messages (startup notices, software version)

### NMEA Coordinate Conversion

NMEA encodes coordinates as degrees + decimal minutes (`ddmm.mmmm` / `dddmm.mmmm`), not decimal degrees:

```
decimal_degrees = floor(raw / 100) + (raw mod 100) / 60
```

Apply a negative sign for `S` latitude or `W` longitude.

Example: `4717.11399,N` → 47 + 17.11399 / 60 = 47.285233°

### UBX Protocol

Binary framing for configuration and high-precision data:

```
0xB5  0x62  CLASS  ID  LENGTH(2B LE)  PAYLOAD  CK_A  CK_B
```

Key messages used by the driver:

| Message | Class/ID | Direction | Description |
|---------|----------|-----------|-------------|
| NAV-POSLLH | `0x01 0x02` | out | Geodetic position: lat/lon (1e-7°), height above ellipsoid and MSL (mm), hAcc/vAcc (mm) |
| NAV-STATUS | `0x01 0x03` | out | Fix type (`gpsFix`), validity flag (`gpsFixOk`), TTFF |
| NAV-SOL | `0x01 0x06` | out | Navigation solution: fix type, numSV, pDOP |
| NAV-SVINFO | `0x01 0x30` | out | Per-channel SV info: SVid, SNR, elevation, azimuth, used/health flags |
| CFG-PRT | `0x06 0x00` | in/out | Port configuration (baud, protocol mask, I²C address, SPI mode) |
| CFG-MSG | `0x06 0x01` | in/out | Enable/disable individual messages per port |
| CFG-RATE | `0x06 0x08` | in/out | Navigation/measurement rate (default 1 Hz) |
| CFG-NAV5 | `0x06 0x24` | in/out | Navigation engine: dynamic platform model, fix mode |
| CFG-CFG | `0x06 0x09` | in | Save/clear/load current configuration to non-volatile storage |
| CFG-RST | `0x06 0x04` | in | Cold/warm/hot start |
| CFG-RXM | `0x06 0x11` | in/out | Power mode (0=max performance, 1=power save) |
| ACK-ACK | `0x05 0x01` | out | Positive acknowledgement for CFG messages |
| ACK-NAK | `0x05 0x00` | out | Negative acknowledgement |

**NAV-POSLLH payload (28 bytes):**

| Offset | Type | Scale | Name | Unit | Description |
|--------|------|-------|------|------|-------------|
| 0 | U4 | — | iTOW | ms | GPS millisecond time of week |
| 4 | I4 | 1e-7 | lon | deg | Longitude |
| 8 | I4 | 1e-7 | lat | deg | Latitude |
| 12 | I4 | — | height | mm | Height above ellipsoid |
| 16 | I4 | — | hMSL | mm | Height above mean sea level |
| 20 | U4 | — | hAcc | mm | Horizontal accuracy estimate |
| 24 | U4 | — | vAcc | mm | Vertical accuracy estimate |

**NAV-STATUS payload (16 bytes):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | U4 | iTOW | GPS ms time of week |
| 4 | U1 | gpsFix | 0x00=no fix, 0x02=2D-fix, 0x03=3D-fix, 0x04=GPS+DR, 0x05=time only |
| 5 | X1 | flags | bit 0 = gpsFixOk (position valid and within DOP/ACC masks) |
| 6 | X1 | fixStat | DGPS status |
| 7 | X1 | flags2 | PSM state |
| 8 | U4 | ttff | ms to first fix |
| 12 | U4 | msss | ms since startup/reset |

**Important:** `gpsFix` alone does not confirm a valid position. The `gpsFixOk` flag in `flags` must also be set.

## Initialization Sequence

1. Apply power; wait ≥ 100 ms for the module to boot and begin NMEA output.
2. Open the transport at the appropriate parameters (UART: 9600 8N1; I²C: address 0x42, ≤100 kHz; SPI: mode 0, ≤200 kHz, with CS timing).
3. Flush/discard any partial NMEA data already in the receive buffer.
4. Begin reading complete sentences terminated by `\r\n` (UART/SPI/DDC all deliver the same framing within the byte stream).
5. Parse GGA sentences for position; check fix status field > 0 before trusting coordinates.
6. (Optional) Send UBX CFG-PRT to reconfigure baud/rate, CFG-MSG to disable unneeded sentences, CFG-NAV5 to set platform model.

No register writes or chip-side configuration are required for basic NMEA position reading — the module self-configures from boot.

## Implementation Stages

Each stage is implemented in two classes. The Full class inherits Minimal and adds the rest.

### Minimal

Goal: read GPS position from the default NMEA stream without any configuration. The transport accepts any of the three interfaces; the driver reads raw bytes and assembles complete `\r\n`-terminated sentences.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | Initialises internal sentence buffer; no hardware configuration |
| `update` | — | bool | Reads bytes from transport; parses one complete NMEA sentence; updates internal state; returns `True` if a GGA sentence with a valid fix was parsed |
| `latitude` | — | float \| None | Decimal degrees, positive=North; `None` until first valid GGA |
| `longitude` | — | float \| None | Decimal degrees, positive=East; `None` until first valid GGA |
| `altitude` | — | float \| None | Height above MSL in meters; `None` until first valid GGA |
| `fix` | — | int | GGA fix quality: 0=no fix, 1=GPS, 2=DGPS |
| `satellites` | — | int | Number of satellites in use (GGA field 7) |

**Sensible defaults:** no CFG messages sent; module runs with its factory defaults (NMEA, 9600, 1 Hz, all standard sentences enabled).

**Transport abstraction:** the Minimal driver reads bytes through `transport.read(n)` → `bytes`. The three transport implementations differ only in their `read()` method:
- UART: direct serial read
- I²C (DDC): write `[0xFF]` to set register pointer, then read n bytes; filter trailing `0xFF` padding
- SPI: transfer `[0xFF] * n` as MOSI filler; return MISO bytes; caller must filter leading `0xFF` idle bytes

### Full

Goal: expose UBX binary messaging, navigation rate control, platform configuration, and richer NMEA fields. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `speed` | — | float \| None | Speed over ground in m/s (RMC, converted from knots × 0.514444) |
| `course` | — | float \| None | Course over ground in degrees 0–360 (RMC/VTG) |
| `utc_time` | — | str \| None | UTC time string `hhmmss.ss` from last RMC or GGA |
| `utc_date` | — | str \| None | UTC date string `ddmmyy` from last RMC |
| `hdop` | — | float \| None | Horizontal dilution of precision from GGA |
| `send_ubx` | msg_class, msg_id, payload=b'' | None | Frames and writes a UBX message (adds sync, length, checksum) |
| `poll_ubx` | msg_class, msg_id | bytes | Sends a poll request and returns the response payload; raises on ACK-NAK |
| `set_rate` | hz: int | None | Set navigation update rate (1–5 Hz for standard models) via CFG-RATE |
| `set_platform` | model: int | None | Set dynamic platform model via CFG-NAV5 (0=portable, 2=stationary, 3=pedestrian, 4=automotive, 5=sea, 6=airborne<1g, 7=airborne<2g, 8=airborne<4g) |
| `cold_start` | — | None | Force cold start via CFG-RST (clears almanac, ephemeris, position) |
| `save_config` | — | None | Persist current configuration via CFG-CFG (saves to BBR and flash if available) |

**Additional configuration options:** all CFG-MSG, CFG-PRT, CFG-NAV5, CFG-NAVX5, CFG-RXM, CFG-PM2, CFG-TP5, CFG-SBAS, CFG-CFG are accessible through `send_ubx`/`poll_ubx` for advanced use.

## Data Conversion

### NMEA coordinates → decimal degrees

```
raw = float(field)   # e.g. 4717.11399
deg = int(raw / 100)
minutes = raw - deg * 100
decimal_degrees = deg + minutes / 60
# Apply sign: negative for S or W
```

### NMEA speed → m/s

```
speed_ms = speed_knots * 0.514444
```

### UBX NAV-POSLLH lat/lon → decimal degrees

```
decimal_degrees = raw_I4 * 1e-7
```

### UBX NAV-POSLLH height → meters

```
height_m = raw_I4 * 0.001
```

## Node-RED

Node name: `periph-neo-6`  
Package: `node-red-contrib-periph-gnss`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ lat: float, lon: float, alt: float, fix: int, satellites: int, speed: float, course: float, time: str, date: str, hdop: float }` | Fields are `null` until a valid fix is acquired; `lat`/`lon` in decimal degrees, `alt` in meters MSL, `speed` in m/s, `course` in degrees |

Config panel fields: transport type (UART / I²C / SPI), serial port or bus number, UART baud rate (default 9600), I²C address (default `0x42`).

### Demo flow

Injects a timestamp trigger every second → NEO-6 node reads the current NMEA fix → a Function node formats the output as a `$lat,$lon` CSV string → a Debug node logs to the sidebar. A second output branch feeds a WorldMap node to display the live position on a map.

## Examples

### Demo

A portable GPS logger that reads position once per second and writes fix data to the console in a structured format. When the fix quality is 0 (no fix), print a waiting indicator and satellite count. Once a fix is acquired, print UTC time, latitude (decimal degrees, 6 dp), longitude (decimal degrees, 6 dp), altitude (m, 1 dp), satellites, and HDOP. Run for 60 seconds then exit. This demonstrates cold-start wait time, the transition from no-fix to fix, and field-by-field extraction from NMEA GGA/RMC sentences.

## Timing Constraints

- **Power-on to first NMEA output:** ≥ 100 ms (module boot)
- **Cold start TTFF:** 26 s typical (NEO-6G/Q/T, –130 dBm signal, no aiding)
- **Warm start TTFF:** 26 s typical
- **Hot start TTFF:** 1 s typical
- **Aided start TTFF:** 1 s (with A-GPS data)
- **SPI CS t_INIT:** ≥ 500 μs (SS_N assert before first SCK)
- **SPI CS t_DES:** ≥ 1 ms (SS_N deassert hold after last SCK)
- **SPI/DDC idle timeout:** 2 s without host reads discards pending output
- **Default navigation rate:** 1 Hz

## Implementation Notes

- **0xFF is the NMEA/UBX stream idle filler** on both DDC and SPI. It can never appear as a valid first byte of an NMEA sentence (`$` = 0x24) or UBX message (0xB5), so filter it from the front of the received byte stream. Embedded 0xFF inside a message is possible in UBX binary payloads; stop filtering once message framing begins.

- **DDC 2-second idle rule:** if the driver pauses reading for ≥ 2 seconds, the module drops all buffered output. Always call `update()` at least once per second when using I²C or SPI.

- **SPI parser stall:** after 50 consecutive 0xFF bytes received on MOSI the module stops parsing incoming data. Send at most 50 MOSI filler bytes per transaction or configure `mode.ffCnt` in CFG-PRT for SPI.

- **gpsFixOk vs gpsFix:** the GGA fix-status field (and `gpsFix` in UBX NAV-STATUS) indicates fix type but does NOT guarantee position validity. Always check that the GGA fix status field is ≥ 1 (or UBX `flags.gpsFixOk` = 1) before exposing coordinates.

- **NMEA sentence checksum validation:** each NMEA sentence ends with `*XX` (two hex digits, XOR of all bytes between `$` and `*`). The driver must verify the checksum and discard malformed sentences silently rather than surfacing bad data.

- **CFG_COM pin conflict with SPI:** pins 14 (MOSI), 15 (MISO), and 16 (SCK) are shared with CFG_COM0, CFG_COM1, and CFG_GPS0. When SPI is used, leave these pins floating (internal pull-ups in the module keep them high); do not connect external pull-down configuration resistors.

- **I²C clock stretching:** the module may clock-stretch during internal processing. The host I²C master must support clock stretching.

- **Default sentence set consumes bandwidth:** at 9600 baud with GSV enabled, a full GSV group (up to 4 sentences × 72 bytes each) plus GGA/RMC/GSA/GLL/VTG totals ≈ 700 bytes/second — close to the 9600/10 ≈ 960 byte/s limit. Disable unneeded sentences via CFG-MSG if using high satellite counts or higher rates.

## Sigrok Decoder

The NEO-6 does not expose a traditional register-mapped bus; the sigrok decoder operates on the byte stream rather than individual registers. For UART captures, use the sigrok `uart` decoder with 9600 8N1, then a custom `neo6_nmea` upper-level decoder that matches the talker ID `GP`, sentence IDs `GGA`/`RMC`/`GSA`/`GSV`, and annotates each comma-separated field with its name and parsed value (decimal-degree coordinates, m/s speed, UTC time). For DDC (I²C) captures, use the sigrok `i2c` decoder with address filter `0x42`, then the same `neo6_nmea` upper-level decoder on the read data bytes, skipping `0xFF` filler.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

> **Transport convention for examples:** all examples use UART as the canonical transport. Each example file includes a comment block at the top showing the one-line swap for I²C (DDC) or SPI. Tests multiply by transport on Linux; UART is the primary transport for embedded-platform tests.

### Python
- [x] Driver `python/periph/chips/gnss/neo6.py` — Google-style docstring on every class and public method
- [x] Examples `python/examples/gnss/neo6/minimal.py` — Tier-1 signature comment on every call (UART transport; comment shows DDC/SPI swap)
- [x] Examples `python/examples/gnss/neo6/complete.py` — Tier-1 + Tier-2
- [x] Examples `python/examples/gnss/neo6/demo.py` — Tier-1 + Tier-3
- [x] Tests `python/tests/gnss/neo6_test.py` (MicroPython — UART)
- [x] Tests `python/tests/gnss/neo6_test_cp.py` (CircuitPython — UART)
- [x] Tests `python/tests/gnss/neo6_test_linux_uart.py` (Linux — UART)
- [x] Tests `python/tests/gnss/neo6_test_linux_i2c.py` (Linux — I²C/DDC)
- [x] Tests `python/tests/gnss/neo6_test_linux_spi.py` (Linux — SPI)

### C++
- [x] Driver `cpp/src/chips/gnss/NEO6.h` — Doxygen `/** @brief */` on every class and public method
- [x] Driver `cpp/src/chips/gnss/NEO6.cpp`
- [x] Examples `cpp/examples/NEO6_Minimal/NEO6_Minimal.ino` — Tier-1 (UART; comment shows DDC/SPI swap)
- [x] Examples `cpp/examples/NEO6_Complete/NEO6_Complete.ino` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/NEO6_Demo/NEO6_Demo.ino` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/NEO6_Minimal_Zephyr/src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/NEO6_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/NEO6_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [x] Tests `cpp/tests/gnss/neo6_test/neo6_test.ino` (Arduino — UART)
- [x] Tests `cpp/tests/gnss/neo6_test_linux/neo6_test_linux.cpp` (Linux GCC — UART)
- [x] Tests `cpp/tests/gnss/neo6_test_linux_i2c/neo6_test_linux_i2c.cpp` (Linux GCC — I²C)
- [x] Tests `cpp/tests/gnss/neo6_test_linux_spi/neo6_test_linux_spi.cpp` (Linux GCC — SPI)
- [x] Tests `cpp/tests/gnss/neo6_test_zephyr/src/main.cpp` (Zephyr — UART)

### Node.js
- [x] Driver `nodejs/packages/periph/src/chips/gnss/neo6.js` — JSDoc on every class and exported method
- [x] Examples `nodejs/packages/periph/examples/gnss/neo6/minimal.js` — Tier-1 (UART; comment shows DDC/SPI swap)
- [x] Examples `nodejs/packages/periph/examples/gnss/neo6/complete.js` — Tier-1 + Tier-2
- [x] Examples `nodejs/packages/periph/examples/gnss/neo6/demo.js` — Tier-1 + Tier-3
- [x] Tests `nodejs/tests/gnss/neo6_test_uart.js` (UART)
- [x] Tests `nodejs/tests/gnss/neo6_test_i2c.js` (I²C)
- [x] Tests `nodejs/tests/gnss/neo6_test_spi.js` (SPI)

### Node-RED
- [x] Node runtime `nodejs/packages/node-red-contrib-periph-gnss/nodes/neo6/neo6.js`
- [x] Node editor `nodejs/packages/node-red-contrib-periph-gnss/nodes/neo6/neo6.html` — `data-help-name` section with inputs, outputs, and config description
- [x] Demo flow `nodejs/packages/node-red-contrib-periph-gnss/examples/neo6/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/gnss/neo6.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/neo6_minimal/src/main.rs` — Tier-1 (UART; comment shows DDC/SPI swap)
- [ ] Examples `rust/examples/neo6_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/neo6_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/gnss/neo6_test_uart/src/main.rs` (Linux — UART)
- [ ] Tests `rust/tests/gnss/neo6_test_i2c/src/main.rs` (Linux — I²C)
- [ ] Tests `rust/tests/gnss/neo6_test_spi/src/main.rs` (Linux — SPI)
- [ ] Tests `rust/tests/gnss/neo6_test_esp32s3/src/main.rs` (ESP32-S3 — UART)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gnss/Neo6.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gnss/Neo6.kt`
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gnss/Neo6.groovy`
- [ ] Examples `jvm/examples/java/gnss/neo6/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/gnss/neo6/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/gnss/neo6/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/gnss/neo6/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/gnss/neo6/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/gnss/neo6/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/gnss/neo6/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/gnss/neo6/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/gnss/neo6/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/gnss/neo6/Neo6Test.java`

### Sigrok
- [x] Decoder `sigrok/neo6/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [x] Decoder `sigrok/neo6/pd.py` — annotates NMEA sentence fields (sentence ID, lat, lon, fix, sats, HDOP, speed, course, UTC time/date); produces `OUTPUT_ANN` only
