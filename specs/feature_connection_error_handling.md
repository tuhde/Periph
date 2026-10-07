# Feature Plan: Connection Error Handling

**Status:** Plan — awaiting approval, then implementation
**Branch:** `plan/connection-error-handling`
**Origin:** audit of how every connection layer handles the return values of the underlying bus / OS / HAL calls.
**Contract (already specified):** `specs/transport_i2c.md` § Error Handling — NACK and bus timeout must "raise `OSError` / return error code". This plan brings the implementations into line with it; it does not change the contract.

---

## 1. Audit result

| Platform | State |
|---|---|
| Rust | Good. Only two small items (§3.7). |
| Python (MicroPython, CircuitPython, Linux) | Good. Bus layers raise `OSError`; UART checks short read/write. |
| Go (Linux, TinyGo) | Mostly good. Items in §3.6. |
| JVM | Partial. Items in §3.4. |
| Node.js | Partial. Items in §3.5. |
| C++ Linux | Partial. I²C and NeoPixel only `perror`; short transfers unchecked. §3.2 |
| C++ Arduino / Zephyr / ESP-IDF / Pico SDK | Poor. Return codes ignored on every bus and GPIO call. §3.1 |

## 2. Principles

1. **A failed transfer must never look like a successful one.** No stale buffer, no zero-filled "valid" data without an error signal.
2. **A short transfer is a failure** (`n != len`), not only `n < 0`.
3. **Keep each platform's idiom.** Hosts raise/throw/return `error`; embedded C++ (no exceptions) records a sticky error code.
4. **No public API break for chip drivers.** Chip drivers keep calling `write` / `read` / `write_read`; error propagation arrives through the connection (exception or `lastError()`).
5. **Preserve the cause.** Include `errno` / driver error code in the message or code.

## 3. Work items

### 3.1 C++ embedded (highest priority)

Design (one decision, applies to all four targets):

- Protected hooks `_write` / `_read` / `_write_read` return `int` (`0` = ok, negative = errno-style / platform code) instead of `void`.
- `Connection` public `write` / `read` / `write_read` stay `void`; they store the result in a sticky `_lastError` and expose `int lastError() const` and `void clearError()`. Same idea as the existing `SMBusConnection::valid()`, generalised.
- On a failed read, zero-fill the destination so drivers never consume stale data.
- Linux (exceptions available) keeps throwing `std::runtime_error`; it additionally records `lastError()` for API parity.
- Chip drivers are unchanged. Examples (complete / demo) gain one `lastError()` check where sensible; the Doxygen of `Connection` documents the contract.

Per-target changes:

| Target | Calls to check |
|---|---|
| Arduino | `endTransmission()` result (0 = ok); `requestFrom()` count must equal requested; applies to `I2CConnection.cpp` and `SMBusConnection.cpp` (+ PEC) |
| Zephyr | `i2c_write/read/write_read`, `spi_write/read/transceive`, `gpio_pin_configure_dt`, `gpio_add_callback`, `gpio_pin_interrupt_configure_dt` |
| ESP-IDF | `esp_err_t` of `i2c_master_*`, `spi_device_polling_transmit`, `gpio_isr_handler_add`, `gpio_set_intr_type`, `gpio_install_isr_service` (tolerate `ESP_ERR_INVALID_STATE`) |
| Pico SDK | `i2c_write_blocking` / `i2c_read_blocking` (byte count vs `PICO_ERROR_GENERIC` / `PICO_ERROR_TIMEOUT`), `spi_*_blocking` count; also SMBus PEC read paths |

Also: SMBus PEC failure sets `lastError()` (keep `valid()` as an alias), NeoPixel / SiPo writers propagate the same way.

### 3.2 C++ Linux

- `I2CConnectionLinux.cpp` (`_write`, `_read`, `_write_read`) and `NeoPixelConnectionLinux.cpp`: replace `perror` with `throw std::runtime_error(... strerror(errno))`, matching SPI / SMBus / UART.
- `::read` / `::write` in I²C and SMBus: treat `n != len` as an error (retry-free; I²C char device transfers are atomic).
- Add unit tests with a fake fd (pipe / socketpair) for the short-transfer case.

