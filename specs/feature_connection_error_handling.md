# Feature Plan: Connection Error Handling

**Status:** Plan — re-audited against `main` on 2026-11-25, awaiting approval, then implementation
**Branch:** `plan/connection-error-handling`
**Origin:** audit of how every connection layer handles the return values of the underlying bus / OS / HAL calls (first written 2026-10-07; re-audited after 425 further commits).
**Contract (already specified):** `specs/transport_i2c.md` § Error Handling — NACK and bus timeout must "raise `OSError` / return error code". This plan brings the implementations into line with it; it does not change the contract.

---

## 0. What changed since the first audit

- **C++ already has a failure signal: `Connection::ok()`** (bool, reset to `true` at the start of every `write` / `read` / `write_read`, set by subclasses through `_setOk()`). The earlier proposal for a sticky `lastError()` is **dropped**; this plan extends `ok()` to the remaining buses instead. 33 chip drivers already consume `ok()` (retry on NACK), and `I2CConnectionMock` has fault injection (`failNextWrites`, failing reads).
- **I²C is done on every C++ platform** (Arduino, Linux, Zephyr, ESP-IDF, Pico SDK, STM32Cube): each reports `ok()` from the platform return value and checks the byte count. STM32Cube additionally keeps `last_status()`, `error_count()` and a bus-reset recovery path.
- **New since October:** STM32Cube platform, `DisplayConnection`, `RegisterConnection`, DHTxx / HX711 / SiPo connections, Go TinyGo target. The audit below covers them.
- The Node.js, JVM and Python I²C mocks also have fault injection; the Python SPI mock does not, and Go / Rust have no connection mock with it that I found (see §5).

## 1. Audit result

`done` = return values are checked and surfaced; `gap` = still open (item reference in §3). The Go and Rust "done" cells were judged from the error-returning signatures and the SPI/I²C ioctl error checks, not from a line-by-line read of every call.

| Platform | I²C | SMBus | SPI | UART | GPIO / other |
|---|---|---|---|---|---|
| C++ Arduino | done (`ok()`) | PEC via separate `valid()` (gap §3.1) | no error API in `SPIClass` — n/a | no error API — n/a | `digitalWrite` has no result — n/a |
| C++ Linux | `ok()` + `perror` (gap §3.2) | throws, but `< 0` only (gap §3.2) | throws | throws; short write ok | NeoPixel `perror` only (gap §3.2) |
| C++ Zephyr | done | return codes dropped, PEC via `valid()` (gap §3.1) | `spi_write/transceive` result ignored (gap §3.1) | ISR driven; `uart_fifo_fill` count handled — n/a | `gpio_*` results ignored (gap §3.1) |
| C++ ESP-IDF | done | inherits I²C, PEC via `valid()` (gap §3.1) | `spi_device_polling_transmit` ignored (gap §3.1) | `uart_write_bytes` / `uart_read_bytes` count ignored (gap §3.1) | `gpio_install_isr_service` etc. ignored (gap §3.1) |
| C++ Pico SDK | done | inherits I²C, PEC via `valid()` (gap §3.1) | `spi_*_blocking` count ignored (gap §3.1) | `uart_write_blocking` has no result; read is poll-based — n/a | — |
| C++ STM32Cube | done (`last_status()`) | inherits I²C, PEC via `valid()` (gap §3.1) | `HAL_SPI_*` status ignored (gap §3.1) | `HAL_UART_*` status ignored (gap §3.1) | — |
| Python | done | done | done | Linux checks short write; MicroPython / CircuitPython do not (gap §3.3, optional) | — |
| JVM | `n < 0` only; short transfers pass (gap §3.4) | — | — | `n < 0` only (gap §3.4) | errno captured only in `I2CBus` (gap §3.4) |
| Node.js | byte counts unchecked (gap §3.5) | same (gap §3.5) | `transferSync` result unchecked (gap §3.5) | — | — |
| Go | done | — | done | Linux: short write and `TCSBRK` ignored; TinyGo: count ignored (gap §3.6) | `dhtxx_linux.go` drops GPIO errors (gap §3.6) |
| Rust | done | done | done | done | NeoPixel buffer overflow silent; `HX711::new` swallows error (gap §3.7) |

