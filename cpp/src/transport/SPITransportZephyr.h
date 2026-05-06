#pragma once
#include <zephyr/drivers/spi.h>
#include "Transport.h"

class SPITransportZephyr : public Transport {
public:
    SPITransportZephyr(const struct device *dev, const struct spi_config &config)
        : _dev(dev), _config(config) {}

    void write(const uint8_t* data, size_t len) override {
        struct spi_buf tx_buf  = { .buf = const_cast<uint8_t*>(data), .len = len };
        struct spi_buf_set tx  = { .buffers = &tx_buf, .count = 1 };
        spi_write(_dev, &_config, &tx);
    }

    void read(uint8_t* buf, size_t len) override {
        struct spi_buf rx_buf  = { .buf = buf, .len = len };
        struct spi_buf_set rx  = { .buffers = &rx_buf, .count = 1 };
        spi_read(_dev, &_config, &rx);
    }

    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        // TX: send command bytes, then clock zeros (buf=nullptr) during read phase.
        // RX: discard (buf=nullptr) during command phase, capture during read phase.
        struct spi_buf tx_bufs[2] = {
            { .buf = const_cast<uint8_t*>(data), .len = data_len },
            { .buf = nullptr,                     .len = buf_len  },
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
