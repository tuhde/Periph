#pragma once
#include <driver/i2c_master.h>
#include "Transport.h"

/** @brief I²C transport for ESP-IDF (driver-ng `i2c_master.h`).
 *
 * Wraps ESP-IDF's driver-ng I²C master API (ESP-IDF ≥5.1; the legacy
 * `driver/i2c.h` is out of scope). Constructor accepts an
 * already-configured `i2c_master_dev_handle_t` — the caller creates the
 * bus (`i2c_new_master_bus`) and adds the device
 * (`i2c_master_bus_add_device`) before constructing the transport, the
 * same division of responsibility Zephyr's devicetree-configured
 * `const struct device *` uses.
 *
 * `write_read` is a single `i2c_master_transmit_receive` call that
 * performs the write-then-repeated-start-read internally — no separate
 * STOP/repeated-START management needed, unlike the legacy API.
 *
 * Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 * The consuming project must enable `driver/i2c_master.h` (built into
 * ESP-IDF) and provide an `i2c_master_dev_handle_t` to construct the
 * transport. Errors are returned via the driver-ng `esp_err_t` return
 * value but are not propagated through the `Transport` interface — the
 * contract here matches the other embedded C++ transports in this repo,
 * which silently drop the error code.
 *
 * @param dev I²C device handle, already added to a bus via
 *            `i2c_master_bus_add_device()`.
 */
class I2CTransportESPIDF : public Transport {
public:
    I2CTransportESPIDF(i2c_master_dev_handle_t dev) : _dev(dev) {}

    /** @brief Send bytes to the device via `i2c_master_transmit`.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override {
        i2c_master_transmit(_dev, data, len, -1);
    }

    /** @brief Read bytes from the device via `i2c_master_receive`.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override {
        i2c_master_receive(_dev, buf, len, -1);
    }

    /** @brief Write then read with a repeated start via `i2c_master_transmit_receive`.
     *
     *  `i2c_master_transmit_receive` is a single driver-ng call that
     *  performs the write-then-repeated-start-read internally — no
     *  separate STOP/repeated-START management needed.
     *
     *  @param data     Register/command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        i2c_master_transmit_receive(_dev, data, data_len, buf, buf_len, -1);
    }

protected:
    /** @brief Underlying I²C device handle, exposed so derived transports
     *         (e.g. SMBus with PEC) can issue raw transfers that the
     *         public write/read/write_read API does not cover. */
    i2c_master_dev_handle_t i2c_dev() const { return _dev; }

private:
    i2c_master_dev_handle_t _dev;
};
