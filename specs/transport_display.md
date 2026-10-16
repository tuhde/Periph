# Transport Spec: Display

**Protocol:** SPI (write-only, or half-duplex on a shared DIN/DOUT line) plus D/C and optional RES and BUSY control lines
**Reference:** UC8276C datasheet (`datasheets/display/UC8276.pdf`) and 4.2" e-Paper (B) module (`datasheets/display/uc8276c_4.2inch-e-paper-b.pdf`) for the e-paper side, SH1106 datasheet (`datasheets/display/sh1106.pdf`) for the OLED side; the scheme is shared by the OLED (SH1106, SSD1306 family) and e-paper (SSD1683, UC8276C) controller families

## Overview

The Display connection drives display controllers that are programmed as a stream of **commands** (D/C low) followed by **data** bytes (D/C high) over SPI, with an optional hardware reset line (RES) and, on e-paper controllers, a BUSY output that signals when the controller is refreshing. It bundles the SPI bus and the side-band lines so chip drivers never touch D/C, RES, BUSY or CS themselves. RES and BUSY are optional: an OLED has no BUSY line and may tie RES high, so `res` / `busy` are `None` there and `reset()` / `wait_busy()` degrade to a no-op / immediate success.

**Scope:** SPI only. An I²C OLED carries the D/C bit inside a control byte of every transaction and uses `I2CConnection`; the chip driver builds the control bytes.

Breakout modules wire the bus in one of three ways, so the caller picks a **wire mode** at construction:

| Wire mode | Wiring | Reads |
|-----------|--------|-------|
| `SIMPLEX` | MOSI -> DIN | Not available |
| `HALF_DUPLEX_HW` | MOSI/SDA pin <-> DIN/DOUT, platform's hardware 3-wire / 1-line SPI | Yes, where the platform supports it |
| `HALF_DUPLEX_PIN` | MOSI -- resistor -- MISO, joined at DIN/DOUT, read by a full-duplex SPI master (hardware or software) | Yes, on any platform |

Chip-level protocol (command set, frame buffer layout, refresh sequence) belongs to the chip spec; this transport only moves bytes and manages the control lines.

**Out of scope:** the controllers' 9-bit-frame 3-wire protocol (BS1 high / SPI 3-wire mode, D/C carried as the first bit of each frame). Modules strap the bus-select pins for 4-wire; the connection is 4-wire (separate D/C line) in all three wire modes.

## Protocol

### Pins

| Pin | Direction | Mandatory | Function |
|-----|-----------|-----------|----------|
| SCK | MCU -> chip | Yes | SPI clock, mode 0 (CPOL=0, CPHA=0), MSB first |
| DIN (MOSI) | MCU -> chip | Yes | Serial data in; in half-duplex modes also carries read data (DOUT) |
| MISO | chip -> MCU | `HALF_DUPLEX_PIN` only | Tied to MOSI through a resistor; read back in software SPI mode |
| CS | MCU -> chip | Yes | Chip select, driven by the underlying hardware SPI / software SPI chip-select |
| D/C | MCU -> chip | Yes | LOW = command byte, HIGH = data byte |
| RES | MCU -> chip | No | Active-low hardware reset; may be tied high, then `res = None` |
| BUSY | chip -> MCU | No (e-paper: yes) | Busy indication; absent on OLEDs (`busy = None`). The polarity differs between controllers (UC8276C: BUSY_N, active low; SSD1683: active high), so it is **chip-level knowledge**: the chip driver passes it to `wait_busy`; the connection only reads the raw level |

### Chip select

