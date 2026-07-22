# Transport Spec: UART

**Protocol:** UART (Universal Asynchronous Receiver-Transmitter)  
**Reference:** Standard asynchronous serial framing (start bit, data bits, optional parity, stop bits)

## Overview

UART is a two-wire asynchronous serial transport (TX, RX). Each transport instance represents one remote device. Used when a chip lists UART as a supported transport.

RS-485 is a differential-signalling variant of UART that supports multi-point buses. It requires a direction-enable pin (DE, usually combined with /RE on the same signal) to switch the bus driver between transmit and receive. When a `de_pin` is provided at construction time, the transport operates in RS-485 mode: DE is asserted before transmitting and deasserted only after all bytes have physically shifted out. The `write`, `read`, and `write_read` interface is identical in both modes; DE management is internal to the transport.

## Interface Contract

All transport implementations must provide these operations.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `write` | `data: bytes` | — | Transmit bytes; in RS-485 mode, assert DE, transmit, drain TX, then deassert DE |
| `read` | `n: int` | `bytes` | Receive n bytes; blocks until n bytes arrive or timeout |
| `write_read` | `data: bytes, n: int` | `bytes` | Transmit then receive; used for command/response protocols |

`write_read` is equivalent to `write(data)` followed by `read(n)`. In RS-485 mode, DE is asserted only during the transmit phase.

DE polarity is active-high (assert = logic 1) on all platforms. If the hardware uses an active-low signal, invert the polarity at the GPIO level — the transport has no polarity parameter.

## Configuration Parameters

| Parameter | Platform | Type | Default | Description |
|-----------|----------|------|---------|-------------|
| `uart` | MicroPython | `machine.UART` | — | Configured UART instance |
| `de_pin` | MicroPython | `machine.Pin` | `None` | RS-485 DE pin; enables RS-485 mode when set |
| `uart` | CircuitPython | `busio.UART` | — | Configured UART instance |
| `de_pin` | CircuitPython | `digitalio.DigitalInOut` | `None` | RS-485 DE pin; enables RS-485 mode when set |
| `port` | Linux | `str` | — | Serial device path (e.g. `/dev/ttyS0`, `/dev/ttyUSB0`) |
| `baudrate` | Linux | `int` | 9600 | Baud rate |
| `data_bits` | Linux | `int` | 8 | Data bits (5–8) |
| `stop_bits` | Linux | `float` | 1 | Stop bits (1, 1.5, or 2) |
| `parity` | Linux | `str` | `'N'` | Parity: `'N'` none, `'E'` even, `'O'` odd |
| `timeout_s` | Linux | `float` | 1.0 | Read timeout in seconds |
| `de_pin_num` | Linux | `int` | `None` | GPIO line number for RS-485 DE; enables RS-485 mode when set |
| `serial` | Arduino | `HardwareSerial&` | — | Serial port object (`Serial1`, `Serial2`, etc.) |
| `de_pin` | Arduino | `int` | `-1` | RS-485 DE pin number; `-1` disables RS-485 mode |
| `dev` | Zephyr | `const struct device *` | — | UART device from devicetree (`DEVICE_DT_GET`) |
| `de_gpio` | Zephyr | `struct gpio_dt_spec` | zero-init | RS-485 DE GPIO spec; `port == NULL` disables RS-485 mode |
| `port` | Linux (C++) | `const char*` | — | Serial device path (e.g. `/dev/ttyS0`) |
| `baudrate` | Linux (C++) | `int` | 9600 | Baud rate |
| `data_bits` | Linux (C++) | `int` | 8 | Data bits (5–8) |
| `stop_bits` | Linux (C++) | `int` | 1 | Stop bits (1 or 2); termios does not support 1.5 |
| `parity` | Linux (C++) | `char` | `'N'` | Parity: `'N'` none, `'E'` even, `'O'` odd |
| `timeout_ms` | Linux (C++) | `int` | 1000 | Read timeout in milliseconds |
| `de_pin_num` | Linux (C++) | `int` | `-1` | GPIO line number for RS-485 DE; `-1` disables RS-485 mode |
| `path` | Node.js | `str` | — | Serial device path (e.g. `/dev/ttyS0`) |
| `baudRate` | Node.js | `int` | 9600 | Baud rate |
| `de_pin_num` | Node.js | `int` | `null` | GPIO line number for RS-485 DE; enables RS-485 mode when set |
| `port` | JVM | `String` | — | Serial device path (e.g. `/dev/ttyS0`, `/dev/ttyUSB0`) |
| `baudRate` | JVM | `int` | 9600 | Baud rate |
| `dataBits` | JVM | `int` | 8 | Data bits (5–8) |
| `stopBits` | JVM | `float` | 1.0 | Stop bits (1, 1.5, or 2) |
| `parity` | JVM | `char` | `'N'` | Parity: `'N'` none, `'E'` even, `'O'` odd |
| `timeoutMs` | JVM | `int` | 1000 | Read timeout in milliseconds |
| `dePinNum` | JVM | `int` | `-1` | GPIO line number for RS-485 DE; `-1` disables RS-485 mode |
| `U` (generic) | Rust | `impl Read + Write` | — | Any type implementing `embedded_io::Read + embedded_io::Write` |
| `uart` | Pico SDK | `uart_inst_t*` | — | UART peripheral (`uart0` or `uart1`), already configured via `uart_init()` |
| `de_pin` | Pico SDK | `int` (GPIO pin number) | `-1` | RS-485 DE pin; `-1` disables RS-485 mode |

