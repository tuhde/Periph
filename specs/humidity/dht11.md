# Chip Spec: DHT11

**Manufacturer:** ASAIR (Aosong Electronics)  
**Datasheet:** `datasheets/humidity/dht11.pdf`  
**Category:** humidity  
**Transports:** DHTxx single-wire (see `specs/transport_dhtxx.md`)

## Overview

The DHT11 is a low-cost combined temperature and humidity sensor with a factory-calibrated digital output. An internal 8-bit microcontroller handles signal conditioning and protocol, delivering 40-bit readings over a single bidirectional data line. Temperature accuracy is ±2°C over –20 to 60°C; humidity accuracy is ±5%RH over 5 to 95%RH. The maximum sampling rate is one reading every 2 seconds. Each read returns the result of the sensor's most recent completed measurement, not a fresh instantaneous conversion.

## Transport Configuration

### DHTxx Single-Wire

The DHT11 uses the DHTxx single-wire transport. The driver accepts a transport instance; the transport handles all GPIO direction switching, timing, and bit decoding. See `specs/transport_dhtxx.md` for the full protocol, timing constraints, and per-platform implementation details.

| Platform | Transport class | Transport file |
|----------|----------------|----------------|
| MicroPython | `DHTxxTransport` | `python/periph/transport/dhtxx_micropython.py` |
| CircuitPython | `DHTxxTransport` | `python/periph/transport/dhtxx_circuitpython.py` |
| Linux | `DHTxxTransport` | `python/periph/transport/dhtxx_linux.py` |
| Arduino | `DHTxxTransport` | `cpp/src/transport/DHTxxTransport.h` |
| Linux GCC | `DHTxxTransportLinux` | `cpp/src/transport/DHTxxTransportLinux.h` |
| Zephyr | `DHTxxTransportZephyr` | `cpp/src/transport/DHTxxTransportZephyr.h` |
| Rust (Linux) | `DHTxxTransportLinux` | `rust/periph/src/transport/dhtxx.rs` |
| Rust (ESP32-S3) | `DHTxxTransportEsp32s3` | `rust/periph/src/transport/dhtxx.rs` |

## Protocol

The DHT11 has no register map. The driver calls `transport.read()` to obtain the raw 5-byte frame, then validates the checksum and decodes the values. The full communication sequence (start signal, sensor response, bit timing) is defined in `specs/transport_dhtxx.md`.

### Data Frame (40 bits)

| Bits | Field | Notes |
|------|-------|-------|
| [39:32] | Humidity integer (8-bit) | %RH integer part |
| [31:24] | Humidity decimal (8-bit) | Always 0x00 for DHT11 |
| [23:16] | Temperature integer (8-bit) | °C integer part |
| [15:8] | Temperature decimal (8-bit) | Fractional °C; bit 7 = sign (1 = negative) |
| [7:0] | Checksum (8-bit) | = (byte0 + byte1 + byte2 + byte3) & 0xFF |

## Initialization Sequence

1. Construct a DHTxx transport instance for the data pin (see `specs/transport_dhtxx.md`); ensure a 4.7 kΩ pull-up to VCC is present on the DATA line
2. Pass the transport to the driver constructor
3. Wait ≥ 1 second after power-up before issuing the first read (sensor internal stabilisation)
4. Record timestamp; device is ready for first read

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: read temperature and humidity with a single call. Enforces the 2-second minimum interval by caching and returning the previous result when called too soon.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `transport` | — | Store transport; record power-up timestamp |
| `read` | — | `(float, float)` | Returns `(temperature_C, humidity_RH)`; calls `transport.read()`, validates checksum, decodes frame; raises error on checksum failure |

**Sensible defaults:** Single read attempt; raises exception on checksum mismatch; caller responsible for respecting ≥ 2 s interval.

### Full

Goal: expose complete sensor functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | float | Returns temperature in °C; internally calls `read` |
| `read_humidity` | — | float | Returns humidity in %RH; internally calls `read` |
| `read_retry` | `max_retries: int` (default 3) | `(float, float)` | Retry up to `max_retries` times on checksum error; raises after all retries exhausted |
| `read_raw` | — | `bytes` | Returns raw 5-byte frame without interpretation; raises on checksum error |

**Additional functionality:**
- Separate `read_temperature()` / `read_humidity()` convenience methods
- Automatic retry with configurable attempt count
- Access to raw 5-byte frame for custom post-processing

## Data Conversion

```
# Received bytes: [hum_int, hum_dec, temp_int, temp_dec, checksum]

# Checksum validation
checksum_ok = (hum_int + hum_dec + temp_int + temp_dec) & 0xFF == checksum

# Humidity (DHT11: hum_dec is always 0)
humidity_RH = hum_int + hum_dec / 10.0

# Temperature
sign = -1 if (temp_dec & 0x80) else 1
temp_dec_value = temp_dec & 0x7F
temperature_C = sign * (temp_int + temp_dec_value / 10.0)
```

**Examples from datasheet:**

