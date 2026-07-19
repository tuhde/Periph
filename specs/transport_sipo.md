# Transport Spec: SiPo

**Protocol:** Serial-in, parallel-out shift register (SER IN + SRCK over hardware or software SPI, plus RCK/SRCLR/G control lines)
**Reference:** TI TPIC6B595 datasheet, https://www.ti.com/lit/ds/symlink/tpic6b595.pdf (see issue #110), representative of the SIPO shift-register family

## Overview

The SiPo transport drives cascadable serial-in/parallel-out shift-register chips such as the TPIC6B595, SN74HC595, and SN74HCT595. These chips shift a byte stream in on SER IN, clocked by SRCK, and only move it to the output-driving storage register when a separate register clock (RCK) is pulsed. SER IN and SRCK are electrically identical to an SPI MOSI/SCK pair, so this transport shifts data over SPI — either the platform's **hardware SPI peripheral** (fast, MHz-range, but ties SER IN/SRCK to fixed hardware pins) or a **bit-banged software SPI** (slow, limited by GPIO call overhead, but works on any two GPIO pins). The caller chooses which one to use at construction time; see [Hardware vs. Software SPI](#hardware-vs-software-spi). RCK — and, where wired, SRCLR and G — are always plain GPIO lines driven directly by the transport, regardless of which SPI mode is chosen.

**Compatible chips:** TPIC6B595, SN74HC595, SN74HCT595, MC74HC595, and other devices exposing the same SER IN / SRCK / RCK / SRCLR / G pin set. SER OUT (the cascade tap) is a hardware-only signal — the transport does not read it back; chip drivers wire it to the next device's SER IN in hardware.

This transport is **write-only**: no `read` or `write_read` operation exists.

## Protocol

### Pins

| Pin | Direction | Mandatory | Idle state | Function |
|-----|-----------|-----------|------------|----------|
| SER IN | MCU → chip | Yes | — | Serial data in; wired to SPI MOSI |
| SRCK | MCU → chip | Yes | LOW | Shift register clock; wired to SPI SCK |
| RCK | MCU → chip | Yes | LOW | Register clock; pulsed HIGH→LOW after each SPI transfer to latch the shift register into the output storage register |
| SRCLR | MCU → chip | No | HIGH (inactive) | Active-low asynchronous clear of the shift register only — does **not** change the outputs until the next RCK pulse |
| G | MCU → chip | No | LOW (active) | Active-low output enable; HIGH forces all outputs off regardless of storage-register contents |

SRCLR and G may be permanently tied off in hardware and omitted from the transport config; RCK cannot be — every write requires an RCK edge, so it is always a driven GPIO.

### Timing

Figures below are from the TPIC6B595 datasheet; treat them as representative minimums for the family and confirm exact numbers against the specific compatible chip when adding one.

| Symbol | Event | Min | Unit |
|--------|-------|-----|------|
| tsu | SER IN setup before SRCK rising edge | 20 | ns |
| th | SER IN hold after SRCK rising edge | 20 | ns |
| tw | Minimum pulse duration (SRCK, RCK, SRCLR) | 40 | ns |

