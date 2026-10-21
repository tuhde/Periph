#pragma once
#include <string.h>
#include <driver/spi_master.h>
#include <hal/spi_types.h>
#include <driver/gpio.h>
#include "RegisterConnection.h"
#include "SPIExtensions.h"

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
class SPIConnectionESPIDF : public RegisterConnection, public SPIExtensions {
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

    // --- SPIExtensions (specs/transport_spi.md "Optional Extensions") ---

    /** @brief Drive CS from a GPIO instead of the SPI peripheral.
     *
     * Required for `idle_clocks()` (clocks with CS high). The device must have
     * been added with `spics_io_num = -1`. @p cs is configured as an output,
     * idle high.
     */
    void setManualCS(gpio_num_t cs) {
        _cs = cs;
        gpio_set_direction(_cs, GPIO_MODE_OUTPUT);
        gpio_set_level(_cs, 1);
    }

    /** @brief Allow `set_speed()`: re-adds the device to @p host with a new `clock_speed_hz`.
     *  @param host SPI host the device lives on.
     *  @param cfg  The `spi_device_interface_config_t` the device was added with.
     */
    void enableSpeedChange(spi_host_device_t host, const spi_device_interface_config_t& cfg) {
        _host = host;
        _devCfg = cfg;
        _canReinit = true;
    }

    Connection& connection() override { return *this; }

    /** @brief Clock @p n bits of 0xFF with CS high; needs setManualCS(). */
    void idle_clocks(uint32_t n) override {
        if (_cs == GPIO_NUM_NC) return;
        gpio_set_level(_cs, 1);
        uint8_t ff[16];
        memset(ff, 0xFF, sizeof(ff));
        uint32_t bytes = (n + 7) / 8;
        while (bytes) {
            size_t chunk = bytes > sizeof(ff) ? sizeof(ff) : bytes;
            spi_transaction_t t = {};
            t.tx_buffer = ff;
            t.length    = chunk * 8;
            spi_device_polling_transmit(_dev, &t);
            bytes -= chunk;
        }
    }

    /** @brief Re-add the device at @p hz; needs enableSpeedChange(). */
    bool set_speed(uint32_t hz) override {
        if (!_canReinit) return false;
        spi_device_handle_t newDev = nullptr;
        spi_device_interface_config_t cfg = _devCfg;
        cfg.clock_speed_hz = static_cast<int>(hz);
        if (spi_bus_remove_device(_dev) != ESP_OK) return false;
        if (spi_bus_add_device(_host, &cfg, &newDev) != ESP_OK) return false;
        _dev = newDev;
        _devCfg = cfg;
        return true;
    }

    /** @brief Assert CS (manual GPIO, or SPI_TRANS_CS_KEEP_ACTIVE on every transfer). */
    void select() override {
        _held = true;
        if (_cs != GPIO_NUM_NC) gpio_set_level(_cs, 0);
    }

    /** @brief Release CS. */
    void deselect() override {
        _held = false;
        if (_cs != GPIO_NUM_NC) gpio_set_level(_cs, 1);
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        spi_transaction_t t = {};
        t.tx_buffer = data;
        t.length    = static_cast<size_t>(len) * 8;
        t.flags     = _keepFlags();
        _csLow();
        spi_device_polling_transmit(_dev, &t);
        _csHigh();
    }

    // Reads clock 0xFF on MOSI (SD cards read other levels as commands).
    void _read(uint8_t* buf, size_t len) override {
        _csLow();
        while (len) {
            size_t chunk = len > 64 ? 64 : len;
            uint8_t ff[64];
            memset(ff, 0xFF, chunk);
            spi_transaction_t t = {};
            t.tx_buffer = ff;
            t.rx_buffer = buf;
            t.length    = chunk * 8;
            t.flags     = (chunk == len) ? _keepFlags() : SPI_TRANS_CS_KEEP_ACTIVE;
            spi_device_polling_transmit(_dev, &t);
            buf += chunk;
            len -= chunk;
        }
        _csHigh();
    }

    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        _csLow();
        spi_transaction_t t1 = {};
        t1.tx_buffer = data;
        t1.length    = static_cast<size_t>(data_len) * 8;
        t1.flags     = SPI_TRANS_CS_KEEP_ACTIVE;
        spi_device_polling_transmit(_dev, &t1);

        // The remaining bytes are read in 0xFF-padded chunks, like _read().
        while (buf_len) {
            size_t chunk = buf_len > 64 ? 64 : buf_len;
            uint8_t ff[64];
            memset(ff, 0xFF, chunk);
            spi_transaction_t t2 = {};
            t2.tx_buffer = ff;
            t2.rx_buffer = buf;
            t2.length    = chunk * 8;
            t2.flags     = (chunk == buf_len) ? _keepFlags() : SPI_TRANS_CS_KEEP_ACTIVE;
            spi_device_polling_transmit(_dev, &t2);
            buf += chunk;
            buf_len -= chunk;
        }
        _csHigh();
    }

private:
    // With a manual CS GPIO the peripheral never touches CS; otherwise a held
    // select() keeps the hardware CS active across transfers.
    uint32_t _keepFlags() const { return (_held && _cs == GPIO_NUM_NC) ? SPI_TRANS_CS_KEEP_ACTIVE : 0; }
    void _csLow()  { if (_cs != GPIO_NUM_NC && !_held) gpio_set_level(_cs, 0); }
    void _csHigh() { if (_cs != GPIO_NUM_NC && !_held) gpio_set_level(_cs, 1); }

    spi_device_handle_t _dev;
    uint8_t              _readBit;
    uint8_t              _multiByteBit;   // 0 = chip has no such bit
    gpio_num_t           _cs = GPIO_NUM_NC;   // manual CS, see setManualCS()
    bool                 _held = false;       // select() active
    spi_host_device_t    _host = SPI2_HOST;
    spi_device_interface_config_t _devCfg = {};
    bool                 _canReinit = false;
};
