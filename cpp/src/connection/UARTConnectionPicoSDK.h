#pragma once
#include <hardware/uart.h>
#include <hardware/gpio.h>
#include <pico/time.h>
#include "Connection.h"

/** @brief UART connection for the Raspberry Pi Pico SDK (wraps `hardware_uart`).
 *
 * Bare-metal `pico-sdk` — no Arduino core, no RTOS. One connection instance
 * represents one device on one UART peripheral; the peripheral is
 * initialised by the caller via `uart_init()` and the GPIO pin functions
 * via `gpio_set_function()` before being passed in.
 *
 * `pico-sdk` exposes no TX-drain / TX-complete call and no RX byte-count
 * API — only `uart_is_writable()` / `uart_is_readable()`, which report a
 * single byte's readiness as a boolean, not a count. Two consequences:
 *
 * 1. `write` blocks until each byte has been accepted into the transmit
 *    FIFO (`uart_is_writable` per byte). RS-485 DE timing is approximated
 *    by a `busy_wait_us` based on baud rate and byte count, since there is
 *    no FIFO-empty / TX-complete callback to wait on.
 * 2. `available` returns 1-or-0 rather than a precise count — callers that
 *    need exact counts (e.g. streaming protocols) cannot get them on
 *    pico-sdk and must consume one byte at a time.
 *
 * `read` blocks up to a 1 s deadline, polling `uart_is_readable()` every
 * 1 ms via `sleep_ms` to avoid spinning a CPU core.
 *
 * Requires the `PICO_SDK_PATH` environment variable and `pico_sdk_init()`
 * in the consuming CMake project. Link against `hardware_uart`,
 * `hardware_gpio`, and `pico_time`.
 *
 * @param uart   UART peripheral pointer (`uart0` or `uart1`).
 * @param baud   Baud rate in bits/s.
 * @param de_pin RS-485 DE GPIO pin number; `-1` disables RS-485 mode.
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class UARTConnectionPicoSDK : public Connection {
public:
    UARTConnectionPicoSDK(uart_inst_t* uart, uint baud, int de_pin = -1,
                          InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _uart(uart), _baud(baud), _de_pin(de_pin)
    {
        if (_de_pin >= 0) {
            gpio_init(_de_pin);
            gpio_set_dir(_de_pin, GPIO_OUT);
            gpio_put(_de_pin, 0);  // DE idles LOW (receive enabled)
        }
    }

    /** @brief 1-or-0 byte count of currently-buffered RX data.
     *
     *  `pico-sdk` has no byte-count API — only `uart_is_readable()`, a
     *  boolean. Returns 1 when at least one byte is ready, 0 otherwise.
     *  Callers that need exact counts must consume one byte at a time.
     *
     *  @return 1 if a byte is ready, 0 otherwise.
     */
    size_t available() const override {
        return uart_is_readable(_uart) ? 1 : 0;
    }

protected:
    /** @brief Transmit bytes; in RS-485 mode assert DE, transmit, then
     *         deassert DE after a baud-rate-derived delay.
     *
     *  `uart_write_blocking` already waits for each byte to be accepted
     *  into the transmit FIFO. Because pico-sdk has no TX-complete
     *  callback, the DE deassertion is followed by a short busy-wait
     *  proportional to the number of bytes (10 bit-times per byte + 1
     *  bit-time margin) so the last byte is fully shifted out before
     *  releasing the bus.
     *
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override {
        if (_de_pin >= 0) gpio_put(_de_pin, 1);
        uart_write_blocking(_uart, data, len);
        if (_de_pin >= 0) {
            // 10 bit-times per byte (start + 8 data + stop) + 1 bit-time margin
            uint32_t total_bits = static_cast<uint32_t>(len) * 10u + 1u;
            busy_wait_us((total_bits * 1'000'000u) / static_cast<uint32_t>(_baud));
            gpio_put(_de_pin, 0);
        }
    }

    /** @brief Receive @p len bytes; blocks (sleeping 1 ms between polls)
     *         until all bytes have arrived or a 1 s total timeout expires.
     *
     *  On timeout, fewer than @p len bytes may have been written to
     *  @p buf — call available() to check how many bytes are buffered
     *  before deciding how much of @p buf is valid.
     *
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override {
        size_t got = 0;
        absolute_time_t deadline = make_timeout_time_ms(1000);
        while (got < len) {
            if (uart_is_readable(_uart)) {
                buf[got++] = uart_getc(_uart);
            } else if (absolute_time_diff_us(get_absolute_time(), deadline) <= 0) {
                break;
            } else {
                sleep_ms(1);
            }
        }
    }

    /** @brief Transmit bytes then receive @p buf_len bytes.
     *
     *  In RS-485 mode DE is asserted only during the transmit phase.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        _write(data, data_len);
        _read(buf, buf_len);
    }

private:
    uart_inst_t* _uart;
    uint         _baud;
    int          _de_pin;
};
