# Transport Spec: HX711

**Protocol:** Custom 2-wire GPIO bit-bang (DOUT + PD_SCK)  
**Reference:** Avia Semiconductor HX711 datasheet (`datasheets/adc_dac/hx711.pdf`)

## Overview

The HX711 transport handles the low-level bit-bang protocol used exclusively by the HX711 24-bit ADC. It is **read-only**: the chip never accepts register writes; all configuration is implicit in the number of clock pulses issued per conversion cycle (25, 26, or 27). Two GPIO pins are required: `DOUT` (data output from chip to MCU) and `PD_SCK` (clock output from MCU to chip, doubled as power-down control).

This transport is chip-specific — no other device uses this protocol.

## Protocol

### Pins

| Pin | Direction | Idle state | Function |
|-----|-----------|------------|----------|
| DOUT | Input | HIGH | Serial data out; goes LOW when a conversion is ready |
| PD_SCK | Output | LOW | Serial clock in; held HIGH >60 µs to enter power-down |

### Read Sequence

1. Ensure PD_SCK is LOW before starting
2. Poll DOUT until LOW (conversion ready); timeout after 1 second and return an error if it never goes LOW
3. Send exactly N positive pulses on PD_SCK (N = 25, 26, or 27), sampling DOUT at each falling edge (HIGH→LOW transition); data is valid within 0.1 µs of the falling edge
4. Data is MSB-first, bits 23–0; after bit 0, the 25th pulse drives DOUT back HIGH
5. N programs the channel and gain for the **next** conversion
6. Leave PD_SCK LOW after the last pulse

### Pulse Count → Channel/Gain

| Pulses (N) | Channel | Gain |
|------------|---------|------|
| 25 | A | 128 |
| 26 | B | 32 |
| 27 | A | 64 |

### Timing Constraints

| Symbol | Event | Min | Max | Unit |
|--------|-------|-----|-----|------|
| T1 | DOUT falling edge to first PD_SCK rising edge | 0.1 | — | µs |
| T2 | PD_SCK rising edge to DOUT data valid | — | 0.1 | µs |
| T3 | PD_SCK high time | 0.2 | **50** | µs |
| T4 | PD_SCK low time | 0.2 | — | µs |

**Critical:** T3 must not reach 60 µs or the chip interprets the high pulse as a power-down command. Keep clock high time well under 50 µs.

### Power-Down and Reset

- **Enter power-down:** hold PD_SCK HIGH for >60 µs
- **Exit power-down / reset:** drive PD_SCK LOW; chip resets to Channel A, Gain 128

After any reset or power-up event the first conversion must be discarded: settling time is 400 ms at 10 SPS and 50 ms at 80 SPS.

### Data Format

The 24 bits arrive MSB-first as an unsigned integer. Sign-extend before returning:

```
if raw >= 0x800000: signed = raw - 0x1000000
else:               signed = raw
signed range: -8 388 608 to +8 388 607
```

## Interface Contract

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `dout`, `pd_sck` | — | Configure pins; drive PD_SCK LOW |
| `is_ready` | — | bool | True if DOUT is LOW; non-blocking |
| `read_raw` | `num_pulses: int` (25, 26, or 27) | int | Wait up to 1 s for DOUT LOW (error on timeout); clock out N pulses sampling DOUT at each falling edge; leave PD_SCK LOW; return signed 24-bit value |
| `power_down` | — | — | Hold PD_SCK HIGH for >60 µs |
| `power_up` | — | — | Drive PD_SCK LOW; chip resets to Channel A, Gain 128 |
| `close` | — | — | Release / de-configure pins |

`read_raw` must validate that `num_pulses` is one of {25, 26, 27} and raise/return an error otherwise.