### 3.3 Python

- No functional gap. Optional: check `uart.write()` return value on MicroPython / CircuitPython (`None` or `< len` → `OSError`).

### 3.4 JVM (Java connection module)

- `I2CConnection._write` / `_read`: error when `n != len`; same in `UARTConnection` write (`n != data.length`, loop or fail).
- Capture `errno` via `Linker.Option.captureCallState("errno")` in every downcall (I²C, SPI, UART, NeoPixel, SiPo, SMBus, pins) and put it in the `IOException` message. Factor into one helper in `AbstractConnection`.
- `I2CConnection._writeRead`: either implement a true repeated start with `I2C_RDWR` (matches the other platforms) or document the limitation prominently; recommended: implement `I2C_RDWR`.

### 3.5 Node.js

- `i2c.js`, `smbus.js`: check the byte counts returned by `i2cReadSync` / `i2cWriteSync`; throw on mismatch.
- `i2c.js` `_writeRead`: uses `data[0]` only, which truncates multi-byte register addresses when `regBytes > 1`; use a combined `i2cTransfer`-style call (or write + read) with the full `data`.
- `spi.js`, `neopixel.js`, `sipo.js`: verify the `transferSync` result (`bytesWritten` / `bytesRead` where the binding reports them).
- `async` wrappers keep throwing (rejecting) — no API change.

### 3.6 Go

- `uart_linux.go` `Write`: loop until all bytes are written, return the `TCSBRK` error, return an error on `n < len`.
- `uart_tinygo.go` `Write`: return an error when `n < len(data)`.
- `dhtxx_linux.go`: stop discarding `readGpioLine` / `setGpioLine` errors; return them so a GPIO fault is not reported as a sensor timeout.

### 3.7 Rust

- `neopixel.rs` `encode`: `resize_default(...).ok()` leaves an empty buffer when the payload exceeds the 768-byte `heapless::Vec`, so nothing is sent without an error. Return an error (`NeoPixelError::TooLong`) from `write` / `write_ext`, or size the buffer by const generic.
- `hx711.rs::new`: `let _ = pd_sck.set_low()` — return `Result` (breaking constructor change: decide, or document and keep).

## 4. Order of work

1. **Contract + C++ base** (`Connection.h`, `RegisterConnection.h`, Doxygen, `specs/feature_connection_design.md` addendum, `transport_*.md` error table).
2. **C++ Linux** (§3.2) — small, unblocks tests on CI.
3. **C++ embedded**, one target per commit: Arduino → Pico SDK → Zephyr → ESP-IDF.
4. **JVM** (§3.4), **Node.js** (§3.5).
5. **Go** (§3.6), **Rust** (§3.7), **Python** (§3.3).
6. Cross-language **hardware-in-loop** check: unplug / NACK a device and confirm every language surfaces an error (see `specs/hil_conformance_checklist.md`).

## 5. Testing

- Extend each language's mock connection (`I2CConnectionMock`, `i2c_mock.py`, …) with a **fault-injection** option (`failNext(code)` / short-read) and add a conformance test per platform asserting that the error surfaces (exception / `error` / `lastError()`).
- C++ host build compiles with `-Wunused-result`; embedded targets are build-checked in CI (Arduino, Zephyr, ESP-IDF, Pico SDK jobs).
- Run the existing unit suites for Python, Node, JVM, Go and Rust; no chip-driver test should change.

## 6. Open questions for the maintainer

1. C++ embedded: sticky `lastError()` (recommended, no API break) vs. changing the public `write/read` to return `int` (breaks every chip driver).
2. Rust `HX711::new` returning `Result`: accept the breaking change?
3. JVM `_writeRead`: implement true repeated start now, or leave as a documented limitation?
4. Issue tracking: one umbrella issue plus one sub-issue per language, or a single PR per language?
