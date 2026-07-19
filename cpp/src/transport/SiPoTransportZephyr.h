#pragma once
#include <errno.h>
#include <zephyr/drivers/spi.h>
#include <zephyr/drivers/gpio.h>

/** @brief SiPo (serial-in/parallel-out shift register) transport for Zephyr RTOS.
 *
 * Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
 * SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Hardware vs.
 * software SPI is a devicetree choice, not a code-path choice: point dev at
 * a real SPI controller node for hardware, or at a spi-bitbang-compatible
 * controller node for software — spi_write()/spi_config look identical
 * either way. RCK — and, if configured, SRCLR/G — are always plain
 * gpio_dt_spec outputs.
 *
 * Write-only: there is no read() or write_read().
 *
 * prj.conf must enable CONFIG_SPI=y, CONFIG_GPIO=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 *
 * @param dev    SPI controller device pointer (hardware or spi-bitbang node).
 * @param config spi_config specifying clock, operation flags (mode 0, MSB-first).
 * @param rck    gpio_dt_spec for RCK (GPIO_OUTPUT).
 * @param srclr  gpio_dt_spec for SRCLR; pass an unpopulated spec (.port == nullptr) to disable.
 * @param g      gpio_dt_spec for G; pass an unpopulated spec (.port == nullptr) to disable.
 */
class SiPoTransportZephyr {
public:
    SiPoTransportZephyr(const struct device *dev, const struct spi_config &config,
                        const struct gpio_dt_spec &rck,
                        const struct gpio_dt_spec &srclr = {},
                        const struct gpio_dt_spec &g = {})
        : _dev(dev), _config(config), _rck(rck), _srclr(srclr), _g(g)
    {
        gpio_pin_configure_dt(&_rck, GPIO_OUTPUT_LOW);
        if (_srclr.port != nullptr) gpio_pin_configure_dt(&_srclr, GPIO_OUTPUT_HIGH);
        if (_g.port != nullptr)     gpio_pin_configure_dt(&_g, GPIO_OUTPUT_LOW);
    }

    /** @brief Shift data out MSB-first via spi_write(), then latch it into the output register.
     *
     *  RCK is pulsed HIGH then LOW after the transfer to latch the shifted
     *  data into the storage register that drives the outputs.
     *
     *  @param data Pointer to the data buffer, one byte per cascaded device.
     *  @param len  Number of bytes to shift out.
     */
    void write(const uint8_t* data, size_t len) {
        struct spi_buf tx_buf = { .buf = const_cast<uint8_t*>(data), .len = len };
        struct spi_buf_set tx = { .buffers = &tx_buf, .count = 1 };
        spi_write(_dev, &_config, &tx);
        gpio_pin_set_dt(&_rck, 1);
        gpio_pin_set_dt(&_rck, 0);
    }

    /** @brief Pulse SRCLR LOW then HIGH to clear the shift register.
     *
     *  The storage register (and therefore the outputs) is unaffected until
     *  the next write().
     *
     *  @return 0 on success, -ENODEV if srclr was not configured.
     */
    int clear() {
        if (_srclr.port == nullptr) return -ENODEV;
        gpio_pin_set_dt(&_srclr, 0);
        gpio_pin_set_dt(&_srclr, 1);
        return 0;
    }

    /** @brief Drive G LOW (enabled) or HIGH (disabled).
     *
     *  @param enabled true drives G LOW, letting the storage register drive
     *         the outputs. false drives G HIGH, forcing every output off
     *         without disturbing the storage register's contents.
     *  @return 0 on success, -ENODEV if g was not configured.
     */
    int set_output_enable(bool enabled) {
        if (_g.port == nullptr) return -ENODEV;
        gpio_pin_set_dt(&_g, enabled ? 0 : 1);
        return 0;
    }

private:
    const struct device *_dev;
    struct spi_config    _config;
    struct gpio_dt_spec  _rck;
    struct gpio_dt_spec  _srclr;
    struct gpio_dt_spec  _g;
};