For embedded platforms (MicroPython, CircuitPython, Arduino, Zephyr, Pico SDK, Rust embedded), the caller constructs and configures the UART peripheral before passing it to the transport. Baud rate, data bits, parity, and stop bits are set on the UART object at construction time.

For Linux and Node.js, the transport opens and configures the serial port itself using the provided parameters.

## Error Handling

| Condition | Behaviour |
|-----------|-----------|
| Read timeout | Raise `OSError` / return error; partial data discarded |
| Write failure | Raise `OSError` / return error code |
| RS-485 TX drain fails | Implementation must confirm TX FIFO empty before deasserting DE; premature deassert corrupts the last transmitted bytes |

## Platform Notes

### MicroPython

Wraps `machine.UART`. The caller constructs and configures the UART instance before passing it to the transport.

| Contract | MicroPython |
|----------|-------------|
| `write` | `de(1)` (RS-485) → `uart.write(data)` → baud-rate delay → `de(0)` (RS-485) |
| `read` | `uart.read(n)` |
| `write_read` | `write(data)` → `read(n)` |

`machine.UART` has no TX-empty interrupt on most ports. After `uart.write()` returns (bytes accepted into FIFO), wait for the bytes to physically shift out using a calculated delay: `time.sleep_us((len(data) * 10 * 1_000_000) // baudrate + 100)`. The factor of 10 accounts for start bit + 8 data bits + stop bit; the 100 µs margin covers stop-bit propagation.

File: `python/periph/transport/uart_micropython.py`

### CircuitPython

Wraps `busio.UART`. Constructor accepts a `busio.UART` instance and an optional `digitalio.DigitalInOut` for RS-485 DE.

| Contract | CircuitPython |
|----------|---------------|
| `write` | `de.value = True` (RS-485) → `uart.write(data)` → baud-rate delay → `de.value = False` (RS-485) |
| `read` | `uart.read(n)` |
| `write_read` | `write(data)` → `read(n)` |

`busio.UART.write()` blocks until bytes are in the hardware FIFO but does not guarantee physical transmission. Apply the same baud-rate-derived delay as MicroPython before deasserting DE.

File: `python/periph/transport/uart_circuitpython.py`

### Linux kernel

Wraps `pyserial` (`serial.Serial`). For RS-485, pyserial has built-in kernel RS-485 support via `serial.rs485.RS485Settings`; prefer it when the kernel driver supports it (e.g. `imx-uart`, `omap-serial`). Fall back to manual GPIO toggling via `python-periphery` when `de_pin_num` is provided and kernel RS-485 is unavailable.

Preferred path (kernel RS-485 support):
```python
import serial, serial.rs485
ser = serial.Serial(port, baudrate, ...)
ser.rs485_mode = serial.rs485.RS485Settings(
    rts_level_for_sending=True,
    rts_level_for_receiving=False,
)
```

Fallback path (manual GPIO via `python-periphery`):
```python
from periphery import GPIO
de = GPIO("/dev/gpiochip0", de_pin_num, "out")
de.write(True)
ser.write(data)
ser.flush()   # blocks until OS has transmitted all bytes
de.write(False)
```

`ser.flush()` blocks until the kernel transmit buffer is drained — safe to deassert DE immediately after on Linux.

| Contract | pyserial |
|----------|----------|
| `write` | RS-485 managed by driver or manual GPIO → `ser.write(data)` → `ser.flush()` |
| `read` | `ser.read(n)` |
| `write_read` | `write(data)` → `read(n)` |

File: `python/periph/transport/uart_linux.py`

### Arduino

Wraps `HardwareSerial`. Constructor accepts a `HardwareSerial&` reference and an optional DE pin number. Do not use `SoftwareSerial` for RS-485; it lacks reliable TX-complete detection.

| Contract | Arduino |
|----------|---------|
| `write` | `digitalWrite(de, HIGH)` (RS-485) → `serial.write(data, len)` → `serial.flush()` → `digitalWrite(de, LOW)` (RS-485) |
| `read` | `serial.readBytes(buf, n)` |
| `write_read` | `write(data, len)` → `read(buf, n)` |

