#pragma once
#include <zephyr/drivers/spi.h>
#include "Transport.h"

class NeoPixelTransportZephyr : public Transport {
public:
    NeoPixelTransportZephyr(const struct device* dev, const struct spi_config& config)
        : _dev(dev), _config(config) {}

    void write(const uint8_t* data, size_t len) override {
        size_t encoded_len = len * 3 + 16;
        uint8_t encoded[256];
        if (encoded_len > sizeof(encoded)) return;
        for (size_t i = 0; i < len; i++) {
            uint8_t byte = data[i];
            for (int bit = 7; bit >= 0; bit--) {
                size_t idx = i * 3 + (7 - bit);
                encoded[idx] = ((byte >> bit) & 1) ? 0b110 : 0b100;
            }
        }
        for (size_t i = 0; i < 16; i++) {
            encoded[len * 3 + i] = 0x00;
        }
        struct spi_buf tx_buf = { .buf = encoded, .len = encoded_len };
        struct spi_buf_set tx = { .buffers = &tx_buf, .count = 1 };
        spi_write(_dev, &_config, &tx);
    }

private:
    const struct device* _dev;
    struct spi_config _config;
};