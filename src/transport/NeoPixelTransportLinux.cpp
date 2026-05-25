#ifdef __linux__
#include "NeoPixelTransportLinux.h"
#include <linux/spi/spidev.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

NeoPixelTransportLinux::NeoPixelTransportLinux(int bus_num, int device_num)
    : _speed_hz(2400000)
{
    char path[32];
    snprintf(path, sizeof(path), "/dev/spidev%d.%d", bus_num, device_num);
    _fd = open(path, O_RDWR);
    if (_fd < 0)
        throw std::runtime_error(std::string("Failed to open ") + path + ": " + strerror(errno));
    uint8_t mode = 0;
    if (ioctl(_fd, SPI_IOC_WR_MODE, &mode) < 0) {
        close(_fd);
        throw std::runtime_error(std::string("SPI_IOC_WR_MODE on ") + path + ": " + strerror(errno));
    }
    if (ioctl(_fd, SPI_IOC_WR_MAX_SPEED_HZ, &_speed_hz) < 0) {
        close(_fd);
        throw std::runtime_error(std::string("SPI_IOC_WR_MAX_SPEED_HZ on ") + path + ": " + strerror(errno));
    }
}

NeoPixelTransportLinux::~NeoPixelTransportLinux() {
    if (_fd >= 0) close(_fd);
}

void NeoPixelTransportLinux::_encode(const uint8_t* data, size_t len, uint8_t* out) {
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

void NeoPixelTransportLinux::write(const uint8_t* data, size_t len) {
    uint8_t encoded[len * 3 + 16] = {};
    _encode(data, len, encoded);
    struct spi_ioc_transfer tr = {};
    tr.tx_buf        = reinterpret_cast<uintptr_t>(encoded);
    tr.len           = static_cast<uint32_t>(len * 3 + 16);
    tr.speed_hz      = _speed_hz;
    tr.bits_per_word = 8;
    if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0) perror("SPI_IOC_MESSAGE write");
}
#endif // __linux__