## 2. Principles

1. **A failed transfer must never look like a successful one.** No stale buffer, no zero-filled "valid" data without an error signal.
2. **A short transfer is a failure** (`n != len`), not only `n < 0`.
3. **Keep each platform's idiom.** Hosts raise/throw/return `error`; embedded C++ (no exceptions) reports through `Connection::ok()`.
4. **No public API break for chip drivers.** Chip drivers keep calling `write` / `read` / `write_read`; error propagation arrives through the connection (exception or `ok()`).
5. **Preserve the cause.** Include `errno` / driver error code in the message where the platform offers one.

## 3. Work items

### 3.1 C++ embedded — extend `ok()` beyond I²C

- **SPI** (`SPIConnection{Zephyr,ESPIDF,PicoSDK,STM32Cube}.h`): call `_setOk(...)` from the platform result: `spi_write` / `spi_transceive` (`== 0`), `spi_device_polling_transmit` (`== ESP_OK`), `spi_write_blocking` / `spi_read_blocking` / `spi_write_read_blocking` (count `== len`), `HAL_SPI_*` (`== HAL_OK`). Zero-fill the destination on a failed read so drivers never consume stale data (matches `Connection::read` for a disabled connection).
- **UART** (`UARTConnection{ESPIDF,STM32Cube}.h`): `uart_write_bytes` / `uart_read_bytes` count, `HAL_UART_*` status → `_setOk`. Zephyr and Pico are ISR / poll based; `ok()` is `false` only when a read times out with fewer bytes than requested.
- **SMBus** (`SMBusConnection{Zephyr,ESPIDF,PicoSDK,STM32Cube}.h` and Arduino): Zephyr drops the `i2c_write` / `i2c_read` return values; ESP-IDF, Pico and STM32Cube inherit the I²C result but overwrite state with `_valid`. Make a PEC mismatch call `_setOk(false)` in addition to the existing `valid()`, which stays as an alias so no driver changes.
- **GPIO** (`InputPin*` / `OutputPin*`): check `gpio_pin_configure_dt`, `gpio_add_callback`, `gpio_pin_interrupt_configure_dt` (Zephyr), `gpio_isr_handler_add`, `gpio_set_intr_type`, `gpio_install_isr_service` (ESP-IDF; tolerate `ESP_ERR_INVALID_STATE`). Pins have no `Connection`, so a failure cannot use `ok()`: either add a `bool ok()` to `InputPin` / `OutputPin` (matching the Connection contract) or leave GPIO setup failures as a documented limitation. Decision in §6.
- NeoPixel / SiPo writers propagate through the same `_setOk` on SPI-based variants.
- Doxygen on `Connection::ok()` documents the contract and which connections report it; `specs/feature_connection_design.md` gets a short addendum and each `transport_*.md` Error Handling table gains the embedded `ok()` row.

### 3.2 C++ Linux

- `I2CConnectionLinux.cpp`: `_write` / `_read` / `_write_read` already set `ok()` and check the byte count; they still `perror` on `< 0`. Keep `ok()` (chip drivers depend on the retry behaviour) and drop the `perror` noise in favour of a one-line stderr message that includes `strerror(errno)`; no throw, because drivers survive NACKs through `ok()`.
- `NeoPixelConnectionLinux.cpp`: `SPI_IOC_MESSAGE` failure only `perror`s. Throw `std::runtime_error(... strerror(errno))`, matching SPI / SiPo / UART.
- `SMBusConnectionLinux.cpp`: treats only `< 0` as an error; treat `n != len` as an error as well (I²C char device transfers are atomic).
- Add unit tests with a fake fd (pipe / socketpair) for the short-transfer case.

### 3.3 Python

- No functional gap on I²C, SMBus, SPI and Linux UART.
- Optional: check `uart.write()` return value on MicroPython / CircuitPython (`None` or `< len` → `OSError`).

### 3.4 JVM (Java connection module)

