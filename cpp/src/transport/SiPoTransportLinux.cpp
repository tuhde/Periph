#ifdef __linux__
#include "SiPoTransportLinux.h"
#include <gpiod.h>
#include <linux/spi/spidev.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

SiPoTransportLinux::SiPoTransportLinux(int bus_num, int device_num,
                                       struct gpiod_line* rck,
                                       struct gpiod_line* srclr,
                                       struct gpiod_line* g,
                                       uint32_t max_speed_hz)
    : _fd(-1), _speed_hz(max_speed_hz),
      _ser_in(nullptr), _srck(nullptr), _rck(rck), _srclr(srclr), _g(g)
{
    char path[32];
    snprintf(path, sizeof(path), "/dev/spidev%d.%d", bus_num, device_num);
    _fd = open(path, O_RDWR);
    if (_fd < 0)
        throw std::runtime_error(std::string("Failed to open ") + path + ": " + strerror(errno));
    uint8_t mode = 0;
    if (ioctl(_fd, SPI_IOC_WR_MODE, &mode) < 0) {
        ::close(_fd);
        throw std::runtime_error(std::string("SPI_IOC_WR_MODE on ") + path + ": " + strerror(errno));
    }
    if (ioctl(_fd, SPI_IOC_WR_MAX_SPEED_HZ, &max_speed_hz) < 0) {
        ::close(_fd);
        throw std::runtime_error(std::string("SPI_IOC_WR_MAX_SPEED_HZ on ") + path + ": " + strerror(errno));
    }

    gpiod_line_set_value(_rck, 0);
    if (_srclr) gpiod_line_set_value(_srclr, 1);
    if (_g)     gpiod_line_set_value(_g, 0);
}

SiPoTransportLinux::SiPoTransportLinux(struct gpiod_line* ser_in, struct gpiod_line* srck,
                                       struct gpiod_line* rck,
                                       struct gpiod_line* srclr,
                                       struct gpiod_line* g)
    : _fd(-1), _speed_hz(0),
      _ser_in(ser_in), _srck(srck), _rck(rck), _srclr(srclr), _g(g)
{
    gpiod_line_set_value(_srck, 0);
    gpiod_line_set_value(_rck, 0);
    if (_srclr) gpiod_line_set_value(_srclr, 1);
    if (_g)     gpiod_line_set_value(_g, 0);
}

SiPoTransportLinux::~SiPoTransportLinux() {
    close();
}

void SiPoTransportLinux::write(const uint8_t* data, size_t len) {
    if (_fd >= 0) {
        struct spi_ioc_transfer tr = {};
        tr.tx_buf        = reinterpret_cast<uintptr_t>(data);
        tr.len           = static_cast<uint32_t>(len);
        tr.speed_hz      = _speed_hz;
        tr.bits_per_word = 8;
        if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0)
            throw std::runtime_error(std::string("SPI write: ") + strerror(errno));
    } else {
        for (size_t i = 0; i < len; i++) {
            for (int bit = 7; bit >= 0; bit--) {
                gpiod_line_set_value(_ser_in, (data[i] >> bit) & 1);
                gpiod_line_set_value(_srck, 1);
                gpiod_line_set_value(_srck, 0);
            }
        }
    }
    _latch();
}

void SiPoTransportLinux::_latch() {
    gpiod_line_set_value(_rck, 1);
    gpiod_line_set_value(_rck, 0);
}

void SiPoTransportLinux::clear() {
    if (!_srclr)
        throw std::runtime_error("SRCLR not configured");
    gpiod_line_set_value(_srclr, 0);
    gpiod_line_set_value(_srclr, 1);
}

void SiPoTransportLinux::set_output_enable(bool enabled) {
    if (!_g)
        throw std::runtime_error("G not configured");
    gpiod_line_set_value(_g, enabled ? 0 : 1);
}

void SiPoTransportLinux::close() {
    if (_fd >= 0) { ::close(_fd); _fd = -1; }
    if (_ser_in) { gpiod_line_release(_ser_in); _ser_in = nullptr; }
    if (_srck)   { gpiod_line_release(_srck);   _srck   = nullptr; }
    if (_rck)    { gpiod_line_release(_rck);    _rck    = nullptr; }
    if (_srclr)  { gpiod_line_release(_srclr);  _srclr  = nullptr; }
    if (_g)      { gpiod_line_release(_g);      _g      = nullptr; }
}
#endif // __linux__
