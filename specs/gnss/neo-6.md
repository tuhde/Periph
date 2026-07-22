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
| 13 | Age of Diff Corr | Seconds since last DGPS update; blank if not using DGPS |
| 14 | Diff Station ID | Reference station ID, 0000–1023; blank if not using DGPS |

**RMC — Recommended Minimum Data**

```
$GPRMC,hhmmss.ss,status,lat,N,lon,E,spd,cog,ddmmyy,mv,mvE,mode*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | UTC Time | `hhmmss.ss` |
| 2 | Status | A=valid, V=warning |
| 3–4 | Latitude | `ddmm.mmmm,N` (degrees + decimal minutes, N/S) |
| 5–6 | Longitude | `dddmm.mmmm,E` (degrees + decimal minutes, E/W) |
| 7 | Speed | Knots over ground (must convert to m/s: × 0.514444) |
| 8 | Course | Degrees over ground, 0–360 |
| 9 | Date | `ddmmyy` |
| 10–11 | Magnetic Variation | Degrees + E/W; blank if not available |
| 12 | Mode | A=autonomous, D=DGPS, E=estimated, N=not valid (NMEA 2.3+; blank on older firmware) |

**GSA — DOP and Active Satellites**

```
$GPGSA,mode1,mode2,sv1,sv2,...,sv12,PDOP,HDOP,VDOP*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | Selection Mode | M=manual, A=automatic |
| 2 | Fix Type | 1=no fix, 2=2D, 3=3D |
| 3–14 | Satellites Used | PRN of each satellite in the solution (up to 12 slots; blank where unused) |
| 15 | PDOP | Position dilution of precision |
| 16 | HDOP | Horizontal dilution of precision |
| 17 | VDOP | Vertical dilution of precision |

**GSV — Satellites in View**

```
$GPGSV,numMsg,msgNum,numSV,{PRN,elev,azim,SNR}×4*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | Number of Messages | Total sentences in this GSV group |
| 2 | Message Number | Sequence number within the group (1-based) |
| 3 | Satellites in View | Total count across the whole group |
| 4, 8, 12, 16 | Satellite PRN | Satellite ID, one per SV block (up to 4 blocks per sentence) |
| 5, 9, 13, 17 | Elevation | Degrees, 0–90 |
| 6, 10, 14, 18 | Azimuth | Degrees, 0–359 |
| 7, 11, 15, 19 | SNR | dBHz, 0–99; blank if not tracking |

**VTG — Course and Speed Over Ground**

```
$GPVTG,cogt,T,cogm,M,sogn,N,sogk,K,mode*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1–2 | Course (True) | Degrees, `T` reference |
| 3–4 | Course (Magnetic) | Degrees, `M` reference; blank if not available |
| 5–6 | Speed | Knots, `N` unit |
| 7–8 | Speed | km/h, `K` unit (must convert to m/s: ÷ 3.6) |
| 9 | Mode | A=autonomous, D=DGPS, E=estimated, N=not valid (NMEA 2.3+; blank on older firmware) |

**GLL — Latitude/Longitude**

```
$GPGLL,lat,N,lon,E,hhmmss.ss,status,mode*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1–2 | Latitude | `ddmm.mmmm,N` (degrees + decimal minutes, N/S) |
| 3–4 | Longitude | `dddmm.mmmm,E` (degrees + decimal minutes, E/W) |
| 5 | UTC Time | `hhmmss.ss` |
| 6 | Status | A=valid, V=invalid |
| 7 | Mode | A=autonomous, D=DGPS, E=estimated, N=not valid (NMEA 2.3+; blank on older firmware) |

**TXT — Text Transmission**

```
$GPTXT,numMsg,msgNum,msgType,text*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | Number of Messages | Total sentences in this TXT group |
| 2 | Message Number | Sequence number within the group |
| 3 | Severity | 00=error, 01=warning, 02=notice, 07=user |
| 4 | Text | Free-form ASCII message (startup notice, firmware version, etc.) |

### NMEA Coordinate Conversion

NMEA encodes coordinates as degrees + decimal minutes (`ddmm.mmmm` / `dddmm.mmmm`), not decimal degrees:

```
decimal_degrees = floor(raw / 100) + (raw mod 100) / 60
```

Apply a negative sign for `S` latitude or `W` longitude.

Example: `4717.11399,N` → 47 + 17.11399 / 60 = 47.285233°

### PUBX Proprietary Sentences

