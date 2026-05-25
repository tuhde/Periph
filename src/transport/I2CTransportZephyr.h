#pragma once
#include <zephyr/drivers/i2c.h>
#include "Transport.h"

/** @brief I²C transport for Zephyr RTOS (wraps the i2c driver API).
 *
 * prj.conf must enable CONFIG_I2C=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 * The I²C device node must be enabled in the board's devicetree or an overlay.
 *
 * @param dev  I²C controller device pointer (e.g., DEVICE_DT_GET(DT_NODELABEL(i2c0))).
 * @param addr 7-bit device address.
 */
class I2CTransportZephyr : public Transport {
public:
    I2CTransportZephyr(const struct device *dev, uint8_t addr)
        : _dev(dev), _addr(addr) {}

    /** @brief Send bytes to the device via i2c_write.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override {
        i2c_write(_dev, data, len, _addr);
    }

    /** @brief Read bytes from the device via i2c_read.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override {
        i2c_read(_dev, buf, len, _addr);
    }

    /** @brief Write then read via i2c_write_read (repeated start).
     *  @param data     Register/command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        i2c_write_read(_dev, _addr, data, data_len, buf, buf_len);
    }

private:
    const struct device *_dev;
    uint8_t _addr;
};