`HardwareSerial::flush()` on Arduino blocks until the hardware TX shift register is empty — safe to deassert DE immediately after.

Files: `cpp/src/transport/UARTTransport.h`, `cpp/src/transport/UARTTransport.cpp`

### Zephyr RTOS

Wraps the Zephyr UART interrupt-driven API. Constructor accepts a `const struct device *` from `DEVICE_DT_GET()` and an optional `struct gpio_dt_spec` for RS-485 DE.

| Contract | Zephyr |
|----------|--------|
| `write` | `gpio_pin_set_dt(&de, 1)` (RS-485) → `uart_irq_tx_enable` + fill loop → wait for TX-complete callback → `gpio_pin_set_dt(&de, 0)` (RS-485) |
| `read` | `uart_irq_rx_enable` + accumulate in callback until n bytes received or timeout |
| `write_read` | `write(data)` → `read(n)` |

TX-complete detection: register a UART interrupt callback via `uart_irq_callback_user_data_set`. Inside the callback, after `uart_irq_tx_complete()` returns true, set a flag. The write path waits on that flag (via `k_sem`) before deasserting DE.

`prj.conf` must enable `CONFIG_UART_INTERRUPT_DRIVEN=y`, `CONFIG_GPIO=y` (RS-485), `CONFIG_CPP=y`, `CONFIG_STD_CPP17=y`. The UART device node must be present and enabled in the board's devicetree or an overlay.

File: `cpp/src/transport/UARTTransportZephyr.h`

### Raspberry Pi Pico SDK

Wraps `hardware_uart` (bare-metal `pico-sdk`, no Arduino core, no RTOS). Constructor accepts a `uart_inst_t*` (`uart0` or `uart1`) already configured via `uart_init()`, plus an optional GPIO pin number for RS-485 DE (`-1` disables RS-485 mode) — same convention as the Arduino and Linux (C++) transports.

| Contract | pico-sdk |
|----------|----------|
| `write` | `gpio_put(de, 1)` (RS-485) → `uart_write_blocking(uart, data, len)` → baud-rate-derived delay → `gpio_put(de, 0)` (RS-485) |
| `read` | `uart_read_blocking(uart, buf, n)` — blocks until exactly n bytes have arrived |
| `write_read` | `write(data, len)` → `read(buf, n)` |

pico-sdk exposes no TX-drain / TX-complete call and no RX byte-count API — only `uart_is_writable()` / `uart_is_readable()`, which report a single byte's readiness as a boolean, not a count. Two consequences:
- Before deasserting DE, use the same baud-rate-derived delay as the MicroPython/CircuitPython UART transports: `sleep_us(len * 10 * 1'000'000 / baudrate + 100)` (the factor of 10 covers start + 8 data + stop bit; the 100 µs margin covers stop-bit propagation).
- Where the transport exposes an `available()`-style query, it can only return 1 or 0 (`uart_is_readable()`) — a coarser contract than Zephyr's exact ring-buffer count. Document this divergence on the method.

`uart_read_blocking` already blocks until exactly `n` bytes have arrived, so `read()` needs no manual polling loop.

File: `cpp/src/transport/UARTTransportPicoSDK.h` (header-only)

### Linux GCC (C++)

Wraps the POSIX `termios` API. Constructor opens the serial device with `open(path, O_RDWR | O_NOCTTY)` and configures it via `tcgetattr` / `tcsetattr` (raw mode, baud rate, data bits, parity, stop bits).

| Contract | Linux (C++) |
|----------|-------------|
| `write` | RS-485 managed by kernel or GPIO → `::write(fd, data, len)` → `tcdrain(fd)` |
| `read` | `::read(fd, buf, n)` with VTIME-based timeout |
| `write_read` | `write(data, len)` → `read(buf, n)` |

For RS-485, prefer kernel RS-485 mode via `ioctl(fd, TIOCSRS485, &rs485)` with the `serial_rs485` struct (`SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND`). Fall back to libgpiod (`gpiod_line_set_value`) when the kernel driver lacks RS-485 support and `de_pin_num != -1`.

`tcdrain(fd)` blocks until the kernel transmit buffer is empty — safe to deassert DE immediately after.

`stop_bits` accepts 1 or 2; termios does not support 1.5 stop bits.

Files: `cpp/src/transport/UARTTransportLinux.h`, `cpp/src/transport/UARTTransportLinux.cpp`

### Node.js (Linux)

Wraps the `serialport` npm package. Constructor opens the port asynchronously and accumulates incoming bytes in an internal buffer. For RS-485 DE, uses the `onoff` package for GPIO toggling.