u-blox NEO-6 modules also support u-blox-proprietary NMEA-framed sentences, identified by the address field `PUBX` (`P` = proprietary, `UBX` = manufacturer mnemonic) instead of a talker ID + 3-letter sentence ID. The first data field after the address carries a 2-digit message ID identifying the sentence type. These share the ASCII `$...*XX<CR><LF>` framing and checksum with standard NMEA but are not part of the default sentence set — the three output messages (00, 03, 04) are enabled individually via UBX CFG-MSG on class `0xF1` (e.g. `0xF1 0x00` enables PUBX,00); 40 and 41 are host→receiver configuration commands, always accepted as input regardless of CFG-MSG state.

**PUBX,00 — POSITION** (output)

```
$PUBX,00,hhmmss.ss,lat,N,long,E,altRef,navStat,hAcc,vAcc,SOG,COG,vVel,ageC,HDOP,VDOP,TDOP,numSvs,reserved,DR*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | UTC Time | `hhmmss.ss` |
| 2–3 | Latitude | `ddmm.mmmmm,N` (degrees + decimal minutes, N/S) |
| 4–5 | Longitude | `dddmm.mmmmm,E` (degrees + decimal minutes, E/W) |
| 6 | Altitude | Meters above the WGS84 ellipsoid |
| 7 | Nav Status | `NF`=no fix, `DR`=dead reckoning only, `G2`=2D GPS, `G3`=3D GPS, `D2`=2D differential, `D3`=3D differential, `RK`=GPS+DR combined, `TT`=time only |
| 8 | Horizontal Accuracy | Meters |
| 9 | Vertical Accuracy | Meters |
| 10 | Speed Over Ground | km/h (must convert to m/s: ÷ 3.6) |
| 11 | Course Over Ground | Degrees, 0–360 |
| 12 | Vertical Velocity | m/s, downward positive |
| 13 | Age of Diff Corrections | Seconds; blank if not using DGPS |
| 14 | HDOP | Horizontal dilution of precision |
| 15 | VDOP | Vertical dilution of precision |
| 16 | TDOP | Time dilution of precision |
| 17 | Satellites Used | Count |
| 18 | Reserved | — |
| 19 | Dead Reckoning | 0=not used, 1=used |

**PUBX,03 — SVSTATUS** (output)

```
$PUBX,03,numSv,{sv,status,azi,ele,cno,lck}×numSv*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | Number of Satellites | Count of `{sv,status,azi,ele,cno,lck}` blocks that follow |
| 2, 8, 14, ... | Satellite ID | SV PRN (GPS 1–32, SBAS 120–158, ...) |
| 3, 9, 15, ... | Status | `-`=not used, `U`=used in solution, `e`=ephemeris available but not used |
| 4, 10, 16, ... | Azimuth | Degrees, 0–359 |
| 5, 11, 17, ... | Elevation | Degrees, 0–90 |
| 6, 12, 18, ... | SNR | dBHz |
| 7, 13, 19, ... | Lock Time | Seconds, capped at 63 |

**PUBX,04 — TIME** (output)

```
$PUBX,04,hhmmss.ss,ddmmyy,utcTow,utcWk,leapSec,clkBias,clkDrift,tpGran*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | UTC Time | `hhmmss.ss` |
| 2 | UTC Date | `ddmmyy` |
| 3 | UTC Time of Week | Seconds |
| 4 | UTC Week Number | GPS week |
| 5 | Leap Seconds | Trailing `D` if the value is a firmware default rather than receiver-confirmed |
| 6 | Clock Bias | Nanoseconds |
| 7 | Clock Drift | ns/s |
| 8 | Time Pulse Granularity | Nanoseconds |

**PUBX,40 — RATE** (input, configures NMEA sentence rates)

```
$PUBX,40,msgId,rddc,rus1,rus2,rusb,rspi,reserved*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | Message ID | Target NMEA sentence: 00=GGA, 01=GLL, 02=GSA, 03=GSV, 04=RMC, 05=VTG, 06=GRS, 07=GST, 08=ZDA, 09=GBS |
| 2 | Rate on DDC | Output every N navigation cycles, 0=off |
| 3 | Rate on UART1 | Output every N navigation cycles, 0=off |
| 4 | Rate on UART2 | Output every N navigation cycles, 0=off |
| 5 | Rate on USB | Output every N navigation cycles, 0=off |
| 6 | Rate on SPI | Output every N navigation cycles, 0=off |
| 7 | Reserved | — |

