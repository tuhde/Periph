#ifdef __linux__
#include "NeopixelTransportLinux.h"

NeopixelTransportLinux::NeopixelTransportLinux(int bus_num, int device_num)
    : _fd(-1) {
    char path[32];
    snprintf(path, sizeof(path), "/dev/spidev%d.%d", bus_num, device_num);
    _fd = open(path, O_RDWR);
    if (_fd < 0) { perror(path); exit(1); }
    uint8_t mode = 0;
    if (ioctl(_fd, SPI_IOC_WR_MODE, &mode) < 0)
        { perror("SPI_IOC_WR_MODE"); close(_fd); exit(1); }
    uint32_t speed = 2400000;
    if (ioctl(_fd, SPI_IOC_WR_MAX_SPEED_HZ, &speed) < 0)
        { perror("SPI_IOC_WR_MAX_SPEED_HZ"); close(_fd); exit(1); }
}

NeopixelTransportLinux::~NeopixelTransportLinux() {
    if (_fd >= 0) close(_fd);
}

void NeopixelTransportLinux::write(const uint8_t* data, size_t len) {
    size_t encoded_len = len * 3 + 16;
    uint8_t* encoded = new uint8_t[encoded_len];
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
    struct spi_ioc_transfer tr = {};
    tr.tx_buf        = reinterpret_cast<uintptr_t>(encoded);
    tr.len           = static_cast<uint32_t>(encoded_len);
    tr.speed_hz      = 2400000;
    tr.bits_per_word = 8;
    if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0)
        perror("SPI_IOC_MESSAGE write");
    delete[] encoded;
}
#endif // __linux__