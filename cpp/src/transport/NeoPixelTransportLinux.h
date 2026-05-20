#pragma once
#ifdef __linux__
#include <stdint.h>
#include <stddef.h>
#include "Transport.h"

class NeoPixelTransportLinux : public Transport {
public:
    NeoPixelTransportLinux(int bus_num, int device_num);
    ~NeoPixelTransportLinux();

    /** @brief Encode and transmit pixel data, then hold MOSI low for reset.
     *  @param data Pointer to the pixel data buffer.
     *  @param len  Number of bytes to send (3 per RGB pixel, 4 per RGBW pixel).
     */
    void write(const uint8_t* data, size_t len) override;

    void read(uint8_t* /*buf*/, size_t /*len*/) override {}

    void write_read(const uint8_t* /*data*/, size_t /*data_len*/,
                   uint8_t* /*buf*/, size_t /*buf_len*/) override {}

private:
    int      _fd;
    uint32_t _speed_hz;

    static void _encode(const uint8_t* data, size_t len, uint8_t* out);
};

#endif // __linux__