On Linux, `read_raw` must insert a short sleep (≥1 ms) between DOUT polls to avoid busy-waiting a CPU core.

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|-------------|
| `dout` | MicroPython | `machine.Pin(n, machine.Pin.IN)` | Data input pin |
| `pd_sck` | MicroPython | `machine.Pin(n, machine.Pin.OUT)` | Clock / power-down output pin |
| `dout` | CircuitPython | `digitalio.DigitalInOut` (direction: INPUT) | Data input pin |
| `pd_sck` | CircuitPython | `digitalio.DigitalInOut` (direction: OUTPUT) | Clock / power-down output pin |
| `dout` | Linux Python | `gpiod.Line` (direction=INPUT, active_low=False) | Data input line |
| `pd_sck` | Linux Python | `gpiod.Line` (direction=OUTPUT) | Clock / power-down output line |
| `dout` | Arduino | `int` pin number | Data input pin; `pinMode(dout, INPUT)` in `init` |
| `pd_sck` | Arduino | `int` pin number | Clock output pin; `pinMode(pd_sck, OUTPUT)` in `init` |
| `dout` | Linux GCC | `gpiod_line *` (input) | Data input line |
| `pd_sck` | Linux GCC | `gpiod_line *` (output) | Clock / power-down output line |
| `dout` | Zephyr | `gpio_dt_spec` (GPIO_INPUT) | Data input pin |
| `pd_sck` | Zephyr | `gpio_dt_spec` (GPIO_OUTPUT_LOW) | Clock / power-down output pin |
| `dout` | Node.js | `object` (`onoff` Gpio, direction `'in'`) | Data input GPIO |
| `pd_sck` | Node.js | `object` (`onoff` Gpio, direction `'out'`) | Clock / power-down output GPIO |
| `dout` | Rust | `impl InputPin` | `embedded_hal::digital::InputPin` |
| `pd_sck` | Rust | `impl OutputPin` | `embedded_hal::digital::OutputPin` |
| `dout` | Go Linux | `int` (GPIO line offset) | Data input line, requested on `/dev/gpiochip0` via ioctl |
| `pdSck` | Go Linux | `int` (GPIO line offset) | Clock / power-down output line, requested on `/dev/gpiochip0` via ioctl |
| `dout` | Go TinyGo | `machine.Pin` | Data input pin |
| `pdSck` | Go TinyGo | `machine.Pin` | Clock / power-down output pin |

## Platform Notes

All platforms implement the same bit-bang loop: toggle PD_SCK HIGH → toggle PD_SCK LOW → sample DOUT at the falling edge, repeated N times. The only platform-specific differences are the GPIO API and the polling sleep on Linux.

### MicroPython

Use `pin.value()` to read DOUT and `pin.value(0/1)` to drive PD_SCK. No sleep is needed between polls — MicroPython's GIL keeps the loop cooperative.

File: `python/periph/transport/hx711_micropython.py`

### CircuitPython

Use `pin.value` (property) to read and write. No sleep needed.

File: `python/periph/transport/hx711_circuitpython.py`

### Linux Python

Use `gpiod` (libgpiod Python bindings). Insert `time.sleep(0.001)` between DOUT polls in the `read_raw` wait loop to avoid spinning a CPU core. **Do not insert explicit delays between clock edges**: `time.sleep(0.000001)` on Linux sleeps 50–100 µs due to scheduler granularity, which exceeds the 50 µs T3 maximum and triggers power-down. The gpiod call overhead (~1–5 µs) is sufficient. Call `request.release()` in `close()`.

File: `python/periph/transport/hx711_linux.py`

### Arduino

Use `digitalRead(dout)` and `digitalWrite(pd_sck, HIGH/LOW)`. Add `delayMicroseconds(1)` between edges — MCU instruction cycles are sub-µs, so an explicit 1 µs delay is needed to meet the T3/T4 0.2 µs minimums.

Files: `cpp/src/transport/HX711Transport.h`, `cpp/src/transport/HX711Transport.cpp`

### Linux GCC

Use `gpiod_line_get_value()` and `gpiod_line_set_value()`. Insert a 1 ms `usleep` between DOUT polls. **Do not insert explicit delays between clock edges**: Linux scheduler granularity makes `usleep(1)` sleep 50–100 µs in practice, which exceeds the 50 µs T3 maximum and triggers power-down. The `gpiod` syscall overhead (~1–5 µs) is sufficient. Release lines in destructor / `close()`.

Files: `cpp/src/transport/HX711TransportLinux.h`, `cpp/src/transport/HX711TransportLinux.cpp`

### Zephyr RTOS

Use `gpio_pin_get_dt()` and `gpio_pin_set_dt()`. Configure pins in `init` using `gpio_pin_configure_dt()`.

`prj.conf`: `CONFIG_GPIO=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`.

File: `cpp/src/transport/HX711TransportZephyr.h`

### Node.js

