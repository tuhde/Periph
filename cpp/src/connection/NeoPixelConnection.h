#pragma once
#include <Arduino.h>
#include <SPI.h>
#include "Connection.h"

/** @brief NeoPixel (WS2812B-compatible) connection for Arduino, via SPI bit-encoding.
 *
 * Write-only: read()/write_read() are no-ops (NeoPixel strips have no data-in line).
 *
 * @param bus    SPIClass instance to use (e.g., the global ::SPI).
 * @param intPin Optional InputPin (unused by NeoPixel; kept for API uniformity).
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class NeoPixelConnection : public Connection {
public:
    NeoPixelConnection(SPIClass& bus, InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _bus(bus) {}

protected:
    /** @brief Encode and transmit pixel data, then hold MOSI low for reset.
     *  @param data Pointer to the pixel data buffer.
     *  @param len  Number of bytes to send (3 per RGB pixel, 4 per RGBW pixel).
     */
    void _write(const uint8_t* data, size_t len) override {
        uint8_t encoded[len * 3 + 16] = {};
        _encode(data, len, encoded);
        _bus.beginTransaction(SPISettings(2400000, MSBFIRST, SPI_MODE0));
        _bus.transfer(encoded, sizeof(encoded));
        _bus.endTransaction();
    }

    void _read(uint8_t* /*buf*/, size_t /*len*/) override {
    }

    void _write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                     uint8_t* /*buf*/, size_t /*buf_len*/) override {
    }

private:
    SPIClass& _bus;

    static void _encode(const uint8_t* data, size_t len, uint8_t* out) {
        for (size_t i = 0; i < len; i++) {
            uint32_t bits = 0;
            for (int bit = 7; bit >= 0; bit--) {
                bits = (bits << 3) | ((data[i] >> bit) & 1 ? 0b110 : 0b100);
            }
            out[i * 3]     = (bits >> 16) & 0xFF;
            out[i * 3 + 1] = (bits >> 8) & 0xFF;
            out[i * 3 + 2] = bits & 0xFF;
        }
    }
};
