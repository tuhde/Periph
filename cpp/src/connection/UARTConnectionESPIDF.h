#pragma once
#include <driver/uart.h>
#include <driver/gpio.h>
#include "Connection.h"

/** @brief UART connection for ESP-IDF (`driver/uart.h`).
 *
 * Wraps ESP-IDF's blocking UART API. Constructor accepts a
 * `uart_port_t` already installed via `uart_driver_install()` and
 * configured via `uart_param_config()` / `uart_set_pin()`, plus an
 * optional DE pin number (`-1` disables RS-485 mode) — same convention
 * as the Arduino and Linux (C++) connections.
 *
 * The 1000 ms timeout matches Arduino's `HardwareSerial` default and
 * Zephyr's 1 s read deadline, keeping behavior consistent across
 * platforms. `uart_wait_tx_done()` blocks until the hardware FIFO is
 * actually empty — safe to deassert DE immediately after, unlike
 * Pico SDK which has no such call and must fall back to a baud-rate
 * delay.
 *
 * `available()` uses `uart_get_buffered_data_len()` and reports an
 * exact byte count — no platform limitation here, unlike Pico SDK's
 * boolean-only `uart_is_readable()`.
 *
 * Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 * The consuming project must link `driver` and provide a configured
 * `uart_port_t` to construct the connection.
 *
 * @param port   UART port number (`UART_NUM_0`, `UART_NUM_1`,
 *               `UART_NUM_2`), already installed via
 *               `uart_driver_install()` and configured via
 *               `uart_param_config()`.
 * @param de_pin RS-485 DE GPIO pin (`gpio_num_t`); `-1` disables
 *               RS-485 mode.
 * @param intPin Optional InputPin for INT-line delivery.
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class UARTConnectionESPIDF : public Connection {
public:
    UARTConnectionESPIDF(uart_port_t port, int de_pin = -1,
                         InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _port(port), _de_pin(static_cast<gpio_num_t>(de_pin))
    {
        if (_de_pin != GPIO_NUM_NC) {
            gpio_reset_pin(_de_pin);
            gpio_set_direction(_de_pin, GPIO_MODE_OUTPUT);
            gpio_set_level(_de_pin, 0);  // DE idles LOW (receive enabled)
        }
    }

    /** @brief Number of bytes currently queued in the RX FIFO, available
     *         to `read()` without blocking.
     *
     *  Uses `uart_get_buffered_data_len()` to report an exact byte count.
     *
     *  @return Byte count currently queued.
     */
    size_t available() const override {
        size_t n = 0;
        uart_get_buffered_data_len(_port, &n);
        return n;
    }

protected:
    /** @brief Transmit bytes; in RS-485 mode assert DE, transmit, then
     *         deassert DE after the FIFO drains.
     *
     *  `uart_write_bytes()` copies data into the TX FIFO and returns;
     *  `uart_wait_tx_done()` blocks until the FIFO is empty, so it is
     *  safe to deassert DE immediately after.
     *
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override {
        if (_de_pin != GPIO_NUM_NC) gpio_set_level(_de_pin, 1);
        uart_write_bytes(_port, data, len);
        uart_wait_tx_done(_port, pdMS_TO_TICKS(1000));
        if (_de_pin != GPIO_NUM_NC) gpio_set_level(_de_pin, 0);
    }

    /** @brief Receive @p len bytes; blocks up to a 1000 ms total timeout.
     *
     *  On timeout, fewer than @p len bytes may have been written to
     *  @p buf — call `available()` to check how many bytes are
     *  buffered before deciding how much of @p buf is valid.
     *
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override {
        int got = uart_read_bytes(_port, buf, len, pdMS_TO_TICKS(1000));
        (void)got;
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
    uart_port_t   _port;
    gpio_num_t    _de_pin;
};
