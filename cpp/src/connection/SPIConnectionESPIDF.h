#pragma once
#include <string.h>
#include <driver/spi_master.h>
#include <hal/spi_types.h>
#include "RegisterConnection.h"

/** @brief SPI connection for ESP-IDF (`driver/spi_master.h`).
 *
 * Wraps ESP-IDF's SPI master API. Constructor accepts an
 * `spi_device_handle_t` already added to a bus via `spi_bus_add_device()`.
 * Unlike Pico SDK — and like Zephyr's devicetree `cs-gpios` — CS is
 * owned by the driver itself: `spi_device_interface_config_t.spics_io_num`
 * is set once at `spi_bus_add_device()` time, and the driver
 * asserts/deasserts it automatically around every transaction. The
 * connection never touches a CS GPIO directly.
 *
 * `write_read` uses two `spi_device_polling_transmit()` calls sharing
 * one CS assertion: the first sends `data` with `SPI_TRANS_CS_KEEP_ACTIVE`
 * set on the transaction to hold CS low after it completes, the second
 * clocks in `n` bytes and lets CS deassert normally. This is the native
 * equivalent of Zephyr's two-segment `spi_transceive` buffer set.
 *
 * Requires ESP-IDF ≥5.1 exported (`IDF_PATH` set, `idf.py` on PATH).
 * The consuming project must link `driver` and provide a configured
 * `spi_device_handle_t` to construct the connection.
 *
 * @param dev          SPI device handle, already added to a bus via
 *                     `spi_bus_add_device()` with CS configured in
 *                     `spi_device_interface_config_t.spics_io_num`.
 * @param readBit      Bit ORed into the command byte for a read; 0 if the
 *                     chip has no such bit. Default 0x80.
 * @param multiByteBit Bit ORed in for multi-byte (burst) transfers when
 *                     len > 1; 0 if the chip has no such bit and always
 *                     auto-increments. Default 0.
 * @param intPin       Optional InputPin for INT-line delivery.
 * @param enPin        Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class SPIConnectionESPIDF : public RegisterConnection {
public:
    // See RegisterConnection.h's using declaration for why this is
    // needed: overriding read()/write() below would otherwise hide
    // RegisterConnection's (and, transitively, Connection's) other
    // read()/write() overloads.
    using RegisterConnection::read;
    using RegisterConnection::write;

    SPIConnectionESPIDF(spi_device_handle_t dev, uint8_t readBit = 0x80, uint8_t multiByteBit = 0,
                        InputPin* intPin = nullptr, OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : RegisterConnection(intPin, enPin, 1, enActiveHigh), _dev(dev), _readBit(readBit), _multiByteBit(multiByteBit) {}

    /** @brief Read @p len bytes starting at register @p reg, building the SPI command byte. */
    void read(uint32_t reg, uint8_t* buf, size_t len) override {
        uint8_t cmd = static_cast<uint8_t>(reg) | _readBit;
        if (len > 1 && _multiByteBit) cmd |= _multiByteBit;
        write_read(&cmd, 1, buf, len);
    }

    /** @brief Write @p len bytes of @p data to register @p reg, building the SPI command byte. */
    void write(uint32_t reg, const uint8_t* data, size_t len) override {
        uint8_t cmd = (static_cast<uint8_t>(reg) & static_cast<uint8_t>(~_readBit)) | ((len > 1 && _multiByteBit) ? _multiByteBit : 0);
        uint8_t payload[17];
        payload[0] = cmd;
        memcpy(payload + 1, data, len);
        Connection::write(payload, len + 1);
    }

protected:
    /** @brief Send bytes to the device via `spi_device_polling_transmit`.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void _write(const uint8_t* data, size_t len) override {
        spi_transaction_t t = {};
        t.tx_buffer = data;
        t.length    = static_cast<size_t>(len) * 8;
        spi_device_polling_transmit(_dev, &t);
    }

    /** @brief Read bytes from the device via `spi_device_polling_transmit`.
     *
     *  `t.tx_buffer = NULL` clocks out zero bits, matching the
     *  dummy-byte convention every other platform's `read` uses.
     *
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void _read(uint8_t* buf, size_t len) override {
        spi_transaction_t t = {};
        t.rx_buffer = buf;
        t.length    = static_cast<size_t>(len) * 8;
        spi_device_polling_transmit(_dev, &t);
    }

    /** @brief Write then read with CS held across both phases.
     *
     *  Two `spi_device_polling_transmit()` calls share one CS assertion:
     *  the first sends `data` with `SPI_TRANS_CS_KEEP_ACTIVE` set to
     *  hold CS low after it completes, the second clocks in `buf_len`
     *  bytes and lets CS deassert normally. This is the native
     *  equivalent of Zephyr's two-segment `spi_transceive` buffer set.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        spi_transaction_t t1 = {};
        t1.tx_buffer = data;
        t1.length    = static_cast<size_t>(data_len) * 8;
        t1.flags     = SPI_TRANS_CS_KEEP_ACTIVE;
        spi_device_polling_transmit(_dev, &t1);

        spi_transaction_t t2 = {};
        t2.rx_buffer = buf;
        t2.length    = static_cast<size_t>(buf_len) * 8;
        spi_device_polling_transmit(_dev, &t2);
    }

private:
    spi_device_handle_t _dev;
    uint8_t              _readBit;
    uint8_t              _multiByteBit;   // 0 = chip has no such bit
};