| Contract | serialport |
|----------|------------|
| `write` | DE high (RS-485) → `port.write(data)` → `port.drain()` → DE low (RS-485) |
| `read` | Resolves a `Promise` when n bytes have accumulated in the buffer or timeout fires |
| `write_read` | `write(data)` → `read(n)` |

`port.drain()` resolves after the OS has transmitted all bytes — safe to deassert DE immediately after on Linux.

File: `nodejs/packages/periph/src/transport/uart.js`

### JVM (Linux)

Uses the FFM API (Java 21+) to call libc functions directly — the same approach as `I2CTransport`. Opens the serial device with `open(path, O_RDWR | O_NOCTTY)` and configures it via `tcgetattr` / `tcsetattr` (raw mode, VMIN=0, VTIME for blocking reads with timeout).

| Contract | JVM (FFM) |
|----------|-----------|
| `write` | RS-485 managed by kernel or GPIO → `write(fd, data, len)` → `tcdrain(fd)` |
| `read` | `read(fd, buf, n)` with VTIME-based timeout |
| `writeRead` | `write(data)` → `read(n)` |

For RS-485, prefer kernel RS-485 mode via `ioctl(fd, TIOCSRS485, rs485Addr)` where `rs485Addr` is a `MemorySegment` holding a `serial_rs485` struct with `SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND`. Fall back to manual GPIO toggling via FFM when `dePinNum != -1` and the kernel driver lacks RS-485 support.

`tcdrain(fd)` blocks until the kernel transmit buffer is empty — safe to deassert DE immediately after.

File: `jvm/periph-transport/src/main/java/it/uhde/periph/transport/UARTTransport.java`

### Rust

#### Embedded (`embedded-io Read + Write`)

Chip drivers are generic over `embedded_io::Read + embedded_io::Write`. For ESP32-S3 with `esp-hal`, the UART peripheral implements both traits directly.

```rust
use embedded_io::{Read, Write};

pub struct MyChipMinimal<U> {
    uart: U,
}

impl<U: Read + Write> MyChipMinimal<U> { ... }
```

For RS-485 DE management, wrap the UART in a newtype that asserts a GPIO pin around `write` calls and waits for TX drain (platform-specific). The chip driver itself never sees this wrapping.

#### Linux (`serialport` crate)

Use the `serialport` crate. Wrap it in a newtype implementing `embedded_io::Read + embedded_io::Write`:

```rust
use serialport::SerialPort;
use embedded_io::{ErrorType, Read, Write};

struct SerialTransport(Box<dyn SerialPort>);

impl ErrorType for SerialTransport { type Error = std::io::Error; }

impl Read for SerialTransport {
    fn read(&mut self, buf: &mut [u8]) -> Result<usize, Self::Error> {
        std::io::Read::read(&mut self.0, buf).map_err(Into::into)
    }
}

impl Write for SerialTransport {
    fn write(&mut self, buf: &[u8]) -> Result<usize, Self::Error> {
        std::io::Write::write(&mut self.0, buf).map_err(Into::into)
    }
    fn flush(&mut self) -> Result<(), Self::Error> {
        std::io::Write::flush(&mut self.0).map_err(Into::into)
    }
}
```

`Cargo.toml` dependencies:
```toml
serialport = "4"
embedded-io = "0.6"
```

Files: `rust/periph/src/transport/uart.rs`, `rust/periph/src/transport/uart_linux.rs`

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] `python/periph/transport/uart_micropython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/uart_circuitpython.py` — Google-style docstring on class and every public method
- [ ] `python/periph/transport/uart_linux.py` — Google-style docstring on class and every public method
- [ ] Tests (MicroPython)
- [ ] Tests (CircuitPython)
- [ ] Tests (Linux)

### C++
- [ ] `cpp/src/transport/UARTTransport.h` — Doxygen `/** @brief */` on class and every public method
- [ ] `cpp/src/transport/UARTTransport.cpp`
- [ ] `cpp/src/transport/UARTTransportLinux.h` — Doxygen
- [ ] `cpp/src/transport/UARTTransportLinux.cpp`
- [ ] `cpp/src/transport/UARTTransportZephyr.h` — Doxygen (header-only)
- [ ] `cpp/src/transport/UARTTransportPicoSDK.h` — Doxygen (header-only)
- [ ] Tests (Arduino)
- [ ] Tests (Linux GCC)
- [ ] Tests (Zephyr)
- [ ] Tests (Pico SDK)

### Node.js
- [ ] `nodejs/packages/periph/src/transport/uart.js` — JSDoc on class and every exported method
- [ ] Tests

### JVM
- [x] `jvm/periph-transport/src/main/java/it/uhde/periph/transport/UARTTransport.java` — Javadoc on class and every public method
- [x] Tests (Linux)

### Rust
- [ ] `rust/periph/src/transport/uart.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Tests (Linux)
- [ ] Tests (ESP32-S3)