Use the `onoff` package. Poll `dout.readSync()` and drive `pd_sck.writeSync(0/1)`. Insert a 1 ms `setTimeout`/spin between polls.

File: `nodejs/packages/periph/src/transport/hx711.js`

### Rust (embedded-hal)

`read_raw` spins on `dout.is_low()` — no standard `Wait` trait exists in `embedded-hal` 1.0, so a spin loop is the correct approach for both `no_std` bare-metal and Linux targets.

```rust
impl<DI: InputPin, CK: OutputPin> HX711Transport<DI, CK> {
    pub fn read_raw(&mut self, num_pulses: u8) -> Result<i32, TransportError> {
        debug_assert!(matches!(num_pulses, 25 | 26 | 27));
        while self.dout.is_high()? {}   // wait for data ready
        let mut raw: u32 = 0;
        for _ in 0..num_pulses {
            self.pd_sck.set_high()?;
            self.pd_sck.set_low()?;
            raw = (raw << 1) | if self.dout.is_high()? { 1 } else { 0 };  // sample at falling edge
        }
        raw >>= num_pulses - 24;        // discard the extra gain-select pulses
        // PD_SCK is already LOW (last loop iteration ended with set_low)
        Ok(if raw >= 0x800000 { raw as i32 - 0x1000000 } else { raw as i32 })
    }
}
```

File: `rust/periph/src/transport/hx711.rs`

### Rust Linux

Same implementation. Use `linux-embedded-hal`'s `CdevPin` as the `InputPin` / `OutputPin`. **Do not insert explicit delays between clock edges** (same reason as Linux GCC: `std::thread::sleep(1µs)` sleeps 50–100 µs). The `embedded-hal` GPIO call overhead is sufficient.

```toml
linux-embedded-hal = "0.4"
embedded-hal = "1"
```

### Go — Linux

Requests both lines on `/dev/gpiochip0` via the GPIO character-device ioctls (`GPIO_GET_LINEHANDLE_IOCTL` / `GPIOHANDLE_SET_LINE_VALUES_IOCTL`) — the same technique the JVM transport uses over FFM, translated to `golang.org/x/sys/unix` plus hand-built structs; no cgo, no `gpiod` bindings. Insert a 1 ms `time.Sleep` between DOUT polls in the wait loop; do **not** add explicit delays between PD_SCK edges — the ioctl call overhead already exceeds the 40 ns/0.2 µs minimums, and any `time.Sleep` of a sub-millisecond duration runs 50–100 µs in practice on Linux, which would trip the 50 µs T3 power-down threshold (same reasoning as every other Linux HX711 transport in this repo).

File: `go/periph/transport/hx711_linux.go`

### Go — TinyGo

Uses `machine.Pin.Get()` / `machine.Pin.Set()` directly. No sleep is needed between polls or clock edges — TinyGo's per-call overhead exceeds the timing minimums, the same way MicroPython's does.

File: `go/periph/transport/hx711_tinygo.go`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] `python/periph/transport/hx711_micropython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/hx711_circuitpython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/hx711_linux.py` — Google-style docstring on class and every public method
- [x] Tests (MicroPython)
- [x] Tests (CircuitPython)
- [x] Tests (Linux)

### C++
- [x] `cpp/src/transport/HX711Transport.h` — Doxygen `/** @brief */` on class and every public method
- [x] `cpp/src/transport/HX711Transport.cpp`
- [x] `cpp/src/transport/HX711TransportLinux.h` — Doxygen
- [x] `cpp/src/transport/HX711TransportLinux.cpp`
- [x] `cpp/src/transport/HX711TransportZephyr.h` — Doxygen (header-only)
- [x] Tests (Arduino)
- [x] Tests (Linux GCC)
- [x] Tests (Zephyr)

### Node.js
- [x] `nodejs/packages/periph/src/transport/hx711.js` — JSDoc on class and every exported method
- [x] Tests

### Rust
- [x] `rust/periph/src/transport/hx711.rs` — `//!` module doc + `///` on every `pub` item
- [x] Tests (Linux)
- [x] Tests (ESP32-S3)

### Go
- [ ] `go/periph/transport/hx711_linux.go` — Go doc comment on the type and every exported method
- [ ] `go/periph/transport/hx711_tinygo.go` — Go doc comment on the type and every exported method
- [ ] Tests (Linux)
- [ ] Tests (TinyGo / Pico W)
