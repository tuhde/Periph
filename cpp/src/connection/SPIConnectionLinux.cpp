#ifdef __linux__
#include "SPIConnectionLinux.h"
#include <linux/spi/spidev.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

SPIConnectionLinux::SPIConnectionLinux(int bus_num, int device_num,
                                       uint8_t mode, uint32_t max_speed_hz,
                                       uint8_t readBit, uint8_t multiByteBit,
                                       InputPin* intPin, OutputPin* enPin, bool enActiveHigh,
                                       OutputPin* csPin)
    : RegisterConnection(intPin, enPin, 1, enActiveHigh), _speed_hz(max_speed_hz),
      _readBit(readBit), _multiByteBit(multiByteBit), _cs(csPin)
{
    char path[32];
    snprintf(path, sizeof(path), "/dev/spidev%d.%d", bus_num, device_num);
    _fd = open(path, O_RDWR);
    if (_fd < 0)
        throw std::runtime_error(std::string("Failed to open ") + path + ": " + strerror(errno));
    if (_cs) mode |= SPI_NO_CS;   // the kernel must not touch CS; this connection drives the GPIO
    if (ioctl(_fd, SPI_IOC_WR_MODE, &mode) < 0) {
        close(_fd);
        throw std::runtime_error(std::string("SPI_IOC_WR_MODE on ") + path + ": " + strerror(errno));
    }
    if (ioctl(_fd, SPI_IOC_WR_MAX_SPEED_HZ, &max_speed_hz) < 0) {
        close(_fd);
        throw std::runtime_error(std::string("SPI_IOC_WR_MAX_SPEED_HZ on ") + path + ": " + strerror(errno));
    }
    if (_cs) _cs->set(true);   // CS idles high
}

SPIConnectionLinux::~SPIConnectionLinux() {
    if (_fd >= 0) close(_fd);
}

void SPIConnectionLinux::read(uint32_t reg, uint8_t* buf, size_t len) {
    uint8_t cmd = static_cast<uint8_t>(reg) | _readBit;
    if (len > 1 && _multiByteBit) cmd |= _multiByteBit;
    write_read(&cmd, 1, buf, len);
}

void SPIConnectionLinux::write(uint32_t reg, const uint8_t* data, size_t len) {
    uint8_t cmd = (static_cast<uint8_t>(reg) & static_cast<uint8_t>(~_readBit)) | ((len > 1 && _multiByteBit) ? _multiByteBit : 0);
    uint8_t payload[17];
    payload[0] = cmd;
    memcpy(payload + 1, data, len);
    Connection::write(payload, len + 1);
}

void SPIConnectionLinux::_csLow()  { if (_cs && !_held) _cs->set(false); }
void SPIConnectionLinux::_csHigh() { if (_cs && !_held) _cs->set(true); }

// One full-duplex transfer; rx may be nullptr.
void SPIConnectionLinux::_xfer(const uint8_t* tx, uint8_t* rx, size_t len) {
    struct spi_ioc_transfer tr = {};
    tr.tx_buf        = reinterpret_cast<uintptr_t>(tx);
    tr.rx_buf        = reinterpret_cast<uintptr_t>(rx);
    tr.len           = static_cast<uint32_t>(len);
    tr.speed_hz      = _speed_hz;
    tr.bits_per_word = 8;
    if (ioctl(_fd, SPI_IOC_MESSAGE(1), &tr) < 0)
        throw std::runtime_error(std::string("SPI transfer: ") + strerror(errno));
}

void SPIConnectionLinux::idle_clocks(uint32_t n) {
    if (!_cs) throw std::runtime_error("idle_clocks needs a GPIO chip select (SPI_NO_CS)");
    _cs->set(true);
    uint8_t ff[16];
    memset(ff, 0xFF, sizeof(ff));
    uint32_t bytes = (n + 7) / 8;
    while (bytes) {
        size_t chunk = bytes > sizeof(ff) ? sizeof(ff) : bytes;
        _xfer(ff, nullptr, chunk);
        bytes -= static_cast<uint32_t>(chunk);
    }
    if (_held) _cs->set(false);
}

bool SPIConnectionLinux::set_speed(uint32_t hz) {
    if (ioctl(_fd, SPI_IOC_WR_MAX_SPEED_HZ, &hz) < 0) return false;
    _speed_hz = hz;
    return true;
}

void SPIConnectionLinux::select() {
    if (!_cs) throw std::runtime_error("select needs a GPIO chip select (SPI_NO_CS)");
    _cs->set(false);
    _held = true;
}

void SPIConnectionLinux::deselect() {
    _held = false;
    if (_cs) _cs->set(true);
}

void SPIConnectionLinux::_write(const uint8_t* data, size_t len) {
    _csLow();
    try { _xfer(data, nullptr, len); } catch (...) { _csHigh(); throw; }
    _csHigh();
}

// Reads clock 0xFF on MOSI (SD cards read other levels as commands), in 256-byte chunks.
void SPIConnectionLinux::_read(uint8_t* buf, size_t len) {
    uint8_t ff[256];
    memset(ff, 0xFF, sizeof(ff));
    _csLow();
    try {
        while (len) {
            size_t chunk = len > sizeof(ff) ? sizeof(ff) : len;
            _xfer(ff, buf, chunk);
            buf += chunk;
            len -= chunk;
        }
    } catch (...) { _csHigh(); throw; }
    _csHigh();
}

void SPIConnectionLinux::_write_read(const uint8_t* data, size_t data_len,
                                      uint8_t* buf, size_t buf_len) {
    // Two transfers in one ioctl call: CS stays asserted between them. MOSI idles
    // high (0xFF) in the read phase.
    std::vector<uint8_t> pad(buf_len, 0xFF);
    struct spi_ioc_transfer tr[2] = {};
    tr[0].tx_buf        = reinterpret_cast<uintptr_t>(data);
    tr[0].len           = static_cast<uint32_t>(data_len);
    tr[0].speed_hz      = _speed_hz;
    tr[0].bits_per_word = 8;
    tr[0].cs_change     = 0;

    tr[1].tx_buf        = reinterpret_cast<uintptr_t>(pad.data());
    tr[1].rx_buf        = reinterpret_cast<uintptr_t>(buf);
    tr[1].len           = static_cast<uint32_t>(buf_len);
    tr[1].speed_hz      = _speed_hz;
    tr[1].bits_per_word = 8;

    _csLow();
    int rc = ioctl(_fd, SPI_IOC_MESSAGE(2), tr);
    _csHigh();
    if (rc < 0)
        throw std::runtime_error(std::string("SPI write_read: ") + strerror(errno));
}
#endif // __linux__
