#ifdef __linux__
#include "SPITransportLinux.h"
#include <linux/spi/spidev.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>

SPITransportLinux::SPITransportLinux(int bus_num, int device_num,
                                     uint8_t mode, uint32_t max_speed_hz)
    : _speed_hz(max_speed_hz)
{
    char path[32];
    snprintf(path, sizeof(path), "/dev/spidev%d.%d", bus_num, device_num);
    _fd = open(path, O_RDWR);
    if (_fd < 0) { perror(path); exit(1); }
    if (ioctl(_fd, SPI_IOC_WR_MODE, &mode) < 0)
        { perror("SPI_IOC_WR_MODE"); close(_fd); exit(1); }
    if (ioctl(_fd, SPI_IOC_WR_MAX_SPEED_HZ, &max_speed_hz) < 0)
        { perror("SPI_IOC_WR_MAX_SPEED_HZ"); close(_fd); exit(1); }
}

SPITransportLinux::~SPITransportLinux() {
    if (_fd >= 0) close(_fd);
}

void SPITransportLinux::write(const uint8_t* data, size_t len) {
    struct spi_ioc_transfer tr = {};
    tr.tx_buf        = reinterpret_cast<uintptr_t>(data);
    tr.len           = static_cast<uint32_t>(len);
    tr.speed_hz      = _speed_hz;
    tr.bits_per_word = 8;
    if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0) perror("SPI_IOC_MESSAGE write");
}

void SPITransportLinux::read(uint8_t* buf, size_t len) {
    struct spi_ioc_transfer tr = {};
    tr.rx_buf        = reinterpret_cast<uintptr_t>(buf);
    tr.len           = static_cast<uint32_t>(len);
    tr.speed_hz      = _speed_hz;
    tr.bits_per_word = 8;
    if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0) perror("SPI_IOC_MESSAGE read");
}

void SPITransportLinux::write_read(const uint8_t* data, size_t data_len,
                                    uint8_t* buf, size_t buf_len) {
    // Two transfers in one ioctl call: CS stays asserted between them.
    struct spi_ioc_transfer tr[2] = {};
    tr[0].tx_buf        = reinterpret_cast<uintptr_t>(data);
    tr[0].len           = static_cast<uint32_t>(data_len);
    tr[0].speed_hz      = _speed_hz;
    tr[0].bits_per_word = 8;
    tr[0].cs_change     = 0;

    tr[1].rx_buf        = reinterpret_cast<uintptr_t>(buf);
    tr[1].len           = static_cast<uint32_t>(buf_len);
    tr[1].speed_hz      = _speed_hz;
    tr[1].bits_per_word = 8;

    if (ioctl(_fd, SPI_IOC_MESSAGE(2), tr) < 0) perror("SPI_IOC_MESSAGE write_read");
}
#endif // __linux__
