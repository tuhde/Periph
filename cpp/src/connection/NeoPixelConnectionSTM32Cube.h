#pragma once
#include <stm32f4xx_hal.h>
#include "Connection.h"

/** @brief NeoPixel (WS2812B-compatible) connection for STM32Cube.
 *
 * Uses `HAL_SPI_Transmit` directly, same SPI bit-encoding trick as every
 * other platform in this repo: each NeoPixel bit is encoded as 3 SPI bits
 * at 2.4 MHz, producing the WS2812B T0H/T0L/T1H/T1L pulse widths within
 * tolerance. This keeps timing/behavior identical across all platforms
 * and avoids a TIM+DMA special case unique to this one platform.
 *
 * Write-only: read()/write_read() are no-ops.
 *
 * Note: the NeoPixel DIN pin must be connected to the SPI MOSI pin. SCK,
 * MISO, and NSS are unused by the strip.
 *
 * `write()` encodes `len` pixel bytes into `3*len + 16` SPI bytes (the
 * trailing 16 zero bytes provide the ≥50 µs reset pulse), then shifts
 * them out in a single `HAL_SPI_Transmit` call — or, for strips longer
 * than the 64-byte stack buffer covers, in 64-byte chunks (same fallback
 * as `NeoPixelConnectionPicoSDK`, to avoid a heap allocation).
 *
 * @param hspi   SPI handle, already initialised via `HAL_SPI_Init()` for
 *               2.4 MHz, mode 0, MSB-first.
 * @param intPin Optional InputPin (unused by NeoPixel; kept for API uniformity).
 * @param enPin  Optional OutputPin for hardware enable/power control.
 * @param enActiveHigh True if the EN pin is active-high (default); false for active-low.
 */
class NeoPixelConnectionSTM32Cube : public Connection {
public:
    explicit NeoPixelConnectionSTM32Cube(SPI_HandleTypeDef* hspi, InputPin* intPin = nullptr,
                                         OutputPin* enPin = nullptr, bool enActiveHigh = true)
        : Connection(intPin, enPin, enActiveHigh), _hspi(hspi) {}

protected:
    /** @brief Encode and transmit pixel data, then hold MOSI low for reset.
     *  @param data Pointer to the pixel data buffer.
     *  @param len  Number of bytes to send (3 per RGB pixel, 4 per RGBW pixel).
     */
    void _write(const uint8_t* data, size_t len) override {
        uint8_t encoded[3 * 64 + 16];  // 64 bytes covers typical small strips
        if (len * 3 + 16 > sizeof(encoded)) {
            _write_chunked(data, len);
            return;
        }
        _encode(data, len, encoded);
        HAL_SPI_Transmit(_hspi, encoded, static_cast<uint16_t>(len * 3 + 16), _TIMEOUT_MS);
    }

    void _read(uint8_t* /*buf*/, size_t /*len*/) override {}

    void _write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                     uint8_t* /*buf*/, size_t /*buf_len*/) override {}

private:
    static constexpr uint32_t _TIMEOUT_MS = 1000;

    SPI_HandleTypeDef* _hspi;

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
        // 16 trailing zero bytes = ≥50 µs reset pulse at 2.4 MHz
        for (size_t i = len * 3; i < len * 3 + 16; i++) out[i] = 0;
    }

    /** @brief For payloads larger than the 64-byte stack buffer, encode and
     *  send in 64-byte chunks, appending the reset tail after the last
     *  chunk only. */
    void _write_chunked(const uint8_t* data, size_t len) {
        constexpr size_t CHUNK = 64;
        uint8_t encoded[3 * CHUNK + 16];
        size_t pos = 0;
        while (pos < len) {
            size_t n = (len - pos < CHUNK) ? (len - pos) : CHUNK;
            bool last = (pos + n == len);
            _encode(data + pos, n, encoded);
            size_t tx_len = n * 3 + (last ? 16 : 0);
            HAL_SPI_Transmit(_hspi, encoded, static_cast<uint16_t>(tx_len), _TIMEOUT_MS);
            pos += n;
        }
    }
};