- `I2CConnection._write` / `_read` and `UARTConnection._write`: error when `n != len` (UART: loop or fail), not only `n < 0`.
- `I2CBus` already captures `errno` through `Linker.Option.captureCallState("errno")`. Factor that into one helper in `AbstractConnection` and apply it to the `I2CConnection`, `SPIConnection`, `UARTConnection`, `NeoPixelConnection`, `SiPoConnection`, `SMBusConnection` and pin downcalls so the `IOException` message carries `errno`.
- `I2CConnection._writeRead` is still two separate transactions (stop then start, documented in the class Javadoc). Recommended: implement a true repeated start with `I2C_RDWR` (already used in `I2CBus` for probing, so the ioctl plumbing exists).

### 3.5 Node.js

- `i2c.js`, `smbus.js`: check the byte counts returned by `i2cReadSync` / `i2cWriteSync`; throw on mismatch.
- `i2c.js` `_writeRead` (and `smbus.js`): only `data[0]` is sent, which truncates multi-byte register addresses when `regBytes > 1`. Use a combined transfer or write + read with the full `data`.
- `spi.js`, `neopixel.js`, `sipo.js`: verify the `transferSync` result where the binding reports it.
- `async` wrappers keep throwing (rejecting) — no API change.

### 3.6 Go

- `uart_linux.go` `Write`: loop until all bytes are written (`unix.Write` count is discarded), check the `TCSBRK` result (currently `_, _, _ =`), return an error on `n < len`.
- `uart_tinygo.go` `Write`: `n, err := t.uart.Write(data); _ = n` — return an error when `n < len(data)`.
- `dhtxx_linux.go`: stop discarding `readGpioLine` / `setGpioLine` errors (`_ =` and `v, _ :=`); return them so a GPIO fault is not reported as a sensor timeout.

### 3.7 Rust

- `neopixel.rs` `encode`: `resize_default(...).ok()` leaves an empty buffer when the payload exceeds the 768-byte `heapless::Vec`, so nothing is sent without an error. Return an error (`NeoPixelError::TooLong`) from `write` / `write_ext`, or size the buffer by const generic.
- `hx711.rs::new`: `let _ = pd_sck.set_low()` — return `Result` (breaking constructor change: decide, or document and keep). Every other `set_low` in the file already maps to `HX711Error::Clock`.

## 4. Order of work

1. **Contract + C++ base** — Doxygen on `Connection::ok()`, `feature_connection_design.md` addendum, `transport_*.md` error table.
2. **C++ Linux** (§3.2) — small, unblocks tests on CI.
3. **C++ embedded** (§3.1), one platform per commit: Arduino (SMBus) → Pico SDK → Zephyr → ESP-IDF → STM32Cube. Each must be built on its own SDK before merging (`cpp/scripts/build-all.sh`; CI builds every app on all five platforms).
4. **JVM** (§3.4), **Node.js** (§3.5).
5. **Go** (§3.6), **Rust** (§3.7), **Python** (§3.3).
6. Cross-language **hardware-in-loop** check: unplug / NACK a device and confirm every language surfaces an error (see `specs/hil_conformance_checklist.md`).

## 5. Testing

- C++ `I2CConnectionMock` and the Node.js, JVM and Python I²C mocks already offer fault injection. Add the same to the mocks that lack it (Python SPI mock; Go and Rust, where I found no connection mock with it) and a conformance test per platform asserting the error surfaces (exception / `error` / `ok()`).
- C++ host build compiles with `-Wunused-result`; embedded targets are build-checked in CI (Arduino, Zephyr, ESP-IDF, Pico SDK, STM32Cube).
- Run the existing unit suites for Python, Node, JVM, Go and Rust; no chip-driver test should change.

## 6. Open questions for the maintainer

1. ~~C++ embedded: sticky `lastError()` vs. changing the public API.~~ **Resolved:** the existing `ok()` is the mechanism; no API break.
2. GPIO setup failures on embedded C++ (`InputPin` / `OutputPin` have no `Connection`): add `ok()` to the pin classes, or leave as a documented limitation?
3. Rust `HX711::new` returning `Result`: accept the breaking change?
4. JVM `_writeRead`: implement true repeated start now (recommended, `I2C_RDWR` is already used in `I2CBus`), or leave as a documented limitation?
5. Issue tracking: one umbrella issue plus one sub-issue per language, or a single PR per language?