Hardware CS only. Each `send_command` / `send_data` / `read_data` is a separate SPI transfer; CS is not held across a command and its parameters. This matches the UC8276C datasheet (CS# may be released between a command and its parameters). A chip spec that requires CS to stay low across a command and its parameters must say so explicitly, and the interface will then need a combined-transfer operation.

### Write sequence

1. Drive D/C low, SPI-transfer the command byte
2. Drive D/C high, SPI-transfer the parameter / data bytes (if any)

D/C must be stable before the first SCK edge of the byte it qualifies and must not change until the transfer has completed.

### Read sequence (half-duplex modes)

1. Drive D/C low, SPI-transfer the command byte (as above)
2. Drive D/C high, release the data line (hardware: switch the bus to receive; `HALF_DUPLEX_PIN`: switch MOSI to input, sample MISO) and clock in `n` bytes
3. Restore the data line to output

The read clock is limited by the chip (confirm the figure in the chip spec; read mode is typically slower than write mode).

### Reset

1. RES HIGH (idle)
2. Drive RES LOW for `reset_pulse_us`
3. Drive RES HIGH, then wait `reset_settle_ms` before the first command

Both values are constructor parameters. Defaults are placeholders (the chip spec supplies the datasheet minimums and may override them).

### BUSY wait

`wait_busy(busy_active_high, timeout_ms)` returns `True` at once when no BUSY pin is wired; otherwise it reads BUSY with the level-read operation on `InputPin` (`is_high` / `isHigh` / `IsHigh`, see `specs/feature_inputpin_level_read.md`), compares it to the `busy_active_high` the chip driver passes in, polls until not busy or the timeout elapses, and returns whether the chip became ready. `busy_level()` returns the raw level. No interrupt-based waiting.

## Hardware vs. Software SPI

Software SPI is a shared primitive (`SoftSPI` under `connection/`): clock, MOSI, optional MISO, mode 0, MSB first, `write(data)` and `read(n, write=0x00)`. There is **no direction switch**: with the resistor wiring MISO is joined to the shared DIN/DOUT line directly and MOSI through a series resistor, so the host keeps driving MOSI (a dummy byte) while the chip's stronger driver wins on the shared line and MISO samples it. This also means `HALF_DUPLEX_PIN` works with any full-duplex SPI master, hardware or software; `SoftSPI` is only needed where the platform has no software-SPI object. `SIMPLEX` writes with any SPI; `HALF_DUPLEX_HW` needs the platform's hardware 3-wire mode (the STM32Cube probe's `bb_gpio` GPIO direction-switch approach is a probe-only alternative and not part of this interface).

```
for byte in data:
    for bit in 7..0:             # MSB first
        mosi.write((byte >> bit) & 1)
        sck.write(1)
        sck.write(0)

read byte:                       # half-duplex, mode 0
    for bit in 7..0:
        sck.write(1)
        v = (v << 1) | miso.read()
        sck.write(0)
```

The software bit-bang loop is slow (GPIO call overhead) but display refresh is dominated by the panel, not the bus.

### Hardware half-duplex support

| Platform | Hardware half-duplex mechanism | `HALF_DUPLEX_HW` |
|----------|--------------------------------|------------------|
| MicroPython | none (`machine.SPI` is full-duplex 4-wire) | not supported |
| CircuitPython | none | not supported |
| Linux Python | spidev `SPI_3WIRE` mode flag | supported on controllers that implement it |
| Arduino | none in the generic API | not supported |
| Linux GCC | spidev `SPI_3WIRE` mode flag | supported on controllers that implement it |
| Zephyr RTOS | `SPI_HALF_DUPLEX` in `spi_config.operation` | supported on controllers that implement it |
| ESP-IDF | `SPI_DEVICE_3WIRE` device flag | supported |
| Pico SDK | none (PL022 is 4-wire) | not supported |
| STM32Cube | `SPI_DIRECTION_1LINE` | supported |
| Node.js | spi-device has no 3-wire option | not supported |
| Rust (embedded-hal) | caller supplies a 3-wire-capable `SpiBus` | depends on the HAL |
| JVM | spidev ioctl `SPI_IOC_WR_MODE` with `SPI_3WIRE` (not wired through in the current layer) | not supported initially |
| Go Linux | same as JVM | not supported initially |
| Go TinyGo | none in `machine.SPI` | not supported |

On a platform where `HALF_DUPLEX_HW` is not supported, selecting it must fail at construction with a clear error; the caller uses `HALF_DUPLEX_PIN` instead.

## Interface Contract

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | SPI object or pin set, `dc`, `res`, `busy`, `wire_mode`, `reset_pulse_us`, `reset_settle_ms` | — | Configure SPI (mode 0, MSB first) or SoftSPI per wire mode; D/C low-idle, RES high-idle; BUSY as input |
| `reset` | — | — | Hardware reset sequence as above |
| `delay_ms` | `ms` | - | Block for `ms` milliseconds; for controllers without a BUSY pin (SSD1331 power-up waits). C++ `delay(ms)`, Node.js `delayMs(ms)`, Rust `delay_ms(ms)`, Go `Delay(d)`, JVM `delay(ms)` |
| `delay_us` | `us` | - | Block for at least `us` microseconds; for controllers that need a gap between bytes (ST7036 30 µs). Python `delay_us(us)`, C++ `delayMicros(us)`, Node.js `delayUs(us)`, Rust `delay_us(us)`, Go `DelayMicros(us)`, JVM `delayUs(us)`; where the host cannot sleep that precisely the wait is longer |
| `wait_busy` | `busy_active_high: bool`, `timeout_ms` | `bool` | True when BUSY went inactive within the timeout; the polarity comes from the chip driver |
| `busy_level` | — | `bool` | Raw BUSY pin level |
| `send_command` | `cmd: byte`, `data: bytes = []` | — | D/C low for `cmd`, D/C high for `data` |
| `send_data` | `data: bytes` | — | D/C high |
| `read_data` | `length` | `bytes` | `HALF_DUPLEX_*` only; error in `SIMPLEX` |
| `close` | — | — | Release SPI and GPIO lines |

`write`, `read` and `write_read` from the base `Connection` interface are not part of this connection; chip drivers use the operations above. Chip drivers accept an `DisplayConnection`, not a plain `Connection`.

## Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `wire_mode` | enum | `SIMPLEX` | `SIMPLEX`, `HALF_DUPLEX_HW`, `HALF_DUPLEX_PIN` |
| `reset_pulse_us` | int | chip spec | RES low pulse width |
| `reset_settle_ms` | int | chip spec | Wait after RES released |
| `clock_hz` | int | 4 000 000 | Write clock; read clock is limited separately in half-duplex modes |

Platform-specific object and pin types follow the same convention as `transport_sipo.md` (per-platform tables are filled in when the transport issue is implemented).

## Error Handling

- `read_data` in `SIMPLEX` mode: raise/return an error (`unsupported`).
- `HALF_DUPLEX_HW` on a platform without hardware half-duplex: error at construction.
- `HALF_DUPLEX_PIN` without a MISO pin: error at construction.
- `wait_busy` timeout: return `false` (chip drivers decide whether it is fatal).
- SPI/GPIO failures: platform-native error, never swallowed.

## Platform Notes

Follow `transport_sipo.md` for platform structure (MicroPython / CircuitPython accept the platform's own SPI or SoftSPI object; Linux, Arduino, Pico SDK, ESP-IDF, STM32Cube, Node.js, JVM and Go select hardware vs. software in the constructor; Rust is generic over `SpiBus` + `OutputPin` + `InputPin`).

### C++

The shared logic is `DisplayConnectionBase` (header-only, no heap, no exceptions, no STL); platform classes derive from it and implement `spiWrite()`, `spiRead()`, `delayUs()`, `delayMs()` and `nowMs()`. D/C#, RES# and CS# are `OutputPin*`, BUSY is an `InputPin*` (`isHigh()`); pass `nullptr` for RES#/BUSY when not wired. Chip drivers are templates over the connection class, as for `SiPoConnection`. Methods: `reset()`, `waitBusy(busyActiveHigh, timeoutMs = kWaitForever)`, `busyLevel()`, `sendCommand(cmd, data = nullptr, len = 0)`, `sendData(data, len)`, `readData(buf, len) -> bool` (false in `kSimplex`), `enable()` / `disable()` / `isEnabled()`, `valid()`.

Errors without exceptions: an unsupported configuration makes `valid()` return `false` and every bus operation a no-op (the Python classes raise `ValueError`). The Linux class throws `std::runtime_error` when spidev cannot be opened, like `SPIConnectionLinux`.

| Platform | Class / constructor | CS# | `kHalfDuplexHW` | `kHalfDuplexPin` |
|----------|--------------------|-----|-----------------|------------------|
| Arduino | `DisplayConnection(SPIClass&, cs, dc, res, busy, SPISettings, wireMode, ...)` or `(SoftSPI&, cs, dc, res, busy, wireMode, ...)` | `OutputPin*` | invalid | `transfer(0x00)` per byte |
| Linux GCC | `DisplayConnectionLinux(bus, device, dc, res, busy, wireMode, max_speed_hz, ...)` or `(SoftSPI&, cs, dc, res, busy, ...)` | kernel (hardware) / `OutputPin*` (soft) | spidev `SPI_3WIRE` (controller must support it) | rx-only `SPI_IOC_MESSAGE` |
| Zephyr | `DisplayConnectionZephyr(dev, spi_config, cs, dc, res, busy, wireMode, ...)` or `(SoftSPI&, ...)` | `config.cs` (pass `nullptr`) or `OutputPin*` | needs `SPI_HALF_DUPLEX` in `config.operation` | needs a config **without** `SPI_HALF_DUPLEX`; `spi_read()` |
| ESP-IDF | `DisplayConnectionESPIDF(spi_device_handle_t, cs, dc, res, busy, wireMode, maxTransferBytes = 4092, ...)` or `(SoftSPI&, ...)` | driver (`spics_io_num`, pass `nullptr`) or `OutputPin*` | caller adds the device with `SPI_DEVICE_3WIRE` (not inspectable) | full-duplex rx |
| Pico SDK | `DisplayConnectionPicoSDK(spi_inst_t*, cs, dc, res, busy, wireMode, ...)` or `(SoftSPI&, ...)` | `OutputPin*` | invalid (PL022 has no 3-wire mode) | `spi_read_blocking()` |
| STM32Cube | `DisplayConnectionSTM32Cube(SPI_HandleTypeDef*, cs, dc, res, busy, wireMode, ...)` or `(SoftSPI&, ...)` | `OutputPin*` | requires `Init.Direction = SPI_DIRECTION_1LINE` | requires `SPI_DIRECTION_2LINES`; `HAL_SPI_TransmitReceive` |

`HAL_Delay`-based platforms round microsecond delays up to whole milliseconds (STM32Cube); the ESP-IDF class splits transfers at `maxTransferBytes`, so with driver-owned CS each chunk is its own CS# window. The ESP-IDF 3-wire read path and the Linux `SPI_3WIRE` path are compile-verified only.

### Python

D/C#, RES# and CS# are `OutputPin`s and BUSY is an `InputPin` (read with `is_high()`, see `specs/feature_inputpin_level_read.md`). The platform-independent logic (framing, reset, BUSY wait, gating) lives in `periph.connection.display_base.DisplayConnectionBase`; the platform classes only implement one CS-framed SPI write and one CS-framed SPI read. `WireMode` is in `display_base`.

| Platform | Constructor | Notes |
|----------|-------------|-------|
| MicroPython | `DisplayConnection(spi, cs, dc, res, busy, wire_mode=SIMPLEX, reset_pulse_us=200, reset_settle_ms=10, en_pin=None, en_active_high=True)` | `spi`: `machine.SPI` / `machine.SoftSPI`. `HALF_DUPLEX_PIN` needs an SPI with `read()` and MISO wired; `HALF_DUPLEX_HW` raises `ValueError` |
| CircuitPython | `DisplayConnection(spi, cs, dc, res, busy, baudrate=4000000, wire_mode=SIMPLEX, ...)` | `spi`: `busio.SPI` / `bitbangio.SPI`, locked and configured (mode 0) around every transfer. `HALF_DUPLEX_HW` raises `ValueError` |
| Linux | `DisplayConnection(dc, res, busy, wire_mode=SIMPLEX, bus_num=None, device_num=None, max_speed_hz=4000000, spi=None, cs=None, ...)` | exactly one of hardware (`bus_num`, `device_num`: spidev; `HALF_DUPLEX_HW` sets `threewire`, reads with `readbytes`; `HALF_DUPLEX_PIN` reads with `xfer2`) or software (`spi`: `SoftSPI`, plus `cs`) |

Errors: `ValueError` for an unknown or unsupported wire mode, an SPI object that cannot read when `HALF_DUPLEX_PIN` is requested, or a Linux constructor with both/neither of hardware and software SPI; `RuntimeError` from `read_data()` in `SIMPLEX`. `DisplayConnectionMock` (`display_mock.py`) implements the public interface and records every call as an ordered `events` list for chip-driver unit tests.

### Node.js

`DisplayConnection` (`display.js`) takes an options object. D/C#, RES# and CS# are `OutputPin`s (`async set(high)`), BUSY is an `InputPin` read synchronously with `isHigh()`. Exactly one SPI source: hardware (`busNumber` + `deviceNumber` open `spi-device`, mode 0, `maxSpeedHz` default 4 MHz; `device` accepts an already-opened spi-device object) or software (`spi`: a `SoftSPI` from `softspi.js`, plus `cs`). Methods: async `reset()`, `waitBusy(busyActiveHigh, timeoutMs = null)`, `sendCommand(cmd, data = [])`, `sendData(data)`, `readData(length)`, `enable()` / `disable()`; sync `busyLevel()`, `isEnabled()`, `close()`. `ready()` resolves once the constructor has driven the idle levels. `WireMode` = `{SIMPLEX, HALF_DUPLEX_HW, HALF_DUPLEX_PIN}`; `HALF_DUPLEX_HW` throws (spi-device has no 3-wire option), `HALF_DUPLEX_PIN` needs the software SPI with a MISO `InputPin`, or a hardware device that reports MISO. `display_mock.js` records calls in `events`.

### Rust

`DisplayConnection<SPI, DC, RES, BUSY, DELAY>` (`rust/periph/src/connection/display.rs`) is generic over `embedded_hal::spi::SpiDevice` (CS-framed transfers, one window per call), `OutputPin` (D/C#, RES#), `InputPin` (BUSY) and `DelayNs`. Use `NoPin` for an unwired RES#/BUSY. `WireMode::HalfDuplexHw` is whatever the caller's `SpiDevice` provides (a 3-wire-capable HAL); `HalfDuplexPin` reads with one `SpiDevice::read` per byte. Errors are `DisplayError<SE, DE, RE, BE>` (`Spi`, `Dc`, `Res`, `Busy`, `ReadUnavailable`).

### JVM

`DisplayConn` (interface; chip drivers depend on it) is implemented by `DisplayConnection` and `MockDisplayConnection` in `periph-connection`. Factories: `DisplayConnection.hardware(busNumber, deviceNumber, maxSpeedHz, dc, res, busy, wireMode)` (spidev via `SPIConnection`, mode 0) and `DisplayConnection.software(sck, mosi, miso, cs, dc, res, busy, wireMode)` over `SoftSpi`. D/C#, RES#, CS# are `OutputPin`, BUSY is an `InputPin` read with `isHigh() throws IOException`. `waitBusy(busyActiveHigh, timeoutMs)` treats `timeoutMs <= 0` as no timeout. `WireMode.HALF_DUPLEX_HW` throws `UnsupportedOperationException`; `readData` throws it in `SIMPLEX`. Kotlin and Groovy drivers use the same Java classes.

### Go

`DisplayConn` (interface) is implemented by `*DisplayConnection` and `*DisplayConnectionMock`. `NewDisplayConnectionHardwareSPI` is in `display_linux.go` (`busNum, deviceNum, maxSpeedHz, dc, res, busy, mode`; spidev through `SPIConnection`) and `display_tinygo.go` (`*machine.SPI, cs machine.Pin, dc, res, busy, mode`); `NewDisplayConnectionSoftSPI(sck, mosi, miso, cs, dc, res, busy, mode)` in `display.go` works on both targets over `OutputPin`/`InputPin`. Pass `nil` for an unwired RES#/BUSY. `WaitBusy(busyActiveHigh, timeout time.Duration) (bool, error)` treats `timeout <= 0` as no timeout. `WireModeHalfDuplexHW` returns `ErrHalfDuplexHWUnsupported`; `ReadData` returns `ErrReadUnavailable` in simplex.

## Sigrok Decoder

Decoder id `display_spi`, displayed as **Display-SPI**, directory `sigrok/display_spi/` (sigrok ids are lowercase identifiers, so the hyphen lives only in the display name). It is the connection-level decoder: input `logic` (not stacked on `spi`, because it needs the D/C#, BUSY and RES# lines and libsigrokdecode does not allow extra channels on a decoder stacked on a non-`logic` input — same reason as `sipo`), SPI Mode 0, MSB first, bits sampled on SCK rising edges while CS# is low.

Channels: `clk`, `mosi` (DIN), `cs` (required); `dc` (required in 4-wire mode), `miso` (DOUT, optional — for `HALF_DUPLEX_PIN` wiring probed on the MISO side), `busy`, `res` (optional). Option: `wire` = `4-wire` (default) or `3-wire` (9-bit frames, first bit D/C). The BUSY polarity is not an option here: it is chip-level, each chip decoder knows which level means busy. For half-duplex wiring probe DIN at the shared DIN/DOUT line, or wire `miso` as well.

Annotation rows: **Data** — one annotation per CS# window listing its bytes as `C xx` / `D xx` (with `miso` wired, `D xx ← yy` when DOUT differs from its idle level at CS# assertion) and the fastest SCK rate seen (shows hardware CS per transfer vs. streamed blocks); **Bytes** — every frame as `C xx` / `D xx` (with `miso` wired, `D xx ← yy`); **Timing** — polarity-neutral edge markers `busy_fall` / `busy_rise` (with the duration of the previous level) and `res_fall` / `res_rise` (with the time RES# was low); there is no `Status` row, the chip decoders show the busy spans; **Warnings** — CS# released after a partial frame (`BITS?`), D/C# changing inside a byte (`DC?`).

`OUTPUT_PYTHON` packets (`(kind, value)`, consumed by chip decoders): `('BYTE', (dc, mosi_byte, miso_byte_or_None))` with `dc` 0 = command, 1 = data; `('CS', asserted)`; `('BUSY', level)` (raw pin level 0/1, sent at the start and on every change); `('RESET', asserted)`; `('IDLE', None)` after 10 ms with CS# high and no clock, because libsigrokdecode has no end-of-capture hook and a chip decoder needs it to report a pending open-ended command.

Chip decoders (`uc8276c`, `ssd1683`) declare `inputs = ['display_spi']` and carry no channels of their own; they own command grouping and semantics. Typical stack: `-P display_spi:clk=0:mosi=1:cs=2:dc=3:busy=4:res=5,uc8276c`.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Prerequisite
- [x] `InputPin` level read (`specs/feature_inputpin_level_read.md`) in Python (`MicroPythonPin`, `CircuitPythonPin`, `LinuxPollingPin`, `LinuxSysfsPin`), C++ (`InputPinArduino`, `InputPinLinux`, `InputPinZephyr`, `InputPinESPIDF`, `InputPinPicoSDK`, `InputPinSTM32Cube`, the vl53l1x test fake), Node.js (`EpollInputPin`, `PollingInputPin`), JVM (`SysfsInputPin`, `PollingInputPin`, the test fakes in Java/Kotlin/Groovy) and Go (`GpioInputPin` Linux + TinyGo, `PollingInputPin` via `NewPollingInputPinWithLevel`, the rfm9x test fake); Rust needs none

### Python
- [x] `python/periph/connection/softspi.py` — SoftSPI primitive over OutputPin/InputPin (one generic file; MicroPython/CircuitPython have their own software SPI)
- [x] `python/periph/connection/display_base.py` — shared `DisplayConnectionBase` and `WireMode`
- [x] `python/periph/connection/display_micropython.py` — Google-style docstring on class and every public method
- [x] `python/periph/connection/display_circuitpython.py` — Google-style docstring on class and every public method
- [x] `python/periph/connection/display_linux.py` — Google-style docstring on class and every public method
- [x] `python/periph/connection/display_mock.py`
- [x] Unit tests `python/tests/connection/display_test_unit.py` (fake pins/SPI/spidev; all three platform classes, SoftSPI, mock) and `python/tests/connection/input_pin_level_test_unit.py`
- [ ] Tests (MicroPython) — hardware-in-loop
- [ ] Tests (CircuitPython) — hardware-in-loop
- [ ] Tests (Linux) — hardware-in-loop

### C++
- [x] `cpp/src/connection/SoftSPI.h` — Doxygen (one generic header over OutputPin/InputPin, used by every platform class)
- [x] `cpp/src/connection/DisplayConnectionBase.h` — Doxygen (shared logic, `DisplayWireMode`)
- [x] `cpp/src/connection/DisplayConnection.h` (Arduino) — Doxygen (header-only)
- [x] `cpp/src/connection/DisplayConnectionLinux.h` — Doxygen (header-only)
- [x] `cpp/src/connection/DisplayConnectionZephyr.h` — Doxygen (header-only)
- [x] `cpp/src/connection/DisplayConnectionESPIDF.h` — Doxygen (header-only)
- [x] `cpp/src/connection/DisplayConnectionPicoSDK.h` — Doxygen (header-only)
- [x] `cpp/src/connection/DisplayConnectionSTM32Cube.h` — Doxygen (header-only)
- [x] `cpp/src/connection/DisplayConnectionMock.h`
- [x] Unit test `cpp/tests/connection/display_test_unit/display_test_unit.cpp` (fake pins, simulated clock, `SoftSPI`, mock; run by `build-all.sh linux`)
- [x] Smoke apps `cpp/tests/connection/display_test{,_linux,_zephyr,_espidf,_picosdk,_stm32cube}` (compile-verified on every platform: Arduino ESP32-S3 + AVR Mega, Linux GCC `-Werror`, Zephyr rpi_pico2, ESP-IDF v6.1, Pico SDK, STM32Cube NUCLEO-F411RE; **not run on hardware**)
- [ ] Tests on hardware (Arduino, Linux GCC, Zephyr, ESP-IDF, Pico SDK, STM32Cube) — hardware-in-loop

### Node.js
- [x] `nodejs/packages/periph/src/connection/softspi.js` — JSDoc (SoftSPI over opengpio-style outputs + `InputPin`)
- [x] `nodejs/packages/periph/src/connection/display.js` — JSDoc on class and every exported method
- [x] `nodejs/packages/periph/src/connection/display_mock.js`
- [x] Unit tests `nodejs/tests/connection/display_test_unit.js` and `nodejs/tests/connection/input_pin_level_test_unit.js`
- [ ] Tests (Linux hardware) — hardware-in-loop

### Sigrok
- [x] Decoder `sigrok/display_spi/__init__.py` — module docstring describing framing, channels, options, annotations and the packets stacked decoders consume (uncommitted)
- [x] Decoder `sigrok/display_spi/pd.py` — frames 4-wire and 3-wire SPI, annotates CS# windows/bytes/BUSY/RES#, emits `BYTE`/`CS`/`BUSY`/`RESET`/`IDLE` on `OUTPUT_PYTHON`. Verified with `sigrok-cli` against synthetic captures from `sigrok/tests/uc8276c/make_capture.py` (4-wire, 3-wire, shared DIN/DOUT, separate DOUT); replayed against a real UC8276C capture (`sigrok/tests/uc8276c/Init sequence.sr`; initial levels taken from sample 0) (uncommitted)

### Rust
- [x] `rust/periph/src/connection/display.rs` — `//!` module doc + `///` on every `pub` item
- [x] Unit tests (12, `embedded-hal-mock`, colocated in `display.rs`)
- [x] Tests (Linux) — `rust/tests/connection/display_test` (compile-verified; **not run on hardware**)
- [x] Tests (ESP32-S3) — `rust/tests/connection/display_test_esp32s3` (compile-verified; **not run on hardware**)

### JVM
- [x] `jvm/periph-connection/src/main/java/it/uhde/periph/connection/{DisplayConn,DisplayConnection,WireMode,SoftSpi,MockDisplayConnection}.java` — Javadoc on every public type and method
- [x] Unit tests `jvm/periph-java/src/test/java/it/uhde/periph/chips/display/DisplayConnectionTest.java`
- [ ] Tests (Pi hardware, JBang)

### Go
- [x] `go/periph/connection/{display.go,softspi.go,display_mock.go}` — Go doc comment on every exported type and method (platform-neutral core, SoftSPI, mock)
- [x] `go/periph/connection/display_linux.go` — Go doc comment on the constructor
- [x] `go/periph/connection/display_tinygo.go` — Go doc comment on the constructor (compile-verified with `tinygo build -target=pico-w`)
- [x] Unit tests `go/periph/connection/display_test.go`
- [ ] Tests (Linux hardware) — hardware-in-loop
- [ ] Tests (TinyGo / Pico W) — hardware-in-loop
