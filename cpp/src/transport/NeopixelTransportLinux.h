#pragma once
#ifdef __linux__
#include <linux/spi/spidev.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "Transport.h"

/** @brief NeoPixel transport for Linux using spidev. */
class NeopixelTransportLinux : public Transport {
public:
    /**
     * @brief Construct a NeoPixel transport.
     * @param bus_num SPI bus number (e.g. 0 for /dev/spidev0.0).
     * @param device_num SPI device number (e.g. 0 for /dev/spidev0.0).
     */
    NeopixelTransportLinux(int bus_num, int device_num);

    ~NeopixelTransportLinux();

    /**
     * @brief Encode and transmit NeoPixel data.
     * @param data Pointer to pixel data.
     * @param len Number of bytes (3/pixel for RGB, 4/pixel for RGBW).
     */
    void write(const uint8_t* data, size_t len) override;

private:
    int _fd;
};
#endif // __linux__