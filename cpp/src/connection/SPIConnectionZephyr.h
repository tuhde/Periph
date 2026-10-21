#pragma once
#include <zephyr/drivers/spi.h>
#include <cstring>
#include "RegisterConnection.h"
#include "SPIExtensions.h"

/** @brief SPI connection for Zephyr RTOS (wraps the spi driver API).
 *
 * prj.conf must enable CONFIG_SPI=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 * The SPI device node and its cs-gpios property must be present in the
 * board's devicetree or an overlay.
 *
 * @param dev          SPI controller device pointer (e.g., DEVICE_DT_GET(DT_NODELABEL(spi0))).
 * @param config       spi_config specifying clock, operation flags, and the CS GPIO spec.
 * @param readBit      Bit ORed into the command byte for a read; 0 if the
 *                     chip has no such bit. Default 0x80.
 * @param multiByteBit Bit ORed in for multi-byte (burst) transfers when
 *                     len > 1; 0 if the chip has no such bit and always
 *                     auto-increments. Default 0.
 * @param intPin       Optional InputPin for INT-line delivery.
 * @param enPin        Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class SPIConnectionZephyr : public RegisterConnection, public SPIExtensions {
public:
    // See RegisterConnection.h's using declaration for why this is
    // needed: overriding read()/write() below would otherwise hide
    // RegisterConnection's (and, transitively, Connection's) other
    // read()/write() overloads.
    using RegisterConnection::read;
    using RegisterConnection::write;

    SPIConnectionZephyr(const struct device *dev, const struct spi_config &config,
                        uint8_t readBit = 0x80, uint8_t multiByteBit = 0,
                        InputPin* intPin = nullptr, OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : RegisterConnection(intPin, enPin, 1, enActiveHigh), _dev(dev), _config(config),
          _readBit(readBit), _multiByteBit(multiByteBit) {}

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

    Connection& connection() override { return *this; }

    /** @brief Clock @p n bits of 0xFF with CS untouched (config copy without a CS control). */
    void idle_clocks(uint32_t n) override {
        struct spi_config cfg = _config;
        memset(&cfg.cs, 0, sizeof(cfg.cs));   // no CS handling: the line stays at its inactive (high) level
        uint8_t ff[16];
        memset(ff, 0xFF, sizeof(ff));
        uint32_t bytes = (n + 7) / 8;
        while (bytes) {
            size_t chunk = bytes > sizeof(ff) ? sizeof(ff) : bytes;
            struct spi_buf tx_buf = { .buf = ff, .len = chunk };
            struct spi_buf_set tx = { .buffers = &tx_buf, .count = 1 };
            spi_write(_dev, &cfg, &tx);
            bytes -= chunk;
        }
    }

    /** @brief Set `config.frequency`; the controller driver picks the closest rate. */
    bool set_speed(uint32_t hz) override {
        _config.frequency = hz;
        return true;
    }

    /** @brief Keep CS asserted (SPI_HOLD_ON_CS) and the bus locked until deselect(). */
    void select() override { _held = true; }

    /** @brief Release CS and the bus lock. */
    void deselect() override {
        _held = false;
        spi_release(_dev, &_config);
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        struct spi_buf tx_buf  = { .buf = const_cast<uint8_t*>(data), .len = len };
        struct spi_buf_set tx  = { .buffers = &tx_buf, .count = 1 };
        struct spi_config cfg = _heldConfig();
        spi_write(_dev, &cfg, &tx);
    }

    // Reads clock 0xFF on MOSI (SD cards read other levels as commands), in
    // 64-byte chunks with CS held between chunks.
    void _read(uint8_t* buf, size_t len) override {
        static const uint8_t ff[64] = {
            0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
            0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
            0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
            0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF };
        while (len) {
            size_t chunk = len > sizeof(ff) ? sizeof(ff) : len;
            bool last = (chunk == len);
            struct spi_buf tx_buf = { .buf = const_cast<uint8_t*>(ff), .len = chunk };
            struct spi_buf rx_buf = { .buf = buf, .len = chunk };
            struct spi_buf_set tx = { .buffers = &tx_buf, .count = 1 };
            struct spi_buf_set rx = { .buffers = &rx_buf, .count = 1 };
            struct spi_config cfg = _config;
            if (_held || !last) cfg.operation |= SPI_HOLD_ON_CS | SPI_LOCK_ON;
            spi_transceive(_dev, &cfg, &tx, &rx);
            if (!_held && last) spi_release(_dev, &_config);
            buf += chunk;
            len -= chunk;
        }
    }

    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        // RX: discard (buf=nullptr) during command phase, capture during read phase.
        uint8_t tx_pad[256];
        memset(tx_pad, 0xFF, sizeof(tx_pad));  // 0xFF dummy TX bytes (MOSI idles high)
        struct spi_buf tx_bufs[2] = {
            { .buf = const_cast<uint8_t*>(data), .len = data_len },
            { .buf = tx_pad,                      .len = buf_len  },
        };
        struct spi_buf rx_bufs[2] = {
            { .buf = nullptr, .len = data_len },
            { .buf = buf,     .len = buf_len  },
        };
        struct spi_buf_set tx_set = { .buffers = tx_bufs, .count = 2 };
        struct spi_buf_set rx_set = { .buffers = rx_bufs, .count = 2 };
        struct spi_config cfg = _heldConfig();
        spi_transceive(_dev, &cfg, &tx_set, &rx_set);
    }

private:
    struct spi_config _heldConfig() const {
        struct spi_config cfg = _config;
        if (_held) cfg.operation |= SPI_HOLD_ON_CS | SPI_LOCK_ON;
        return cfg;
    }

    const struct device *_dev;
    struct spi_config    _config;
    uint8_t               _readBit;
    uint8_t               _multiByteBit;   // 0 = chip has no such bit
    bool                  _held = false;   // select() active: CS held across calls
};
