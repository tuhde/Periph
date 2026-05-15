# Transport Spec: DHTxx Single-Wire

**Protocol:** DHTxx 1-Wire (custom single-wire bidirectional bit-bang)  
**Reference:** DHT11 / DHT22 datasheet, Aosong Electronics

## Overview

The DHTxx transport implements the single-wire bidirectional bit-bang protocol used by DHT11 and DHT22 sensors. A single data pin, externally pulled up to VCC via a 4.7 kΩ resistor, carries both the host start signal and the sensor's 40-bit response. The transport handles all GPIO direction switching, timing, and bit decoding. Chip drivers receive a raw 5-byte frame and are responsible only for checksum validation and data interpretation.

## Interface Contract

All transport implementations must provide these operations:

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `data_pin` (platform-specific) | — | Store pin; configure as input; external pull-up required |
| `read` | — | `bytes` | Execute the full start/response/bit-read sequence; return raw 5-byte frame |
| `close` | — | — | Release any held GPIO resources |

`read` raises a transport-specific error if the sensor does not respond within the expected window (timeout) or if the bit-read phase produces fewer than 40 bits (framing error). It does **not** validate the checksum — that is the chip driver's responsibility.

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|-------------|
| `data_pin` | MicroPython | `machine.Pin` | GPIO pin; transport switches direction internally |
| `data_pin` | CircuitPython | `digitalio.DigitalInOut` | GPIO pin; transport switches direction internally |
| `chip_num` | Linux | `int` | gpiod chip number (e.g. `0` for `/dev/gpiochip0`) |
| `line_num` | Linux | `int` | GPIO line offset on that chip |
| `data_pin` | Arduino | `int` | Pin number; transport calls `pinMode` to switch direction |
| `chip` | Linux GCC | `gpiod_chip *` | Open gpiod chip handle |
| `line_num` | Linux GCC | `int` | gpiod line offset |
| `spec` | Zephyr | `gpio_dt_spec` | GPIO devicetree spec; transport calls `gpio_pin_configure_dt` |
| `P` | Rust | platform-specific | See Rust platform notes |

## Protocol Sequence

The transport executes the following sequence on each `read` call.

**Step 1 — Host start signal**
1. Configure DATA as output; drive LOW for ≥ 18 ms (max 30 ms)
2. Configure DATA as input; pull-up brings line HIGH
3. Wait 10–20 µs for sensor to respond

**Step 2 — Sensor response**
1. Sensor pulls DATA LOW for ~83 µs
2. Sensor drives DATA HIGH for ~87 µs
3. Data transmission begins immediately after

**Step 3 — Receive 40 bits (MSB first)**

Each bit starts with a 54 µs LOW pulse, followed by a HIGH pulse whose duration encodes the value:

| High pulse duration | Bit value |
|---------------------|-----------|
| 23–27 µs | 0 |
| 68–74 µs | 1 |

Decoding strategy: wait for the LOW pulse to end, then measure only the HIGH pulse. A threshold of ~40 µs reliably distinguishes bit-0 from bit-1. There is no need to measure the LOW pulse duration.

**Step 4 — End**

After all 40 bits, the sensor pulls DATA LOW for 54 µs then releases. The transport may skip waiting for this — the full frame has already been received.

## Timing Constraints

| Symbol | Parameter | Min | Typ | Max | Unit |
|--------|-----------|-----|-----|-----|------|
| T_be | Host start signal LOW | 18 | 20 | 30 | ms |
| T_go | Host releases bus | 10 | 13 | 20 | µs |
| T_rel | Sensor response LOW | 81 | 83 | 85 | µs |
| T_reh | Sensor response HIGH | 85 | 87 | 88 | µs |
| T_LOW | Bit LOW pulse (both 0 and 1) | 52 | 54 | 56 | µs |
| T_H0 | Bit '0' HIGH pulse | 23 | 24 | 27 | µs |
| T_H1 | Bit '1' HIGH pulse | 68 | 71 | 74 | µs |
| T_en | Sensor releases bus (end) | 52 | 54 | 56 | µs |

## Error Handling

| Error | Condition |
|-------|-----------|
| `TransportError` (timeout) | Sensor does not pull DATA LOW within the expected window after the start signal |
| `TransportError` (framing) | Fewer than 40 bit pulses received before the bus returns idle |

Checksum errors are **not** raised by the transport — the raw 5-byte frame is returned as-is.

## Platform Notes

### MicroPython

Uses `machine.Pin`. Direction switching: `pin.init(Pin.OUT)` / `pin.init(Pin.IN)`. Timing: `utime.ticks_us()` with busy-wait loops. On RP2040 and ESP32 targets, timings are typically accurate enough for reliable reads without retries.

