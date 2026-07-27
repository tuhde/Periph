#pragma once
#include <errno.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include <hal/gpio_types.h>

/** @brief SiPo (serial-in/parallel-out shift register) connection for ESP-IDF.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.)
 * whose SER IN / SRCK pins are electrically an SPI MOSI / SCK pair.
 * ESP-IDF has no devicetree, so — like Pico SDK — hardware vs. software
 * SPI is a constructor-overload choice rather than the devicetree
 * choice Zephyr uses:
 *
 * - **Hardware:** pass an `spi_device_handle_t` already added to a bus
 *   via `spi_bus_add_device()` at 1 MHz, mode 0, plus GPIO pin numbers
 *   for RCK/SRCLR/G. `write()` calls `spi_device_polling_transmit()`.
 * - **Software:** pass `ser_in` / `srck` GPIO pin numbers instead of an
 *   `spi_device_handle_t`. `write()` bit-bangs the MSB-first mode-0
 *   loop with `gpio_set_level()`.
 *
 * Both then do `gpio_set_level(rck, 1); gpio_set_level(rck, 0);` to
 * latch. RCK/SRCLR/G are plain GPIO pin numbers in both modes (`-1`
 * disables the optional SRCLR/G pin), the same convention
 * `SiPoConnection` (Arduino) and `SiPoConnectionPicoSDK` already use.
 *
 * Write-only: no `read` or `write_read` exists. This is a custom protocol
 * with no generic byte read/write, so it does not extend the shared
 * Connection base — it carries its own enabled flag directly, gating write().
 *
 * Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 * The consuming project must link `driver`.
 */
class SiPoConnectionESPIDF {
public:
    /** @brief Hardware SPI constructor.
     *
     *  @param spi   SPI device handle, already added to a bus via
     *               `spi_bus_add_device()` at 1 MHz, mode 0, MSB-first.
     *  @param rck   GPIO pin number (`gpio_num_t`) for RCK (register clock).
     *  @param srclr GPIO pin number for SRCLR; `-1` to disable.
     *  @param g     GPIO pin number for G (output enable); `-1` to disable.
     */
    SiPoConnectionESPIDF(spi_device_handle_t spi, gpio_num_t rck,
                        int srclr = -1, int g = -1)
        : _spi(spi), _ser_in(GPIO_NUM_NC), _srck(GPIO_NUM_NC), _rck(rck),
          _srclr(srclr), _g(g), _use_hw_spi(true) {
        _init_gpio();
    }

    /** @brief Software (bit-bang) SPI constructor.
     *
     *  @param ser_in GPIO pin number for SER IN (MOSI equivalent).
     *  @param srck   GPIO pin number for SRCK (SCK equivalent).
     *  @param rck    GPIO pin number for RCK (register clock).
     *  @param srclr  GPIO pin number for SRCLR; `-1` to disable.
     *  @param g      GPIO pin number for G (output enable); `-1` to disable.
     */
    SiPoConnectionESPIDF(gpio_num_t ser_in, gpio_num_t srck, gpio_num_t rck,
                        int srclr = -1, int g = -1)
        : _spi(nullptr), _ser_in(ser_in), _srck(srck), _rck(rck),
          _srclr(srclr), _g(g), _use_hw_spi(false) {
        _init_gpio();
    }

    /** @brief Resume writes. */
    void enable() { _enabled = true; }
    /** @brief Gate write(); it becomes a no-op while disabled. */
    void disable() { _enabled = false; }
    /** @brief Return the current software-gate state. */
    bool isEnabled() const { return _enabled; }

    /** @brief Shift data out MSB-first, then latch it into the output register.
     *
     *  RCK is pulsed HIGH then LOW after the transfer to latch the
     *  shifted data into the storage register that drives the outputs.
     *  No-op if this connection is disabled.
     *
     *  @param data Pointer to the data buffer, one byte per cascaded device.
     *  @param len  Number of bytes to shift out.
     */
    void write(const uint8_t* data, size_t len) {
        if (!_enabled) return;
        if (_use_hw_spi) {
            spi_transaction_t t = {};
            t.tx_buffer = data;
            t.length    = static_cast<size_t>(len) * 8;
            spi_device_polling_transmit(_spi, &t);
        } else {
            for (size_t i = 0; i < len; i++) {
                uint8_t byte = data[i];
                for (int bit = 7; bit >= 0; bit--) {
                    gpio_set_level(_ser_in, (byte >> bit) & 1);
                    gpio_set_level(_srck, 1);
                    gpio_set_level(_srck, 0);
                }
            }
        }
        gpio_set_level(_rck, 1);
        gpio_set_level(_rck, 0);
    }

    /** @brief Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     *  The storage register (and therefore the outputs) is unaffected
     *  until the next `write()`.
     *
     *  @return 0 on success, `-ENODEV` if srclr was not configured.
     */
    int clear() {
        if (_srclr < 0) return -ENODEV;
        gpio_set_level(static_cast<gpio_num_t>(_srclr), 0);
        gpio_set_level(static_cast<gpio_num_t>(_srclr), 1);
        return 0;
    }

    /** @brief Drive G LOW (enabled) or HIGH (disabled).
     *
     *  @param enabled true drives G LOW, letting the storage register
     *         drive the outputs. false drives G HIGH, forcing every
     *         output off without disturbing the storage register's
     *         contents.
     *  @return 0 on success, `-ENODEV` if g was not configured.
     */
    int set_output_enable(bool enabled) {
        if (_g < 0) return -ENODEV;
        gpio_set_level(static_cast<gpio_num_t>(_g), enabled ? 0 : 1);
        return 0;
    }

private:
    spi_device_handle_t _spi;
    gpio_num_t          _ser_in;
    gpio_num_t          _srck;
    gpio_num_t          _rck;
    int                 _srclr;
    int                 _g;
    bool                _use_hw_spi;
    bool                _enabled = true;

    void _init_gpio() {
        if (!_use_hw_spi) {
            gpio_reset_pin(_ser_in);
            gpio_set_direction(_ser_in, GPIO_MODE_OUTPUT);
            gpio_set_level(_ser_in, 0);
            gpio_reset_pin(_srck);
            gpio_set_direction(_srck, GPIO_MODE_OUTPUT);
            gpio_set_level(_srck, 0);
        }
        gpio_reset_pin(_rck);
        gpio_set_direction(_rck, GPIO_MODE_OUTPUT);
        gpio_set_level(_rck, 0);
        if (_srclr >= 0) {
            gpio_reset_pin(static_cast<gpio_num_t>(_srclr));
            gpio_set_direction(static_cast<gpio_num_t>(_srclr), GPIO_MODE_OUTPUT);
            gpio_set_level(static_cast<gpio_num_t>(_srclr), 1);  // SRCLR idle HIGH (inactive)
        }
        if (_g >= 0) {
            gpio_reset_pin(static_cast<gpio_num_t>(_g));
            gpio_set_direction(static_cast<gpio_num_t>(_g), GPIO_MODE_OUTPUT);
            gpio_set_level(static_cast<gpio_num_t>(_g), 0);  // G idle LOW (outputs enabled)
        }
    }
};
