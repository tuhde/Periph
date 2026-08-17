# Transport Spec: DHTxx Single-Wire

**Protocol:** DHTxx 1-Wire (custom single-wire bidirectional bit-bang)  
**Reference:** DHT11 / DHT22 datasheet, Aosong Electronics

## Overview

The DHTxx connection implements the single-wire bidirectional bit-bang protocol used by DHT11 and DHT22 sensors. A single data pin, externally pulled up to VCC via a 4.7 kΩ resistor, carries both the host start signal and the sensor's 40-bit response. The connection handles all GPIO direction switching, timing, and bit decoding. Chip drivers receive a raw 5-byte frame and are responsible only for checksum validation and data interpretation.

## Interface Contract

All transport implementations must provide these operations:

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `data_pin` (platform-specific) | — | Store pin; configure as input; external pull-up required |
| `read` | — | `bytes` | Execute the full start/response/bit-read sequence; return raw 5-byte frame |
| `close` | — | — | Release any held GPIO resources |

`read` raises a connection-specific error if the sensor does not respond within the expected window (timeout) or if the bit-read phase produces fewer than 40 bits (framing error). It does **not** validate the checksum — that is the chip driver's responsibility.

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|-------------|
| `data_pin` | MicroPython | `machine.Pin` | GPIO pin; connection switches direction internally |
| `data_pin` | CircuitPython | `digitalio.DigitalInOut` | GPIO pin; connection switches direction internally |
| `chip_num` | Linux | `int` | gpiod chip number (e.g. `0` for `/dev/gpiochip0`) |
| `line_num` | Linux | `int` | GPIO line offset on that chip |
| `data_pin` | Arduino | `int` | Pin number; connection calls `pinMode` to switch direction |
| `chip` | Linux GCC | `gpiod_chip *` | Open gpiod chip handle |
| `line_num` | Linux GCC | `int` | gpiod line offset |
| `spec` | Zephyr | `gpio_dt_spec` | GPIO devicetree spec; connection calls `gpio_pin_configure_dt` |
| `data_pin` | Node.js | `number` | GPIO pin number; connection switches direction internally via `onoff` |
| `P` | Rust | platform-specific | See Rust platform notes |
| `chipPath` | JVM | `String` | gpiochip device path (e.g. `/dev/gpiochip0`) |
| `lineOffset` | JVM | `int` | GPIO line offset on that chip |
| `line_num_out` | Linux (optional) | `int` | Second gpiod line offset, open-drain output; enables the two-pin variant (see below) |
| `line_num_out` | Linux GCC (optional) | `int` | Second gpiod line offset, open-drain output; enables the two-pin variant (see below) |
| `P2` | Rust (Linux, optional) | `CdevPin` | Second open-drain output pin; enables the two-pin variant (see below) |
| `lineOffsetOut` | JVM (optional) | `int` | Second gpiod line offset, open-drain output; enables the two-pin variant (see below) |

## Two-Pin Variant (Linux Only, Optional)

On Linux, direction switching goes through the kernel's gpiod request lifecycle — releasing and re-requesting the line via ioctl — which is expensive enough to threaten the 10–20 µs `T_go` window and contributes to the read flakiness noted under each Linux platform's notes below. Embedded targets (MicroPython, CircuitPython, Arduino, Zephyr, ESP32-S3) reconfigure a pin's direction with a direct register write and have no equivalent cost, so this variant is Linux-only and does not apply to them.

The four Linux-based implementations (Python, Linux GCC, Rust, JVM) may optionally accept a second GPIO line wired to the same physical DATA net instead of switching a single line's direction:

- One line requested once, as **input**, for the connection's lifetime.
- One line requested once, as **output** with the open-drain drive flag (`GPIOD_LINE_DRIVE_OPEN_DRAIN`), for the connection's lifetime. Driving it LOW pulls the bus low; driving it HIGH releases it — open-drain never actively drives HIGH, so it cannot contend with the sensor or the input line sharing the net.

"Releasing the bus" then becomes a single `gpiod_line_set_value` call instead of a release-and-re-request, removing GPIO reconfiguration from the timing-critical part of the transaction entirely. Support is optional: an implementation may offer only the single-pin form, only the two-pin form, or both, selected by whether the second line parameter is supplied at construction.

## Protocol Sequence

The connection executes the following sequence on each `read` call.

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

After all 40 bits, the sensor pulls DATA LOW for 54 µs then releases. The connection may skip waiting for this — the full frame has already been received.

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
| Timeout error | Sensor does not pull DATA LOW within the expected window after the start signal |
| Framing error | Fewer than 40 bit pulses received before the bus returns idle |

Checksum errors are **not** raised by the connection — the raw 5-byte frame is returned as-is.

## Platform Notes

### MicroPython

