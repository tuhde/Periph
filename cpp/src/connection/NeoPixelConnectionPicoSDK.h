#pragma once
#include <hardware/spi.h>
#include "Connection.h"

/** @brief NeoPixel (WS2812B-compatible) connection for the Raspberry Pi Pico SDK.
 *
 * Uses `hardware_spi` directly (bare-metal `pico-sdk`, no Arduino core, no
 * RTOS) rather than the RP2040/RP2350 PIO block. The same SPI bit-encoding
 * trick as every other platform in this repo is used: each NeoPixel bit
 * is encoded as 3 SPI bits at 2.4 MHz, producing the WS2812B T0H/T0L/T1H/T1L
 * pulse widths within tolerance. This keeps timing/behavior identical
 * across all platforms and avoids introducing a `.pio` build step unique
 * to one platform.
 *
 * Write-only: read()/write_read() are no-ops.
 *
 * Note: the NeoPixel DIN pin must be connected to the SPI MOSI pin. SCK,
 * MISO, and CS are unused by the strip.
 *
 * `write()` encodes `len` pixel bytes into `3*len + 16` SPI bytes (the
 * trailing 16 zero bytes provide the ≥50 µs reset pulse), then shifts
 * them out in a single `spi_write_blocking` call.
 *
 * Requires the `PICO_SDK_PATH` environment variable and `pico_sdk_init()`
 * in the consuming CMake project. Link against `hardware_spi`.
 *
 * @param spi    SPI controller pointer (`spi0` or `spi1`); caller has
 *               already configured it for 2.4 MHz, mode 0, MSB-first.
 * @param intPin Optional InputPin (unused by NeoPixel; kept for API uniformity).
 * @param enPin  Optional OutputPin for hardware enable/power control.
 */
class NeoPixelConnectionPicoSDK : public Connection {
public:
    NeoPixelConnectionPicoSDK(spi_inst_t* spi, InputPin* intPin = nullptr, OutputPin* enPin = nullptr)
        : Connection(intPin, enPin), _spi(spi) {}

protected:
    /** @brief Encode and transmit pixel data, then hold MOSI low for reset.
     *  @param data Pointer to the pixel data buffer.
     *  @param len  Number of bytes to send (3 per RGB pixel, 4 per RGBW pixel).
     */
    void _write(const uint8_t* data, size_t len) override {
        uint8_t encoded[3 * 64 + 16];  // 64 bytes covers typical small strips
        if (len * 3 + 16 > sizeof(encoded)) {
            // fall back to chunked transfer for larger strips
            write_chunked(data, len);
            return;
        }
        _encode(data, len, encoded);
        spi_write_blocking(_spi, encoded, len * 3 + 16);
    }

    void _read(uint8_t* /*buf*/, size_t /*len*/) override {}

    void _write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                     uint8_t* /*buf*/, size_t /*buf_len*/) override {}

private:
    spi_inst_t* _spi;

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

    void write_chunked(const uint8_t* data, size_t len) {
        // For payloads larger than the stack buffer, encode and send in
        // 64-byte chunks. Each chunk: 64*3 = 192 encoded bytes, with a
        // reset tail appended after the last chunk only.
        constexpr size_t CHUNK = 64;
        uint8_t encoded[3 * CHUNK + 16];
        size_t pos = 0;
        while (pos < len) {
            size_t n = (len - pos < CHUNK) ? (len - pos) : CHUNK;
            bool last = (pos + n == len);
            _encode(data + pos, n, encoded);
            size_t tx_len = n * 3 + (last ? 16 : 0);
            spi_write_blocking(_spi, encoded, tx_len);
            pos += n;
        }
    }
};