Example 1: `[0x35, 0x00, 0x18, 0x04, 0x51]`
- Humidity: 0x35 = 53, 0x00 = 0.0 → **53.0 %RH**
- Temperature: 0x18 = 24, 0x04 = 0.4°C, sign bit = 0 → **24.4°C**
- Checksum: (53+0+24+4) & 0xFF = 81 = 0x51 ✓

Example 2 (negative temperature): `[..., temp_int, temp_dec_with_sign_bit]`
- -10.1°C: temp_int = 0x0A = 10, temp_dec = 0x81 (0x01 with bit 7 set) = 0.1
- Result: -(10 + 0.1) = **-10.1°C**

## Node-RED

Node name: `periph-dht11`  
Package: `node-red-contrib-periph-humidity`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ "temperature_c": float, "humidity_rh": float }` | |

Config panel fields:
- **DATA pin** — GPIO pin number for the single-wire data line
- **Retries** — number of retry attempts on checksum error (default 3)

### Demo flow

An inject node fires every 5 seconds and triggers a `periph-dht11` read. The output connects to a debug node (logging the full payload) and two dashboard gauge nodes (node-red-dashboard): one for temperature in °C and one for humidity in %RH.

## Examples

### Demo

Indoor comfort monitor: read temperature and humidity every 5 seconds and print a one-line status showing both values plus a comfort assessment (`"dry"` if RH < 30%, `"comfortable"` if 30–60%, `"humid"` if > 60%). Use `read_retry(max_retries=3)` to handle occasional checksum errors gracefully. When a read fails after all retries, print a warning and continue. This scenario demonstrates reliable real-world polling with error recovery.

## Timing Constraints

| Symbol | Parameter | Min | Typ | Max | Unit |
|--------|-----------|-----|-----|-----|------|
| — | Minimum read interval | 2 | — | — | s |
| — | Power-up stabilisation | 1 | — | — | s |

Full protocol timing (start signal, response pulses, bit widths) is defined in `specs/transport_dhtxx.md`.

## Implementation Notes

- **Read returns previous result:** The sensor runs an internal conversion cycle continuously. Each read request retrieves the result of the most recently completed conversion, not a fresh one. If the last read was a long time ago, call `read()` twice in succession (with a 2-second gap between calls) and use the second result for real-time accuracy.
- **Minimum sampling interval:** Do not initiate a new read sooner than 2 seconds after the previous one. The Minimal `read()` implementation does not enforce this automatically — callers must manage timing. The Full implementation may add a timestamp guard if desired but is not required to.
- **Pull-up resistor:** A 4.7 kΩ pull-up to VCC on the DATA line is required. For cable lengths > 5 m, reduce the pull-up value. At 3.3 V, keep cable runs short to avoid voltage drop causing measurement errors.
- **Humidity decimal byte:** For the DHT11, byte 1 (humidity decimal) is always 0x00. The conversion formula includes it for completeness and forward compatibility with the DHT22, which uses this byte.
- **Power supply noise:** Switching power supplies can induce temperature measurement jumps. Decouple VCC with 100 nF close to the sensor. Use a linear regulator if temperature stability is critical.
- **Linux read reliability:** Frame reads may sporadically fail on a loaded non-RTOS system; the `read_retry()` method mitigates this. See `specs/transport_dhtxx.md` for details.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

The DHTxx transport must be implemented first; its checklist is in `specs/transport_dhtxx.md`.

### Python
- [ ] Driver `python/periph/chips/humidity/dht11.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/humidity/dht11/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/humidity/dht11/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/humidity/dht11/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/humidity/dht11_test.py` (MicroPython)
- [ ] Tests `python/tests/humidity/dht11_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/humidity/dht11_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/humidity/DHT11.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/humidity/DHT11.cpp`
- [ ] Examples `cpp/examples/DHT11_Minimal/DHT11_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/DHT11_Complete/DHT11_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/DHT11_Demo/DHT11_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/DHT11_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/DHT11_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/DHT11_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/humidity/dht11_test/dht11_test.ino` (Arduino)
- [ ] Tests `cpp/tests/humidity/dht11_test_linux/dht11_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/humidity/dht11_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/humidity/dht11.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/humidity/dht11/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/humidity/dht11/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/humidity/dht11/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/humidity/dht11_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-humidity/nodes/dht11/dht11.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-humidity/nodes/dht11/dht11.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-humidity/examples/dht11/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/humidity/dht11.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/dht11_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/dht11_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/dht11_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/humidity/dht11_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/humidity/dht11_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/Dht11Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/Dht11Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/Dht11Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/Dht11Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/Dht11Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/Dht11Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/humidity/dht11/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/humidity/dht11/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/humidity/dht11/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/humidity/dht11/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/humidity/dht11/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/humidity/dht11/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/humidity/dht11/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/humidity/dht11/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/humidity/dht11/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/humidity/dht11/Dht11Test.java` (Pi hardware, JBang)

### Sigrok
- [ ] Decoder `sigrok/dhtxx/__init__.py` — shared DHTxx single-wire transport decoder; see `specs/transport_dhtxx.md`
- [ ] Decoder `sigrok/dhtxx/pd.py` — logic input; framing/data-byte/checksum `OUTPUT_ANN`; stack base for DHT11/DHT22 chip decoders