SPI mode 0 (CPOL=0, CPHA=0) satisfies tsu/th: data is driven on the falling edge and sampled on the rising edge. In hardware SPI mode, a default clock of **1 MHz** (500 ns half-period) gives a large margin over the 40 ns tw minimum; higher clocks are fine as long as the half-period stays ≥40 ns. In software (bit-banged) SPI mode, the achieved rate is whatever the GPIO call loop produces — see [Hardware vs. Software SPI](#hardware-vs-software-spi) — which is always well within these minimums but far slower than hardware SPI.

### Write Sequence

1. RCK idle LOW (SRCLR idle HIGH and G idle LOW, if configured)
2. SPI-transfer `data` MSB-first over SER IN/SRCK
3. Pulse RCK HIGH for ≥40 ns, then LOW — this latches the just-shifted data from the shift register into the storage register that drives the outputs
4. Outputs update atomically on the RCK falling edge (rising edge triggers the latch; returning LOW just re-arms it)

`data` may be any length that is a multiple of one byte per cascaded device. The transport shifts it as opaque bytes; the mapping from buffer position to a specific device/output in a cascade is the chip driver's responsibility, same convention as the NeoPixel transport.

### Clear and Output Enable

- **Clear:** pulse SRCLR LOW for ≥40 ns, then HIGH. This clears the shift register only — the storage register (and therefore the outputs) is unaffected until the next RCK pulse. To actually blank the outputs, call `write()` with a zeroed buffer; `clear()` alone is not sufficient.
- **Output enable:** drive G LOW to let the storage register drive the outputs (normal operation), HIGH to force every output off without disturbing the storage register's contents.

## Hardware vs. Software SPI

Some platforms already have a first-class software-SPI object with the same interface as their hardware SPI object, so the transport just accepts either and uses it unchanged. Others don't, so the transport itself bit-bangs SER IN/SRCK as two plain GPIO lines when software mode is selected.

| Platform | Hardware SPI | Software SPI |
|----------|--------------|---------------|
| MicroPython | `machine.SPI` | `machine.SoftSPI` — same object shape, no transport code difference |
| CircuitPython | `busio.SPI` | `bitbangio.SPI` — same object shape, no transport code difference |
| Linux Python | `spidev.SpiDev` on `/dev/spidevB.D` | transport bit-bangs `ser_in`/`srck` `gpiod.Line`s |
| Arduino | `SPIClass&` | transport bit-bangs `ser_in`/`srck` pin numbers |
| Linux GCC | `/dev/spidevB.D` via kernel spidev | transport bit-bangs `ser_in`/`srck` `gpiod_line*`s |
| Zephyr RTOS | `spi_config` on a hardware SPI controller node | same `spi_config`/`spi_write()` API against a `spi-bitbang`-compatible devicetree node — no transport code difference |
| Node.js | `spi-device` on `/dev/spidevB.D` | transport bit-bangs `ser_in`/`srck` `onoff` `Gpio`s |
| Rust (embedded-hal / Linux) | Hardware `impl SpiBus` | caller supplies any bit-banged `impl SpiBus` (e.g. from `embedded-hal-bus`) — no transport code difference |
| JVM | `/dev/spidevB.D` via FFM ioctl | transport bit-bangs `serInLine`/`srckLine` GPIO chardev lines via FFM ioctl |

Where the transport does its own bit-banging, the loop is the same everywhere (MSB-first, mode 0 — data driven before the rising edge, sampled on it):

```
for byte in data:
    for bit in 7..0:            # MSB first
        ser_in.write((byte >> bit) & 1)
        srck.write(1)
        srck.write(0)
```

No explicit delay is needed between edges on any platform: the 40 ns tw / 20 ns tsu / 20 ns th minimums are far smaller than the call overhead of a single GPIO write on every target here (µs-range on Linux userspace GPIO, sub-µs but still >40 ns on microcontroller `digitalWrite`/`gpio_pin_set` calls) — the same reasoning as the [HX711 transport](transport_hx711.md)'s clock-edge notes.

## Interface Contract

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | *(hardware mode)* `spi`, `rck`, `srclr=None`, `g=None` — *or* *(software mode)* `ser_in`, `srck`, `rck`, `srclr=None`, `g=None` | — | Configure SPI (mode 0, MSB-first) or the bit-bang GPIO pair, plus RCK/SRCLR/G; drive RCK LOW; drive SRCLR HIGH if configured; drive G LOW if configured |
| `write` | `data: bytes` | — | Shift `data` out MSB-first — via the hardware/software SPI object in hardware mode, or the bit-bang loop in software mode — then pulse RCK HIGH→LOW (≥40 ns high) to latch it into the output register |
| `clear` | — | — | Pulse SRCLR LOW→HIGH (≥40 ns low) to clear the shift register; raise/return an error if `srclr` was not configured |
| `set_output_enable` | `enabled: bool` | — | Drive G LOW (`enabled=True`) or HIGH (`enabled=False`); raise/return an error if `g` was not configured |
| `close` | — | — | Release the SPI device and GPIO lines |

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|--------------|
| `spi` | MicroPython | `machine.SPI` (hardware) or `machine.SoftSPI` (software) | SPI instance, mode 0, MSB-first; caller picks which class to construct and pass in |
| `rck`, `srclr`, `g` | MicroPython | `machine.Pin(n, machine.Pin.OUT)` | Digital output lines; `srclr`/`g` may be `None` |
| `spi` | CircuitPython | `busio.SPI` (hardware) or `bitbangio.SPI` (software) | SPI instance; `configure()` called around each transfer; caller picks which class to construct and pass in |
| `rck`, `srclr`, `g` | CircuitPython | `digitalio.DigitalInOut` (direction: OUTPUT) | `srclr`/`g` may be `None` |
| `bus_num`, `device_num` | Linux Python | `int` | Hardware mode: opens `/dev/spidevB.D`, mode 0, 1 MHz |
| `ser_in`, `srck` | Linux Python | `gpiod.Line` (direction=OUTPUT) | Software mode: transport bit-bangs these two lines instead of opening spidev |
| `rck`, `srclr`, `g` | Linux Python | `gpiod.Line` (direction=OUTPUT) | Always required/optional as before, independent of SPI mode; `srclr`/`g` may be `None` |
| `spi` | Arduino | `SPIClass&` | Hardware mode: SPI bus; speed set via `SPISettings` |
| `ser_in`, `srck` | Arduino | `int` pin number | Software mode: transport bit-bangs these two pins instead of using `SPIClass` |
| `rck`, `srclr`, `g` | Arduino | `int` pin number | Always required/optional as before; `pinMode(pin, OUTPUT)` in `init`; `srclr`/`g` may be `-1` to disable |
| `bus_num`, `device_num` | Linux GCC | `int` | Hardware mode: opens `/dev/spidevB.D`, mode 0, 1 MHz |
| `ser_in`, `srck` | Linux GCC | `gpiod_line *` (output) | Software mode: transport bit-bangs these two lines instead of opening spidev |
| `rck`, `srclr`, `g` | Linux GCC | `gpiod_line *` (output) | Always required/optional as before; `srclr`/`g` may be `nullptr` |
| `dev`, `config` | Zephyr | `const struct device *`, `struct spi_config` | Hardware or software (bitbang-controller devicetree node) SPI — same API either way, mode 0, 1 MHz |
| `rck`, `srclr`, `g` | Zephyr | `gpio_dt_spec` (GPIO_OUTPUT) | `srclr`/`g` may be an unpopulated (`.port == NULL`) spec to disable |
| `bus_num`, `device_num` | Node.js | `int` | Hardware mode: opens `spi-device` on `/dev/spidevB.D`, mode 0, 1 MHz |
| `ser_in`, `srck` | Node.js | `object` (`onoff` Gpio, direction `'out'`) | Software mode: transport bit-bangs these two GPIOs instead of opening spi-device |
| `rck`, `srclr`, `g` | Node.js | `object` (`onoff` Gpio, direction `'out'`) | Always required/optional as before; `srclr`/`g` may be `null` |
| `spi` | Rust (embedded-hal) | `impl SpiBus` | Hardware or caller-supplied bit-banged `SpiBus` (e.g. `embedded-hal-bus`) at up to 1 MHz, mode 0 — no distinction in the transport's type signature |
| `rck`, `srclr`, `g` | Rust (embedded-hal) | `impl OutputPin`, `Option<impl OutputPin>` | `srclr`/`g` wrapped in `Option` |
| `spi` | Rust Linux | `impl SpiBus` | `linux-embedded-hal` `SpidevBus` (hardware) or any bit-banged `SpiBus` impl (software) at up to 1 MHz, mode 0 |
| `rck`, `srclr`, `g` | Rust Linux | `impl OutputPin`, `Option<impl OutputPin>` | Same as embedded-hal; `linux-embedded-hal` `CdevPin` |
| `busNumber`, `deviceNumber` | JVM | `int` | Hardware mode: opens `/dev/spidevB.D` via FFM ioctl, mode 0, 1 MHz — same approach as `NeoPixelTransport` |
| `serInLine`, `srckLine` | JVM | `int` (GPIO line number) | Software mode: transport bit-bangs these two `/dev/gpiochip0` lines via FFM ioctl instead of opening spidev |
| `rckLine`, `srclrLine`, `gLine` | JVM | `int` (GPIO line number, or `-1` to disable) | Always required/optional as before; `/dev/gpiochip0` character-device lines via FFM ioctl — same approach as the DE-pin handling in `UARTTransport` |

## Platform Notes

All platforms follow the same structure: transfer `data` over SPI (hardware or software, per the caller's choice), then pulse RCK as a plain GPIO write. SRCLR and G, where configured, are plain GPIO writes with no timing beyond the 40 ns tw minimum, which every platform's normal GPIO write latency exceeds.

### MicroPython

Constructor accepts a `machine.SPI` or `machine.SoftSPI` instance — both expose the same `write()` method, so the transport code doesn't branch on which one it got; the caller decides by constructing one or the other before passing it in. `write()` calls `spi.write(data)` then toggles the RCK `Pin` object HIGH/LOW with `pin.value(1)` / `pin.value(0)` — no explicit delay needed, MicroPython's per-call overhead exceeds 40 ns.

File: `python/periph/transport/sipo_micropython.py`

### CircuitPython

Constructor accepts a `busio.SPI` (hardware) or `bitbangio.SPI` (software) instance — same interface, no branching needed. `write()` locks the bus (`spi.try_lock()`), calls `spi.configure(baudrate=1_000_000, polarity=0, phase=0)`, transfers via `spi.write(data)`, unlocks, then toggles the RCK `DigitalInOut.value`.

File: `python/periph/transport/sipo_circuitpython.py`

### Linux Python

Two constructor modes, since `spidev` only talks to a real hardware controller — there's no Python software-SPI object to substitute:
- **Hardware:** wrap `spidev.SpiDev` at 1 MHz, mode 0; `write()` calls `spi.writebytes2(data)`.
- **Software:** take two `gpiod.Line`s (`ser_in`, `srck`) and bit-bang the loop from [Hardware vs. Software SPI](#hardware-vs-software-spi) directly in `write()`.

Either way, RCK/SRCLR/G are `gpiod` lines driven the same way regardless of SPI mode. Release the SPI device (or bit-bang lines) and any configured RCK/SRCLR/G lines in `close()`.

File: `python/periph/transport/sipo_linux.py`

### Arduino

Two overloaded constructors:
- **Hardware:** `SPIClass&` plus pin numbers for RCK/SRCLR/G (`-1` to disable the optional pins). `write()` wraps the transfer in `SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0))` / `SPI.transfer(buf, len)` / `SPI.endTransaction()`.
- **Software:** `ser_in`/`srck` pin numbers instead of `SPIClass&`. `write()` bit-bangs the loop from [Hardware vs. Software SPI](#hardware-vs-software-spi) with `digitalWrite()`.

Both then do `digitalWrite(rck, HIGH); digitalWrite(rck, LOW);` to latch.

Files: `cpp/src/transport/SiPoTransport.h`, `cpp/src/transport/SiPoTransport.cpp`

### Linux GCC

Same two-mode structure as Linux Python:
- **Hardware:** `/dev/spidevB.D` via the kernel spidev driver at 1 MHz, mode 0.
- **Software:** two `gpiod_line*`s (`ser_in`, `srck`), bit-banged in `write()`.

RCK/SRCLR/G use `gpiod_line_set_value()` in both modes. Release the spidev fd (or bit-bang lines) and any configured RCK/SRCLR/G lines in the destructor / `close()`.

Files: `cpp/src/transport/SiPoTransportLinux.h`, `cpp/src/transport/SiPoTransportLinux.cpp`

### Zephyr RTOS

Constructor accepts `const struct device *` + `struct spi_config` plus `gpio_dt_spec` for RCK/SRCLR/G. Hardware vs. software SPI is a **devicetree choice**, not a code-path choice: point `dev` at a real SPI controller node for hardware, or at a `spi-bitbang`-compatible controller node for software — `spi_write()` and `spi_config` look identical either way. `write()` calls `spi_write()` with a single `spi_buf_set`, then `gpio_pin_set_dt()` HIGH/LOW on RCK.

`prj.conf`: `CONFIG_SPI=y`, `CONFIG_GPIO=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`.

File: `cpp/src/transport/SiPoTransportZephyr.h`

### Node.js

Two constructor modes:
- **Hardware:** `busNumber`/`deviceNumber` opening `spi-device` at 1 MHz, mode 0; `write()` calls `spi.transferSync([...])`.
- **Software:** `ser_in`/`srck` `onoff` `Gpio` objects instead; `write()` bit-bangs the loop from [Hardware vs. Software SPI](#hardware-vs-software-spi) with `writeSync`.

Both then call `rck.writeSync(1); rck.writeSync(0);` to latch. RCK/SRCLR/G are `onoff` `Gpio` objects in both modes.

File: `nodejs/packages/periph/src/transport/sipo.js`

### Rust (embedded-hal, bare-metal / ESP32-S3)

Constructor wraps any `embedded_hal::spi::SpiBus` (hardware or a caller-supplied bit-banged impl — the transport's generic bound doesn't distinguish) plus `OutputPin` for RCK and `Option<impl OutputPin>` for SRCLR/G.

```rust
impl<SPI: SpiBus, RCK: OutputPin, AUX: OutputPin> SiPoTransport<SPI, RCK, AUX> {
    pub fn write(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        self.spi.write(data)?;
        self.rck.set_high().ok();
        self.rck.set_low().ok();
        Ok(())
    }
}
```

File: `rust/periph/src/transport/sipo.rs`

### Rust Linux

Same implementation and same generic-`SpiBus` genericity. Use `linux-embedded-hal`'s `SpidevBus` for hardware SPI, or any bit-banged `SpiBus` impl (e.g. built on `linux-embedded-hal`'s `CdevPin` via `embedded-hal-bus`) for software SPI. `CdevPin` also provides RCK/SRCLR/G in both modes.

```toml
linux-embedded-hal = "0.4"
embedded-hal = "1"
```

### JVM

No `SPITransport` class exists in `periph-transport` (JVM targets i2c-dev only for the generic bus wrapper), so both modes live directly in `SiPoTransport`:
- **Hardware:** follow `NeoPixelTransport`'s pattern of talking to `/dev/spidevB.D` directly via FFM `ioctl` (`SPI_IOC_WR_MODE`, `SPI_IOC_WR_MAX_SPEED_HZ`, `SPI_IOC_MESSAGE_1`) at 1 MHz, mode 0.
- **Software:** take `serInLine`/`srckLine` GPIO line numbers and bit-bang the loop from [Hardware vs. Software SPI](#hardware-vs-software-spi), reusing the same `/dev/gpiochip0` FFM ioctl helpers as RCK.

For RCK/SRCLR/G in both modes, follow `UARTTransport`'s DE-pin pattern: request lines on `/dev/gpiochip0` via `GPIO_GET_LINEHANDLE_IOCTL` and drive them with `GPIOHANDLE_SET_LINE_VALUES_IOCTL`. `-1` for `srclrLine`/`gLine` disables the optional pin.

File: `jvm/periph-transport/src/main/java/it/uhde/periph/transport/SiPoTransport.java`

## Sigrok Decoder

The `sipo` decoder stacks on sigrok's built-in `spi` decoder — SER IN/SRCK are electrically SPI, so it consumes the `spi` PD's `OUTPUT_PYTHON` `DATA` packets for the shifted MOSI byte stream — and additionally takes the `rck` logic channel (required) plus optional `srclr` and `g` channels. It buffers bytes seen since the last RCK rising edge and, on each RCK rising edge, emits a `LATCH` annotation carrying that buffered payload (the new output-register contents). SRCLR LOW pulses are annotated `CLEAR`; G HIGH periods are annotated `OUTPUTS DISABLED`. It emits `OUTPUT_PYTHON` packets — `('LATCH', bytes)`, `('CLEAR', None)` — that chip-level decoders for specific SiPo-based devices can stack on to interpret individual output pins.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] `python/periph/transport/sipo_micropython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/sipo_circuitpython.py` — Google-style docstring on class and every public method
- [x] `python/periph/transport/sipo_linux.py` — Google-style docstring on class and every public method
- [x] Tests (MicroPython)
- [x] Tests (CircuitPython)
- [x] Tests (Linux)

### C++
- [x] `cpp/src/transport/SiPoTransport.h` — Doxygen `/** @brief */` on class and every public method
- [x] `cpp/src/transport/SiPoTransport.cpp`
- [x] `cpp/src/transport/SiPoTransportLinux.h` — Doxygen
- [x] `cpp/src/transport/SiPoTransportLinux.cpp`
- [x] `cpp/src/transport/SiPoTransportZephyr.h` — Doxygen (header-only)
- [x] Tests (Arduino)
- [x] Tests (Linux GCC)
- [x] Tests (Zephyr)

### Node.js
- [ ] `nodejs/packages/periph/src/transport/sipo.js` — JSDoc on class and every exported method
- [ ] Tests

### Rust
- [ ] `rust/periph/src/transport/sipo.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Tests (Linux)
- [ ] Tests (ESP32-S3)

### JVM
- [ ] `jvm/periph-transport/src/main/java/it/uhde/periph/transport/SiPoTransport.java` — Javadoc on class and every public method
- [ ] Tests (Pi hardware, JBang)

### Sigrok
- [ ] Decoder `sigrok/sipo/__init__.py` — module docstring describing protocol framing, signal channels, and what is annotated
- [ ] Decoder `sigrok/sipo/pd.py` — annotates framing, data bytes, and decoded values; produces `OUTPUT_ANN` only
