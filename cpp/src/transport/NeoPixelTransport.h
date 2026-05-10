#pragma once
#include <Arduino.h>
#include <SPI.h>
#include "Transport.h"

/** @brief NeoPixel transport using SPI bit-banging. */
class NeoPixelTransport : public Transport {
public:
    /**
     * @brief Construct a NeoPixel transport.
     * @param bus SPI bus (SPIClass&).
     */
    NeoPixelTransport(SPIClass& bus)
        : _bus(bus) {}

    /**
     * @brief Encode and transmit NeoPixel data.
     * @param data Pointer to pixel data.
     * @param len Number of bytes (3/pixel for RGB, 4/pixel for RGBW).
     */
    void write(const uint8_t* data, size_t len) override;

private:
    void _encode_write(const uint8_t* data, size_t len);

    SPIClass& _bus;
};