Uses `machine.Pin`. Direction switching: `pin.init(Pin.OUT)` / `pin.init(Pin.IN)`. Timing: `utime.ticks_us()` with busy-wait loops. On RP2040 and ESP32 targets, timings are typically accurate enough for reliable reads without retries.

File: `python/periph/connection/dhtxx_micropython.py`

### CircuitPython

Uses `digitalio.DigitalInOut`. Direction switching: `pin.direction = Direction.OUTPUT` / `Direction.INPUT`. Timing: `time.monotonic_ns()` with busy-wait loops. Where available, `microcontroller.delay_us()` provides more accurate short delays than `time.sleep()`.

File: `python/periph/connection/dhtxx_circuitpython.py`

### Linux kernel

Uses the `gpiod` Python library (`python-gpiod`). Direction switching requires releasing and re-requesting the line handle with a different flag, so the constructor accepts a chip number and line offset rather than a pre-opened handle. The connection opens and closes line handles per-phase internally. Timing: `time.perf_counter_ns()` with busy-wait loops.

µs-level timing on a non-RTOS kernel is inherently imprecise under load. Read failures are expected on a busy system; callers should use the chip driver's retry mechanism rather than relying on single-shot reads.

Optionally accepts `line_num_out` for the two-pin open-drain variant (see above), which avoids the release/re-request entirely.

File: `python/periph/connection/dhtxx_linux.py`

### Arduino

Uses `pinMode` / `digitalRead` / `digitalWrite`. Direction switching: `pinMode(pin, OUTPUT)` / `pinMode(pin, INPUT)`. Timing: `delayMicroseconds()` for the start pulse; `micros()` with a busy-wait for pulse-width measurement.

Files: `cpp/src/connection/DHTxxConnection.h`, `cpp/src/connection/DHTxxConnection.cpp`

### Linux GCC

Uses libgpiod C API (`gpiod_chip_open_by_number`, `gpiod_chip_get_line`). Direction switching requires releasing and re-requesting the line: `gpiod_line_release` then `gpiod_line_request_output` / `gpiod_line_request_input`. Timing: `clock_gettime(CLOCK_MONOTONIC)` with busy-wait loops. Same non-RTOS reliability caveats as the Linux Python connection apply.

Optionally accepts a second `line_num_out`, requested once with `gpiod_line_request_output_flags(..., GPIOD_LINE_REQUEST_FLAG_OPEN_DRAIN)`, for the two-pin variant (see above) — avoids the release/re-request entirely.

Files: `cpp/src/connection/DHTxxConnectionLinux.h`, `cpp/src/connection/DHTxxConnectionLinux.cpp`

### Zephyr RTOS

Uses `zephyr/drivers/gpio.h`. Direction switching: `gpio_pin_configure_dt(&spec, GPIO_OUTPUT_ACTIVE)` / `GPIO_INPUT`. Timing: `k_busy_wait(us)` for the start pulse; `k_cycle_get_32()` for pulse-width measurement, converted to µs via `k_cyc_to_us_near32`.

`prj.conf` must enable `CONFIG_GPIO=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`.

File: `cpp/src/connection/DHTxxConnectionZephyr.h`

### Node.js

Uses the `onoff` package (legacy sysfs GPIO, `/sys/class/gpio`). Direction switching: destroy and recreate the `Gpio` instance with the new direction, or construct with `{ reconfigureDirection: true }` and call `unexport()`/re-instantiate — `onoff` does not expose an in-place direction change on an already-open instance. Timing: `process.hrtime.bigint()` with busy-wait loops; V8's non-deterministic GC pauses make this the least timing-reliable of the Linux targets. Same non-RTOS reliability caveats as the other Linux connections apply, more acutely.

The open-drain two-pin variant (see above) is **not available** via `onoff` — it exposes no open-drain / drive-mode option, only `activeLow` and `reconfigureDirection`. A second output pin without open-drain would actively drive HIGH and could contend with the sensor and the pull-up, so it is not a safe substitute. Supporting the variant here would require replacing `onoff` with a library that exposes gpiod v2 line-request flags (as the other four Linux implementations use directly). Separately, `onoff`'s sysfs interface is deprecated and already absent on some current kernels (e.g. recent Raspberry Pi OS), which is a pre-existing risk to Node's GPIO connections generally, independent of DHTxx.

File: `nodejs/packages/periph/src/connection/dhtxx.js`

### Rust

`embedded-hal` 1.0 defines no `IoPin` (bidirectional) trait, so this connection cannot be generic over a standard trait. Two platform-specific structs are provided instead:

