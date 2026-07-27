#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Connection.h"

/** @brief NeoPixel (WS2812B-compatible) connection for Linux, via SPI bit-encoding.
 *
 * Write-only: read()/write_read() are no-ops.
 *
 * @param bus_num    SPI bus number (opens /dev/spidevBUS.DEVICE).
 * @param device_num Chip-select line on the bus.
 * @param intPin     Optional InputPin (unused by NeoPixel; kept for API uniformity).
 * @param enPin      Optional OutputPin for hardware enable/power control.
 */
class NeoPixelConnectionLinux : public Connection {
public:
    NeoPixelConnectionLinux(int bus_num, int device_num,
                            InputPin* intPin = nullptr, OutputPin* enPin = nullptr);
    ~NeoPixelConnectionLinux();

protected:
    /** @brief Encode and transmit pixel data, then hold MOSI low for reset.
     *  @param data Pointer to the pixel data buffer.
     *  @param len  Number of bytes to send (3 per RGB pixel, 4 per RGBW pixel).
     */
    void _write(const uint8_t* data, size_t len) override;

    void _read(uint8_t* /*buf*/, size_t /*len*/) override {}

    void _write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                     uint8_t* /*buf*/, size_t /*buf_len*/) override {}

private:
    int      _fd;
    uint32_t _speed_hz;

    static void _encode(const uint8_t* data, size_t len, uint8_t* out);
};

#endif // __linux__