**PUBX,41 — CONFIG** (input, configures port protocol/baud rate)

```
$PUBX,41,portId,inProto,outProto,baudrate,autobauding*cs<CR><LF>
```

| Field | Name | Description |
|-------|------|-------------|
| 1 | Port ID | 0=DDC, 1=UART1, 2=UART2, 3=USB, 4=SPI |
| 2 | Input Protocols | Hex bitmask: bit 0=UBX, bit 1=NMEA, bit 5=RTCM |
| 3 | Output Protocols | Hex bitmask: bit 0=UBX, bit 1=NMEA |
| 4 | Baud Rate | bd (UART ports only) |
| 5 | Autobauding | 0=disabled, 1=enabled |

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

**NAV-SOL payload (52 bytes):**

| Offset | Type | Scale | Name | Unit | Description |
|--------|------|-------|------|------|-------------|
| 0 | U4 | — | iTOW | ms | GPS millisecond time of week |
| 4 | I4 | — | fTOW | ns | Fractional part of iTOW |
| 8 | I2 | — | week | — | GPS week number |
| 10 | U1 | — | gpsFix | — | Fix type (same encoding as NAV-STATUS `gpsFix`) |
| 11 | X1 | — | flags | — | Fix status bitmask |
| 12 | I4 | — | ecefX | cm | ECEF X coordinate |
| 16 | I4 | — | ecefY | cm | ECEF Y coordinate |
| 20 | I4 | — | ecefZ | cm | ECEF Z coordinate |
| 24 | U4 | — | pAcc | cm | 3D position accuracy estimate |
| 28 | I4 | — | ecefVX | cm/s | ECEF X velocity |
| 32 | I4 | — | ecefVY | cm/s | ECEF Y velocity |
| 36 | I4 | — | ecefVZ | cm/s | ECEF Z velocity |
| 40 | U4 | — | sAcc | cm/s | Speed accuracy estimate |
| 44 | U2 | 0.01 | pDOP | — | Position dilution of precision |
| 46 | U1 | — | reserved1 | — | Reserved |
| 47 | U1 | — | numSV | — | Number of satellites used in the solution |
| 48 | U4 | — | reserved2 | — | Reserved |

**NAV-SVINFO payload (8-byte header + 12 bytes × `numCh`):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | U4 | iTOW | GPS ms time of week |
| 4 | U1 | numCh | Number of channels (repeat blocks) that follow |
| 5 | X1 | globalFlags | Chip type indicator |
| 6 | U2 | reserved2 | Reserved |

Per-channel repeat block (12 bytes, `numCh` times, starting at offset 8):

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| +0 | U1 | chn | Channel number |
| +1 | U1 | svid | Satellite ID |
| +2 | X1 | flags | svUsed / diffCorr / orbitAvail / orbitEph / unhealthy / orbitAlm / orbitAop / smoothed bitmask |
| +3 | X1 | quality | Signal quality indicator |
| +4 | U1 | cno | Signal strength, dBHz |
| +5 | I1 | elev | Elevation, degrees |
| +6 | I2 | azim | Azimuth, degrees |
| +8 | I4 | prRes | Pseudorange residual, cm |

**CFG-PRT payload (poll: 0 or 1 bytes; set/response: 20 bytes):**

A 0-byte payload polls all ports; a 1-byte payload (`portID`) polls one port. The 20-byte form:

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | U1 | portID | 0=DDC, 1=UART1, 4=SPI |
| 1 | U1 | reserved0 | Reserved |
| 2 | X2 | txReady | TX-ready pin configuration bitmask |
| 4 | X4 | mode | Port-specific mode bitfield (UART: char length/parity/stop bits; DDC: slave address; SPI: SPI mode) |
| 8 | U4 | baudRate | Baud rate, bd (UART only; meaningless for DDC/SPI) |
| 12 | X2 | inProtoMask | Input protocols enabled (bit 0=UBX, bit 1=NMEA) |
| 14 | X2 | outProtoMask | Output protocols enabled (bit 0=UBX, bit 1=NMEA) |
| 16 | X2 | flags | Extended TX timeout flag |
| 18 | X2 | reserved5 | Reserved |

**CFG-MSG payload (poll: 2 bytes; set short: 3 bytes; set long: 8 bytes):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | U1 | msgClass | Class of the message to (de)configure |
| 1 | U1 | msgID | ID of the message to (de)configure |
| 2 | U1 | rate | Send rate on the current target port (3-byte set form only) |
| 2–7 | U1×6 | rate[6] | Send rate per port: DDC, UART1, UART2, USB, SPI, reserved (8-byte set form only) |