- **Linux (`DHTxxConnectionLinux`):** Accepts `linux-embedded-hal`'s `CdevPin`. Direction switching is done by re-requesting the line with the appropriate direction via the `CdevPin` API. Dependency: `linux-embedded-hal`. Optionally accepts a second `CdevPin` requested as open-drain output (`Flags::OPEN_DRAIN`) for the two-pin variant (see above), avoiding the release/re-request entirely.
- **ESP32-S3 (`DHTxxConnectionEsp32s3`):** Accepts `esp-hal`'s `AnyFlex` GPIO. Direction switching via `.into_input()` / `.into_output_push_pull()`.

Both structs expose the same `read() → Result<[u8; 5], DHTxxError>` method.

File: `rust/periph/src/connection/dhtxx.rs`

### JVM (Linux)

Uses libgpiod v2 via FFM (Java 21+, no native libraries required) — the same approach as `I2CConnection`. libgpiod v2 does not allow changing the direction of an already-requested line, so direction switching releases the line (`gpiod_line_request_release`) and re-requests it with `GPIOD_LINE_DIRECTION_OUTPUT` / `GPIOD_LINE_DIRECTION_INPUT`. Timing: `System.nanoTime()` with busy-wait loops. Same non-RTOS reliability caveats as the Linux Python/C++ connections apply.

A single `DHTxxConnection` class implements the full contract (`read() → byte[5]`) — chip drivers depend only on this class, never on GPIO details, so any DHTxx-family chip (DHT11, DHT22, ...) shares the same connection.

Optionally accepts a second `lineOffsetOut`, requested once as output with `GPIOD_LINE_DRIVE_OPEN_DRAIN`, for the two-pin variant (see above) — avoids the release/re-request entirely.

File: `jvm/periph-connection/src/main/java/it/uhde/periph/connection/DHTxxConnection.java`

### Go — Linux

Requests the data GPIO line on `/dev/gpiochip0` via the GPIO character-device ioctls (`GPIO_GET_LINEHANDLE_IOCTL` / `GPIOHANDLE_SET_LINE_VALUES_IOCTL`) — the same technique the JVM connection uses over FFM, translated to `golang.org/x/sys/unix` plus hand-built structs; no cgo, no `libgpiod` bindings. The connection toggles the line between input and output by switching the `lineoffsets` array handed to the kernel on each request. Same non-RTOS reliability caveats as the JVM/Linux Python/C++ connections apply.

Optionally accepts a second `dataOutLine` (line offset of an open-drain output line wired to the same DATA net), for the two-pin variant — driver reuses the shared GPIO chardev helper at `go/periph/connection/gpio_linux.go`.

File: `go/periph/connection/dhtxx_linux.go`

### Go — TinyGo

Reconfigures a `machine.Pin` between `PinInput` and `PinOutput` for each phase — TinyGo's per-call overhead is small enough that the 10–20 µs `T_go` window is comfortably met, the same way MicroPython and CircuitPython do it.

File: `go/periph/connection/dhtxx_tinygo.go`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] `python/periph/connection/dhtxx_micropython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/connection/dhtxx_circuitpython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/connection/dhtxx_linux.py` — Google-style docstring on class and every public method
- [ ] Tests (MicroPython)
- [ ] Tests (CircuitPython)
- [ ] Tests (Linux)

### C++
- [ ] `cpp/src/connection/DHTxxConnection.h` — Doxygen `/** @brief */` on class and every public method
- [ ] `cpp/src/connection/DHTxxConnection.cpp`
- [ ] `cpp/src/connection/DHTxxConnectionLinux.h` — Doxygen
- [ ] `cpp/src/connection/DHTxxConnectionLinux.cpp`
- [ ] `cpp/src/connection/DHTxxConnectionZephyr.h` — Doxygen (header-only)
- [ ] Tests (Arduino)
- [ ] Tests (Linux GCC)
- [ ] Tests (Zephyr)

### Node.js
- [ ] `nodejs/packages/periph/src/connection/dhtxx.js` — JSDoc on class and every exported method
- [ ] Tests

### Rust
- [ ] `rust/periph/src/connection/dhtxx.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Tests (Linux)
- [ ] Tests (ESP32-S3)

### JVM
- [ ] `jvm/periph-connection/src/main/java/it/uhde/periph/connection/DHTxxConnection.java` — Javadoc on class and every public method
- [ ] Tests (Pi hardware, JBang)

### Go
- [x] `go/periph/connection/dhtxx_linux.go` — Go doc comment on the type and every exported method
- [x] `go/periph/connection/dhtxx_tinygo.go` — Go doc comment on the type and every exported method
- [x] Tests (Linux)
- [x] Tests (TinyGo / Pico W)

### Sigrok
- [ ] Decoder `sigrok/dhtxx/__init__.py` — module docstring describing protocol framing, signal channels, and what is annotated
- [ ] Decoder `sigrok/dhtxx/pd.py` — annotates framing, data bytes, and decoded values; produces `OUTPUT_ANN` only
