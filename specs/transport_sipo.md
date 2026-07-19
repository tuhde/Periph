# Transport Spec: SiPo

**Protocol:** Serial-in, parallel-out shift register (SER IN + SRCK over hardware SPI, plus RCK/SRCLR/G control lines)
**Reference:** TI TPIC6B595 datasheet, https://www.ti.com/lit/ds/symlink/tpic6b595.pdf (see issue #110), representative of the SIPO shift-register family

## Overview

The SiPo transport drives cascadable serial-in/parallel-out shift-register chips such as the TPIC6B595, SN74HC595, and SN74HCT595. These chips shift a byte stream in on SER IN, clocked by SRCK, and only move it to the output-driving storage register when a separate register clock (RCK) is pulsed. SER IN and SRCK are electrically identical to an SPI MOSI/SCK pair, so this transport shifts data using the platform's **hardware SPI peripheral** wherever one is available, falling back to software SPI only where the platform has no hardware SPI to offer (e.g. MicroPython on pins without a hardware SPI unit). RCK — and, where wired, SRCLR and G — are plain GPIO lines driven directly by the transport.

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

SPI mode 0 (CPOL=0, CPHA=0) satisfies tsu/th: data is driven on the falling edge and sampled on the rising edge. A default SPI clock of **1 MHz** (500 ns half-period) gives a large margin over the 40 ns tw minimum; higher clocks are fine as long as the half-period stays ≥40 ns.

### Write Sequence

1. RCK idle LOW (SRCLR idle HIGH and G idle LOW, if configured)
2. SPI-transfer `data` MSB-first over SER IN/SRCK
3. Pulse RCK HIGH for ≥40 ns, then LOW — this latches the just-shifted data from the shift register into the storage register that drives the outputs
4. Outputs update atomically on the RCK falling edge (rising edge triggers the latch; returning LOW just re-arms it)

`data` may be any length that is a multiple of one byte per cascaded device. The transport shifts it as opaque bytes; the mapping from buffer position to a specific device/output in a cascade is the chip driver's responsibility, same convention as the NeoPixel transport.

### Clear and Output Enable

- **Clear:** pulse SRCLR LOW for ≥40 ns, then HIGH. This clears the shift register only — the storage register (and therefore the outputs) is unaffected until the next RCK pulse. To actually blank the outputs, call `write()` with a zeroed buffer; `clear()` alone is not sufficient.
- **Output enable:** drive G LOW to let the storage register drive the outputs (normal operation), HIGH to force every output off without disturbing the storage register's contents.

## Interface Contract

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `spi`, `rck`, `srclr=None`, `g=None` | — | Configure SPI (mode 0, MSB-first) and GPIO pins; drive RCK LOW; drive SRCLR HIGH if configured; drive G LOW if configured |
| `write` | `data: bytes` | — | SPI-transfer `data` MSB-first, then pulse RCK HIGH→LOW (≥40 ns high) to latch it into the output register |
| `clear` | — | — | Pulse SRCLR LOW→HIGH (≥40 ns low) to clear the shift register; raise/return an error if `srclr` was not configured |
| `set_output_enable` | `enabled: bool` | — | Drive G LOW (`enabled=True`) or HIGH (`enabled=False`); raise/return an error if `g` was not configured |
| `close` | — | — | Release the SPI device and GPIO lines |

## Configuration Parameters

