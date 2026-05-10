#pragma once
#include <zephyr/drivers/spi.h>
#include "Transport.h"

/** @brief NeoPixel transport for Zephyr RTOS. */
class NeopixelTransportZephyr : public Transport {
public:
    /**
     * @brief Construct a NeoPixel transport.
     * @param dev SPI device pointer.
     * @param config SPI configuration.
     */
    NeopixelTransportZephyr(const struct device* dev, const struct spi_config& config)
        : _dev(dev), _config(config) {}

    /**
     * @brief Encode and transmit NeoPixel data.
     * @param data Pointer to pixel data.
     * @param len Number of bytes (3/pixel for RGB, 4/pixel for RGBW).
     */
    void write(const uint8_t* data, size_t len) override;

private:
    const struct device* _dev;
    struct spi_config _config;
};