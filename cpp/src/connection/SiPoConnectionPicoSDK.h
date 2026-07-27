#pragma once
#include <errno.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>

/** @brief SiPo (serial-in/parallel-out shift register) connection for the
 *         Raspberry Pi Pico SDK.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.)
 * whose SER IN / SRCK pins are electrically an SPI MOSI / SCK pair. Two
 * constructor modes (bare-metal `pico-sdk`, no Arduino core, no RTOS):
 *
 * - **Hardware:** pass an `spi_inst_t*` plus RCK/SRCLR/G pin numbers.
 *   The connection shifts data via `spi_write_blocking` and latches with
 *   `gpio_put()` on RCK.
 * - **Software:** pass `ser_in` and `srck` pin numbers instead of an
 *   `spi_inst_t*`. The connection bit-bangs the MSB-first mode-0 loop
 *   directly with `gpio_put()`.
 *
 * Write-only: no `read` or `write_read` exists. This is a custom protocol
 * with no generic byte read/write, so it does not extend the shared
 * Connection base — it carries its own enabled flag directly, gating write().
 *
 * Requires the `PICO_SDK_PATH` environment variable and `pico_sdk_init()`
 * in the consuming CMake project. Link against `hardware_spi` (hardware
 * mode) and `hardware_gpio` (both modes).
 */
class SiPoConnectionPicoSDK {
public:
    /** @brief Hardware SPI constructor.
     *
     *  @param spi    SPI controller pointer (`spi0` or `spi1`); caller has
     *                already configured it for 1 MHz, mode 0, MSB-first.
     *  @param rck    GPIO pin number for RCK (register clock).
     *  @param srclr  GPIO pin number for SRCLR; `-1` to disable.
     *  @param g      GPIO pin number for G (output enable); `-1` to disable.
     */
    SiPoConnectionPicoSDK(spi_inst_t* spi, uint rck, int srclr = -1, int g = -1)
        : _spi(spi), _ser_in(0), _srck(0), _rck(rck), _srclr(srclr), _g(g),
          _use_hw_spi(true) {
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
    SiPoConnectionPicoSDK(uint ser_in, uint srck, uint rck, int srclr = -1, int g = -1)
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
            spi_write_blocking(_spi, data, len);
        } else {
            for (size_t i = 0; i < len; i++) {
                uint8_t byte = data[i];
                for (int bit = 7; bit >= 0; bit--) {
                    gpio_put(_ser_in, (byte >> bit) & 1);
                    gpio_put(_srck, 1);
                    gpio_put(_srck, 0);
                }
            }
        }
        gpio_put(_rck, 1);
        gpio_put(_rck, 0);
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
        gpio_put(_srclr, 0);
        gpio_put(_srclr, 1);
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
        gpio_put(_g, enabled ? 0 : 1);
        return 0;
    }

private:
    spi_inst_t* _spi;
    uint        _ser_in;
    uint        _srck;
    uint        _rck;
    int         _srclr;
    int         _g;
    bool        _use_hw_spi;
    bool        _enabled = true;

    void _init_gpio() {
        if (!_use_hw_spi) {
            gpio_init(_ser_in);
            gpio_set_dir(_ser_in, GPIO_OUT);
            gpio_put(_ser_in, 0);
            gpio_init(_srck);
            gpio_set_dir(_srck, GPIO_OUT);
            gpio_put(_srck, 0);
        }
        gpio_init(_rck);
        gpio_set_dir(_rck, GPIO_OUT);
        gpio_put(_rck, 0);
        if (_srclr >= 0) {
            gpio_init(_srclr);
            gpio_set_dir(_srclr, GPIO_OUT);
            gpio_put(_srclr, 1);  // SRCLR idle HIGH (inactive)
        }
        if (_g >= 0) {
            gpio_init(_g);
            gpio_set_dir(_g, GPIO_OUT);
            gpio_put(_g, 0);  // G idle LOW (outputs enabled)
        }
    }
};
