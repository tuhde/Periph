#pragma once
#include <zephyr/drivers/spi.h>
#include "Connection.h"

/** @brief NeoPixel (WS2812B-compatible) connection for Zephyr RTOS, via SPI bit-encoding.
 *
 * Write-only: read()/write_read() are no-ops.
 *
 * @param dev     SPI controller device pointer.
 * @param freq_hz SPI clock frequency (default 2 400 000).
 * @param intPin  Optional InputPin (unused by NeoPixel; kept for API uniformity).
 * @param enPin   Optional OutputPin for hardware enable/power control.
 */
class NeoPixelConnectionZephyr : public Connection {
public:
    NeoPixelConnectionZephyr(const struct device* dev, uint32_t freq_hz = 2400000,
                             InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _dev(dev)
    {
        _config.frequency = freq_hz;
        _config.operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER;
        _config.cs        = nullptr;
        _config.slave     = 0;
    }

protected:
    /** @brief Encode and transmit pixel data, then hold MOSI low for reset.
     *  @param data Pointer to the pixel data buffer.
     *  @param len  Number of bytes to send (3 per RGB pixel, 4 per RGBW pixel).
     */
    void _write(const uint8_t* data, size_t len) override {
        uint8_t* encoded = new uint8_t[len * 3 + 16]();
        _encode(data, len, encoded);
        struct spi_buf tx_buf  = { .buf = encoded, .len = len * 3 + 16 };
        struct spi_buf_set tx   = { .buffers = &tx_buf, .count = 1 };
        spi_write(_dev, &_config, &tx);
        delete[] encoded;
    }

    void _read(uint8_t* /*buf*/, size_t /*len*/) override {}

    void _write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                     uint8_t* /*buf*/, size_t /*buf_len*/) override {}

private:
    const struct device* _dev;
    struct spi_config    _config;

    static void _encode(const uint8_t* data, size_t len, uint8_t* out) {
        for (size_t i = 0; i < len; i++) {
            uint32_t bits = 0;
            for (int bit = 7; bit >= 0; bit--) {
                bits = (bits << 3) | ((data[i] >> bit) & 1 ? 0b110 : 0b100);
            }
            out[i * 3]     = (bits >> 16) & 0xFF;
            out[i * 3 + 1] = (bits >> 8)  & 0xFF;
            out[i * 3 + 2] =  bits        & 0xFF;
        }
    }
};