**CFG-RATE payload (6 bytes):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | U2 | measRate | Measurement rate, ms |
| 2 | U2 | navRate | Navigation rate, cycles (always 1 on NEO-6) |
| 4 | U2 | timeRef | 0=UTC, 1=GPS |

**CFG-NAV5 payload (36 bytes):**

| Offset | Type | Scale | Name | Unit | Description |
|--------|------|-------|------|------|-------------|
| 0 | X2 | — | mask | — | Parameters bitmask (which fields below apply) |
| 2 | U1 | — | dynModel | — | Dynamic platform model, 0–8 |
| 3 | U1 | — | fixMode | — | 1=2D only, 2=3D only, 3=auto 2D/3D |
| 4 | I4 | 0.01 | fixedAlt | m | Fixed altitude for 2D fix mode |
| 8 | U4 | 0.0001 | fixedAltVar | m² | Fixed altitude variance |
| 12 | I1 | — | minElev | deg | Minimum satellite elevation |
| 13 | U1 | — | drLimit | s | Dead reckoning time limit |
| 14 | U2 | 0.1 | pDop | — | Position DOP mask |
| 16 | U2 | 0.1 | tDop | — | Time DOP mask |
| 18 | U2 | — | pAcc | m | Position accuracy mask |
| 20 | U2 | — | tAcc | m | Time accuracy mask |
| 22 | U1 | — | staticHoldThresh | cm/s | Static hold threshold |
| 23 | U1 | — | dgpsTimeOut | s | DGPS timeout |
| 24 | U4 | — | reserved2 | — | Reserved |
| 28 | U4 | — | reserved3 | — | Reserved |
| 32 | U4 | — | reserved4 | — | Reserved |

**CFG-CFG payload (12 bytes, or 13 with the optional device mask):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | X4 | clearMask | Sections to clear to firmware defaults |
| 4 | X4 | saveMask | Sections to save to non-volatile storage |
| 8 | X4 | loadMask | Sections to load from non-volatile storage |
| 12 | X1 | deviceMask | Target storage devices (optional 13th byte) |

**CFG-RST payload (4 bytes):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | X2 | navBbrMask | BBR sections to clear |
| 2 | U1 | resetMode | 0x00=hardware reset, 0x01=controlled software reset, 0x02=controlled software reset (GNSS only), 0x04=hardware reset after shutdown, 0x08=controlled GNSS stop, 0x09=controlled GNSS start |
| 3 | U1 | reserved1 | Reserved |

**CFG-RXM payload (2 bytes):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | U1 | reserved1 | Reserved |
| 1 | U1 | lpMode | 0=max performance, 1=power save |

**ACK-ACK / ACK-NAK payload (2 bytes):**

| Offset | Type | Name | Description |
|--------|------|------|-------------|
| 0 | U1 | clsID | Class ID of the acknowledged/rejected message |
| 1 | U1 | msgID | Message ID of the acknowledged/rejected message |

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

The NEO-6 does not expose a traditional register-mapped bus; the sigrok decoder operates on the byte stream rather than individual registers, and the same UART/DDC/SPI byte stream can interleave both protocols the module supports (NMEA ASCII and UBX binary), so the decoder recognises both framings on one input.

For UART captures, use the sigrok `uart` decoder with 9600 8N1, then a custom `neo6_nmea` upper-level decoder that:

- Matches NMEA sentences by talker ID `GP` (framed `$`...`*XX<CR><LF>`) and annotates **every** comma-separated field of **every** sentence type the module emits at its default configuration — GGA, RMC, GSA, GSV, VTG, GLL, TXT — with its name and parsed value: decimal-degree coordinates, m/s speed, ISO 8601 time (`HH:MM:SSZ`) and date (`YYYY-MM-DD`), per-satellite PRN/elevation/azimuth/SNR for GSV, PRN list and PDOP/HDOP/VDOP for GSA, and decoded mode/severity enums where the sentence carries one. Every field is emitted on every occurrence of its sentence, even with no fix — missing values render as `n/a` rather than being omitted. Any NMEA sentence type outside this default set still gets a sentence-level annotation without field breakdown.
- Matches the proprietary `PUBX` address field (same `$`...`*XX<CR><LF>` framing/checksum) and annotates every field of all five PUBX message types — 00 POSITION, 03 SVSTATUS, 04 TIME, 40 RATE, 41 CONFIG — per the payload tables above, with the same `n/a`/ISO 8601/SI conventions used for standard NMEA. Any other PUBX message ID still gets a sentence-level annotation without field breakdown.
- Matches UBX binary frames (framed `0xB5 0x62 CLASS ID LENGTH(2B LE) PAYLOAD CK_A CK_B`), validates the 8-bit Fletcher checksum, and annotates every payload field of the driver's key messages — NAV-POSLLH, NAV-STATUS, NAV-SOL, NAV-SVINFO, CFG-PRT, CFG-MSG, CFG-RATE, CFG-NAV5, CFG-CFG, CFG-RST, CFG-RXM, ACK-ACK, ACK-NAK — per the payload tables above, with the same unit conversions (mm→m, cm→m or cm/s, 1e-7°→°) and enum decoding used elsewhere in this decoder. Any other class/ID still gets a sentence-level annotation (class, ID, payload length) with the raw payload shown as hex, but no field breakdown.
- Emits a warning annotation for any NMEA or PUBX sentence that fails checksum validation or has a malformed address/sentence-ID field, or any UBX frame that fails its checksum, instead of raising.

For DDC (I²C) captures, use the sigrok `i2c` decoder with address filter `0x42`, then the same `neo6_nmea` upper-level decoder on the read data bytes, skipping `0xFF` filler.

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
- [x] Driver `rust/periph/src/chips/gnss/neo6.rs` — `//!` module doc + `///` on every `pub` item
- [x] Examples `rust/examples/neo6_minimal/src/main.rs` — Tier-1 (UART; comment shows DDC/SPI swap)
- [x] Examples `rust/examples/neo6_complete/src/main.rs` — Tier-1 + Tier-2
- [x] Examples `rust/examples/neo6_demo/src/main.rs` — Tier-1 + Tier-3
- [x] Tests `rust/tests/gnss/neo6_test_uart/src/main.rs` (Linux — UART)
- [x] Tests `rust/tests/gnss/neo6_test_i2c/src/main.rs` (Linux — I²C)
- [x] Tests `rust/tests/gnss/neo6_test_spi/src/main.rs` (Linux — SPI)
- [x] Tests `rust/tests/gnss/neo6_test_esp32s3/src/main.rs` (ESP32-S3 — UART; written against the ina226 esp32s3 template but not compiled — no esp toolchain in this sandbox, crate is workspace-excluded)

### JVM
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gnss/Neo6Minimal.java` + `Neo6Full.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gnss/Neo6Minimal.kt` + `Neo6Full.kt`
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gnss/Neo6Minimal.groovy` + `Neo6Full.groovy`
- [x] Examples `jvm/examples/java/gnss/neo6/Minimal.java` — Tier-1
- [x] Examples `jvm/examples/java/gnss/neo6/Complete.java` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/java/gnss/neo6/Demo.java` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/kotlin/gnss/neo6/Minimal.kt` — Tier-1
- [x] Examples `jvm/examples/kotlin/gnss/neo6/Complete.kt` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/kotlin/gnss/neo6/Demo.kt` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/groovy/gnss/neo6/Minimal.groovy` — Tier-1
- [x] Examples `jvm/examples/groovy/gnss/neo6/Complete.groovy` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/groovy/gnss/neo6/Demo.groovy` — Tier-1 + Tier-3
- [x] Tests `jvm/tests/gnss/neo6/Neo6Test.java`

### Sigrok
- [x] Decoder `sigrok/neo6/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [x] Decoder `sigrok/neo6/pd.py` — annotates every field of every default-configuration NMEA sentence type (GGA, RMC, GSA, GSV, VTG, GLL, TXT) with long/short forms, ISO 8601 time/date, SI units, and `n/a` for missing/no-fix values; also recognises the proprietary PUBX sentences (00 POSITION, 03 SVSTATUS, 04 TIME, 40 RATE, 41 CONFIG) with the same field-level detail; also recognises UBX binary frames, validates the Fletcher checksum, and annotates every payload field of the driver's key UBX messages (NAV-POSLLH, NAV-STATUS, NAV-SOL, NAV-SVINFO, CFG-PRT, CFG-MSG, CFG-RATE, CFG-NAV5, CFG-CFG, CFG-RST, CFG-RXM, ACK-ACK, ACK-NAK); produces `OUTPUT_ANN` only