File: `python/periph/transport/dhtxx_micropython.py`

### CircuitPython

Uses `digitalio.DigitalInOut`. Direction switching: `pin.direction = Direction.OUTPUT` / `Direction.INPUT`. Timing: `time.monotonic_ns()` with busy-wait loops. Where available, `microcontroller.delay_us()` provides more accurate short delays than `time.sleep()`.

File: `python/periph/transport/dhtxx_circuitpython.py`

### Linux kernel

Uses the `gpiod` Python library (`python-gpiod`). Direction switching requires releasing and re-requesting the line handle with a different flag, so the constructor accepts a chip number and line offset rather than a pre-opened handle. The transport opens and closes line handles per-phase internally. Timing: `time.perf_counter_ns()` with busy-wait loops.

µs-level timing on a non-RTOS kernel is inherently imprecise under load. Read failures are expected on a busy system; callers should use the chip driver's retry mechanism rather than relying on single-shot reads.

File: `python/periph/transport/dhtxx_linux.py`

### Arduino

Uses `pinMode` / `digitalRead` / `digitalWrite`. Direction switching: `pinMode(pin, OUTPUT)` / `pinMode(pin, INPUT)`. Timing: `delayMicroseconds()` for the start pulse; `micros()` with a busy-wait for pulse-width measurement.

Files: `cpp/src/transport/DHTxxTransport.h`, `cpp/src/transport/DHTxxTransport.cpp`

### Linux GCC

Uses libgpiod C API (`gpiod_chip_open_by_number`, `gpiod_chip_get_line`). Direction switching requires releasing and re-requesting the line: `gpiod_line_release` then `gpiod_line_request_output` / `gpiod_line_request_input`. Timing: `clock_gettime(CLOCK_MONOTONIC)` with busy-wait loops. Same non-RTOS reliability caveats as the Linux Python transport apply.

Files: `cpp/src/transport/DHTxxTransportLinux.h`, `cpp/src/transport/DHTxxTransportLinux.cpp`

### Zephyr RTOS

Uses `zephyr/drivers/gpio.h`. Direction switching: `gpio_pin_configure_dt(&spec, GPIO_OUTPUT_ACTIVE)` / `GPIO_INPUT`. Timing: `k_busy_wait(us)` for the start pulse; `k_cycle_get_32()` for pulse-width measurement, converted to µs via `k_cyc_to_us_near32`.

`prj.conf` must enable `CONFIG_GPIO=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`.

File: `cpp/src/transport/DHTxxTransportZephyr.h`

### Rust

`embedded-hal` 1.0 defines no `IoPin` (bidirectional) trait, so this transport cannot be generic over a standard trait. Two platform-specific structs are provided instead:

- **Linux (`DHTxxTransportLinux`):** Accepts `linux-embedded-hal`'s `CdevPin`. Direction switching is done by re-requesting the line with the appropriate direction via the `CdevPin` API. Dependency: `linux-embedded-hal`.
- **ESP32-S3 (`DHTxxTransportEsp32s3`):** Accepts `esp-hal`'s `AnyFlex` GPIO. Direction switching via `.into_input()` / `.into_output_push_pull()`.

Both structs expose the same `read() → Result<[u8; 5], TransportError>` method.

File: `rust/periph/src/transport/dhtxx.rs`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] `python/periph/transport/dhtxx_micropython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/dhtxx_circuitpython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/dhtxx_linux.py` — Google-style docstring on class and every public method
- [ ] Tests (MicroPython)
- [ ] Tests (CircuitPython)
- [ ] Tests (Linux)

### C++
- [ ] `cpp/src/transport/DHTxxTransport.h` — Doxygen `/** @brief */` on class and every public method
- [ ] `cpp/src/transport/DHTxxTransport.cpp`
- [ ] `cpp/src/transport/DHTxxTransportLinux.h` — Doxygen
- [ ] `cpp/src/transport/DHTxxTransportLinux.cpp`
- [ ] `cpp/src/transport/DHTxxTransportZephyr.h` — Doxygen (header-only)
- [ ] Tests (Arduino)
- [ ] Tests (Linux GCC)
- [ ] Tests (Zephyr)

### Node.js
- [ ] `nodejs/packages/periph/src/transport/dhtxx.js` — JSDoc on class and every exported method
- [ ] Tests

### Rust
- [ ] `rust/periph/src/transport/dhtxx.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Tests (Linux)
- [ ] Tests (ESP32-S3)