| Parameter | Platform | Type | Description |
|-----------|----------|------|--------------|
| `spi` | MicroPython | `machine.SPI` or `machine.SoftSPI` | SPI instance, mode 0, MSB-first; use hardware `machine.SPI` where the pin mapping allows it, `SoftSPI` otherwise |
| `rck`, `srclr`, `g` | MicroPython | `machine.Pin(n, machine.Pin.OUT)` | Digital output lines; `srclr`/`g` may be `None` |
| `spi` | CircuitPython | `busio.SPI` | SPI instance (always hardware-backed on CircuitPython); `configure()` called around each transfer |
| `rck`, `srclr`, `g` | CircuitPython | `digitalio.DigitalInOut` (direction: OUTPUT) | `srclr`/`g` may be `None` |
| `bus_num`, `device_num` | Linux Python | `int` | Opens `/dev/spidevB.D` (hardware SPI), mode 0, 1 MHz |
| `rck`, `srclr`, `g` | Linux Python | `gpiod.Line` (direction=OUTPUT) | `srclr`/`g` may be `None` |
| `spi` | Arduino | `SPIClass&` | Hardware SPI bus; speed set via `SPISettings` |
| `rck`, `srclr`, `g` | Arduino | `int` pin number | `pinMode(pin, OUTPUT)` in `init`; `srclr`/`g` may be `-1` to disable |
| `bus_num`, `device_num` | Linux GCC | `int` | Opens `/dev/spidevB.D` (hardware SPI), mode 0, 1 MHz |
| `rck`, `srclr`, `g` | Linux GCC | `gpiod_line *` (output) | `srclr`/`g` may be `nullptr` |
| `dev`, `config` | Zephyr | `const struct device *`, `struct spi_config` | Hardware SPI controller and config, mode 0, 1 MHz |
| `rck`, `srclr`, `g` | Zephyr | `gpio_dt_spec` (GPIO_OUTPUT) | `srclr`/`g` may be an unpopulated (`.port == NULL`) spec to disable |
| `bus_num`, `device_num` | Node.js | `int` | Opens `spi-device` (hardware SPI), mode 0, 1 MHz |
| `rck`, `srclr`, `g` | Node.js | `object` (`onoff` Gpio, direction `'out'`) | `srclr`/`g` may be `null` |
| `spi` | Rust (embedded-hal) | `impl SpiBus` | Hardware `embedded_hal::spi::SpiBus` at 1 MHz, mode 0 |
| `rck`, `srclr`, `g` | Rust (embedded-hal) | `impl OutputPin`, `Option<impl OutputPin>` | `srclr`/`g` wrapped in `Option` |
| `spi` | Rust Linux | `impl SpiBus` | `linux-embedded-hal` `SpidevBus` at 1 MHz, mode 0 |
| `rck`, `srclr`, `g` | Rust Linux | `impl OutputPin`, `Option<impl OutputPin>` | Same as embedded-hal; `linux-embedded-hal` `CdevPin` |
| `busNumber`, `deviceNumber` | JVM | `int` | Opens `/dev/spidevB.D` via FFM ioctl (hardware SPI), mode 0, 1 MHz — same approach as `NeoPixelTransport` |
| `rckLine`, `srclrLine`, `g` `Line` | JVM | `int` (GPIO line number, or `-1` to disable) | `/dev/gpiochip0` character-device lines via FFM ioctl — same approach as the DE-pin handling in `UARTTransport` |

## Platform Notes

All platforms follow the same structure: transfer `data` over hardware SPI, then pulse RCK as a plain GPIO write. SRCLR and G, where configured, are plain GPIO writes with no timing beyond the 40 ns tw minimum, which every platform's normal GPIO write latency exceeds.

### MicroPython

Prefer `machine.SPI` (hardware peripheral) constructed by the caller and passed in; fall back to `machine.SoftSPI` only when the target pins aren't on a hardware SPI unit. `write()` calls `spi.write(data)` then toggles the RCK `Pin` object HIGH/LOW with `pin.value(1)` / `pin.value(0)` — no explicit delay needed, MicroPython's per-call overhead exceeds 40 ns.

File: `python/periph/transport/sipo_micropython.py`

### CircuitPython

`busio.SPI` is always hardware-backed. `write()` locks the bus (`spi.try_lock()`), calls `spi.configure(baudrate=1_000_000, polarity=0, phase=0)`, transfers via `spi.write(data)`, unlocks, then toggles the RCK `DigitalInOut.value`.

File: `python/periph/transport/sipo_circuitpython.py`

### Linux Python

Wrap `spidev.SpiDev` (hardware SPI) at 1 MHz, mode 0, for the shift transfer, and `gpiod` lines for RCK/SRCLR/G. `write()` calls `spi.writebytes2(data)` then drives the RCK line high then low. Release both the SPI device and any configured `gpiod` lines in `close()`.

File: `python/periph/transport/sipo_linux.py`

### Arduino

Constructor accepts a `SPIClass&` (hardware SPI) plus pin numbers for RCK/SRCLR/G (`-1` to disable the optional pins). `write()` wraps the transfer in `SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0))` / `SPI.transfer(buf, len)` / `SPI.endTransaction()`, then `digitalWrite(rck, HIGH); digitalWrite(rck, LOW);`.

Files: `cpp/src/transport/SiPoTransport.h`, `cpp/src/transport/SiPoTransport.cpp`

### Linux GCC

