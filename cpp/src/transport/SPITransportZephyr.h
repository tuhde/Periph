#pragma once
#include <zephyr/drivers/spi.h>
#include <cstring>
#include "Transport.h"

/** @brief SPI transport for Zephyr RTOS (wraps the spi driver API).
 *
 * prj.conf must enable CONFIG_SPI=y, CONFIG_CPP=y, CONFIG_STD_CPP17=y.
 * The SPI device node and its cs-gpios property must be present in the
 * board's devicetree or an overlay.
 *
 * @param dev    SPI controller device pointer (e.g., DEVICE_DT_GET(DT_NODELABEL(spi0))).
 * @param config spi_config specifying clock, operation flags, and the CS GPIO spec.
 */
class SPITransportZephyr : public Transport {
public:
    SPITransportZephyr(const struct device *dev, const struct spi_config &config)
        : _dev(dev), _config(config) {}

    /** @brief Send bytes via spi_write.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override {
        struct spi_buf tx_buf  = { .buf = const_cast<uint8_t*>(data), .len = len };
        struct spi_buf_set tx  = { .buffers = &tx_buf, .count = 1 };
        spi_write(_dev, &_config, &tx);
    }

    /** @brief Read bytes via spi_read.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override {
        struct spi_buf rx_buf  = { .buf = buf, .len = len };
        struct spi_buf_set rx  = { .buffers = &rx_buf, .count = 1 };
        spi_read(_dev, &_config, &rx);
    }

    /** @brief Write then read via spi_transceive (CS held for both phases).
     *
     *  Uses a two-segment TX buffer (command bytes + zero padding) and a
     *  two-segment RX buffer (discard during command phase, capture during read
     *  phase). Zephyr discards RX segments whose buf pointer is nullptr.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        // RX: discard (buf=nullptr) during command phase, capture during read phase.
        uint8_t tx_pad[buf_len];
        memset(tx_pad, 0, buf_len);
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
        spi_transceive(_dev, &_config, &tx_set, &rx_set);
    }

private:
    const struct device *_dev;
    struct spi_config    _config;
};
