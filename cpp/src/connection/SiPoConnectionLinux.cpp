#ifdef __linux__
#include "SiPoConnectionLinux.h"
#include <linux/spi/spidev.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

std::unique_ptr<GpiodLineLinux> SiPoConnectionLinux::_output(const char* chip_path, int offset,
                                                             bool initial_high, const char* consumer) {
    if (offset < 0) return nullptr;
    auto line = std::make_unique<GpiodLineLinux>(chip_path, (unsigned int)offset,
                                                 GpiodLineLinux::Mode::Output, initial_high, consumer);
    if (!line->valid())
        throw std::runtime_error(std::string("Failed to request GPIO line ") + std::to_string(offset) +
                                 " on " + chip_path);
    return line;
}

SiPoConnectionLinux::SiPoConnectionLinux(int bus_num, int device_num,
                                         const char* chip_path, unsigned int rck,
                                         int srclr, int g,
                                         uint32_t max_speed_hz)
    : _fd(-1), _speed_hz(max_speed_hz)
{
    // Request the GPIO lines first: a failed request throws, and nothing
    // has to be cleaned up yet. RCK idles LOW, SRCLR HIGH (not clearing),
    // G LOW (outputs enabled).
    _rck   = _output(chip_path, (int)rck, false, "sipo-rck");
    _srclr = _output(chip_path, srclr, true, "sipo-srclr");
    _g     = _output(chip_path, g, false, "sipo-g");

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
}

SiPoConnectionLinux::SiPoConnectionLinux(const char* chip_path, unsigned int ser_in, unsigned int srck,
                                         unsigned int rck, int srclr, int g)
    : _fd(-1), _speed_hz(0)
{
    _ser_in = _output(chip_path, (int)ser_in, false, "sipo-ser");
    _srck   = _output(chip_path, (int)srck, false, "sipo-srck");
    _rck    = _output(chip_path, (int)rck, false, "sipo-rck");
    _srclr  = _output(chip_path, srclr, true, "sipo-srclr");
    _g      = _output(chip_path, g, false, "sipo-g");
}

SiPoConnectionLinux::~SiPoConnectionLinux() {
    close();
}

void SiPoConnectionLinux::write(const uint8_t* data, size_t len) {
    if (!_enabled || !_rck) return;  // disabled, or closed
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
                _ser_in->set((data[i] >> bit) & 1);
                _srck->set(true);
                _srck->set(false);
            }
        }
    }
    _latch();
}

void SiPoConnectionLinux::_latch() {
    _rck->set(true);
    _rck->set(false);
}

void SiPoConnectionLinux::clear() {
    if (!_srclr)
        throw std::runtime_error("SRCLR not configured");
    _srclr->set(false);
    _srclr->set(true);
}

void SiPoConnectionLinux::set_output_enable(bool enabled) {
    if (!_g)
        throw std::runtime_error("G not configured");
    _g->set(!enabled);
}

void SiPoConnectionLinux::close() {
    if (_fd >= 0) { ::close(_fd); _fd = -1; }
    _ser_in.reset();
    _srck.reset();
    _rck.reset();
    _srclr.reset();
    _g.reset();
}
#endif // __linux__