Same structure as Linux Python: hardware `/dev/spidevB.D` via the kernel spidev driver at 1 MHz, mode 0, plus `gpiod_line_set_value()` for RCK/SRCLR/G. Release the spidev fd and any configured lines in the destructor / `close()`.

Files: `cpp/src/transport/SiPoTransportLinux.h`, `cpp/src/transport/SiPoTransportLinux.cpp`

### Zephyr RTOS

Constructor accepts `const struct device *` + `struct spi_config` (hardware SPI, `config.frequency = 1000000`, `SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER`) plus `gpio_dt_spec` for RCK/SRCLR/G. `write()` calls `spi_write()` with a single `spi_buf_set`, then `gpio_pin_set_dt()` HIGH/LOW on RCK.

`prj.conf`: `CONFIG_SPI=y`, `CONFIG_GPIO=y`, `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`.

File: `cpp/src/transport/SiPoTransportZephyr.h`

### Node.js

Constructor accepts `busNumber`/`deviceNumber` (hardware SPI via `spi-device`, 1 MHz, mode 0) plus `onoff` `Gpio` objects for RCK/SRCLR/G. `write()` calls `spi.transferSync([...])` then `rck.writeSync(1); rck.writeSync(0);`.

File: `nodejs/packages/periph/src/transport/sipo.js`

### Rust (embedded-hal, bare-metal / ESP32-S3)

Constructor wraps any hardware `embedded_hal::spi::SpiBus` at 1 MHz plus `OutputPin` for RCK and `Option<impl OutputPin>` for SRCLR/G.

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

Same implementation. Use `linux-embedded-hal`'s `SpidevBus` (hardware SPI) at 1 MHz and `CdevPin` for RCK/SRCLR/G.

```toml
linux-embedded-hal = "0.4"
embedded-hal = "1"
```

### JVM

No `SPITransport` class exists in `periph-transport` (JVM targets i2c-dev only for the generic bus wrapper); follow `NeoPixelTransport`'s pattern of talking to `/dev/spidevB.D` directly via FFM `ioctl` (`SPI_IOC_WR_MODE`, `SPI_IOC_WR_MAX_SPEED_HZ`, `SPI_IOC_MESSAGE_1`) at 1 MHz, mode 0 — this is still the kernel's hardware SPI driver, just accessed without an intermediate Java class. For RCK/SRCLR/G, follow `UARTTransport`'s DE-pin pattern: request lines on `/dev/gpiochip0` via `GPIO_GET_LINEHANDLE_IOCTL` and drive them with `GPIOHANDLE_SET_LINE_VALUES_IOCTL`. `-1` for `srclrLine`/`gLine` disables the optional pin.

File: `jvm/periph-transport/src/main/java/it/uhde/periph/transport/SiPoTransport.java`

## Sigrok Decoder

The `sipo` decoder stacks on sigrok's built-in `spi` decoder — SER IN/SRCK are electrically SPI, so it consumes the `spi` PD's `OUTPUT_PYTHON` `DATA` packets for the shifted MOSI byte stream — and additionally takes the `rck` logic channel (required) plus optional `srclr` and `g` channels. It buffers bytes seen since the last RCK rising edge and, on each RCK rising edge, emits a `LATCH` annotation carrying that buffered payload (the new output-register contents). SRCLR LOW pulses are annotated `CLEAR`; G HIGH periods are annotated `OUTPUTS DISABLED`. It emits `OUTPUT_PYTHON` packets — `('LATCH', bytes)`, `('CLEAR', None)` — that chip-level decoders for specific SiPo-based devices can stack on to interpret individual output pins.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] `python/periph/transport/sipo_micropython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/sipo_circuitpython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/sipo_linux.py` — Google-style docstring on class and every public method
- [ ] Tests (MicroPython)
- [ ] Tests (CircuitPython)
- [ ] Tests (Linux)

### C++
- [ ] `cpp/src/transport/SiPoTransport.h` — Doxygen `/** @brief */` on class and every public method
- [ ] `cpp/src/transport/SiPoTransport.cpp`
- [ ] `cpp/src/transport/SiPoTransportLinux.h` — Doxygen
- [ ] `cpp/src/transport/SiPoTransportLinux.cpp`
- [ ] `cpp/src/transport/SiPoTransportZephyr.h` — Doxygen (header-only)
- [ ] Tests (Arduino)
- [ ] Tests (Linux GCC)
- [ ] Tests (Zephyr)

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
