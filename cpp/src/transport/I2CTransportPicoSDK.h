#pragma once
#include <hardware/i2c.h>
#include "Transport.h"

/** @brief I²C transport for the Raspberry Pi Pico SDK (wraps `hardware_i2c`).
 *
 * Bare-metal `pico-sdk` — no Arduino core, no RTOS, no devicetree. One
 * transport instance represents one device on one I²C controller; the
 * controller is initialised by the caller via `i2c_init()` and the GPIO
 * pins via `gpio_set_function()` before being passed in.
 *
 * `write_read` uses the `nostop=true` form of `i2c_write_blocking` so the
 * write phase holds the bus for a repeated START rather than issuing STOP,
 * matching the repeated-start contract every other I²C transport in this
 * repo provides.
 *
 * Requires the `PICO_SDK_PATH` environment variable and `pico_sdk_init()`
 * in the consuming CMake project, analogous to `ZEPHYR_BASE`/`west build`
 * for Zephyr. Link against `hardware_i2c`.
 *
 * @param i2c  I²C controller pointer (`i2c0` or `i2c1`).
 * @param addr 7-bit device address.
 */
class I2CTransportPicoSDK : public Transport {
public:
    I2CTransportPicoSDK(i2c_inst_t* i2c, uint8_t addr)
        : _i2c(i2c), _addr(addr) {}

    /** @brief Send bytes to the device.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override {
        i2c_write_blocking(_i2c, _addr, data, len, false);
    }

    /** @brief Read bytes from the device.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override {
        i2c_read_blocking(_i2c, _addr, buf, len, false);
    }

    /** @brief Write then read with a repeated start between phases.
     *
     *  The `nostop=true` 4th argument to `i2c_write_blocking` holds the bus
     *  for the following `i2c_read_blocking` so the device sees a repeated
     *  START rather than a STOP+START pair.
     *
     *  @param data     Register/command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        i2c_write_blocking(_i2c, _addr, data, data_len, true);
        i2c_read_blocking(_i2c, _addr, buf, buf_len, false);
    }

protected:
    /** @brief Underlying I²C controller pointer, exposed so derived
     *         transports (e.g. SMBus with PEC) can issue raw transfers
     *         that the public write/read/write_read API does not cover. */
    i2c_inst_t* i2c_inst() const { return _i2c; }
    /** @brief 7-bit I²C address this transport was constructed with. */
    uint8_t i2c_address() const { return _addr; }

private:
    i2c_inst_t* _i2c;
    uint8_t     _addr;
};
