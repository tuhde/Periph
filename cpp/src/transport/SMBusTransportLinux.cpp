#ifdef __linux__
#include "SMBusTransportLinux.h"
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>

SMBusTransportLinux::SMBusTransportLinux(int bus, uint8_t addr, bool pec)
    : _addr(addr), _pec(pec) {
    if (addr < 0x08 || addr > 0x77)
        throw std::runtime_error("SMBus address must be in range 0x08-0x77");
    char path[32];
    snprintf(path, sizeof(path), "/dev/i2c-%d", bus);
    _fd = open(path, O_RDWR);
    if (_fd < 0)
        throw std::runtime_error(std::string("Failed to open ") + path + ": " + strerror(errno));
    if (ioctl(_fd, I2C_SLAVE, addr) < 0) {
        close(_fd);
        throw std::runtime_error(std::string("I2C_SLAVE on ") + path + ": " + strerror(errno));
    }
}

SMBusTransportLinux::~SMBusTransportLinux() {
    if (_fd >= 0) close(_fd);
}

uint8_t SMBusTransportLinux::_crc8(const uint8_t* data, size_t len, uint8_t crc) {
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++)
            crc = (crc & 0x80) ? (crc << 1) ^ 0x07 : crc << 1;
    }
    return crc;
}

void SMBusTransportLinux::write(const uint8_t* data, size_t len) {
    _valid = true;
    if (_pec) {
        uint8_t addr_byte = _addr << 1;
        uint8_t crc = _crc8(&addr_byte, 1);
        crc = _crc8(data, len, crc);
        // +1 for PEC byte; chip drivers must not exceed this bound
        uint8_t buf[256];
        memcpy(buf, data, len);
        buf[len] = crc;
        if (::write(_fd, buf, len + 1) < 0)
            throw std::runtime_error(std::string("SMBus write: ") + strerror(errno));
    } else {
        if (::write(_fd, data, len) < 0)
            throw std::runtime_error(std::string("SMBus write: ") + strerror(errno));
    }
}

void SMBusTransportLinux::read(uint8_t* buf, size_t len) {
    _valid = true;
    if (_pec) {
        uint8_t tmp[256];
        if (::read(_fd, tmp, len + 1) < 0)
            throw std::runtime_error(std::string("SMBus read: ") + strerror(errno));
        memcpy(buf, tmp, len);
        uint8_t addr_byte = (_addr << 1) | 1;
        uint8_t crc = _crc8(&addr_byte, 1);
        crc = _crc8(buf, len, crc);
        _valid = (crc == tmp[len]);
    } else {
        if (::read(_fd, buf, len) < 0)
            throw std::runtime_error(std::string("SMBus read: ") + strerror(errno));
    }
}

void SMBusTransportLinux::write_read(const uint8_t* data, size_t data_len,
                                      uint8_t* buf, size_t buf_len) {
    _valid = true;
    size_t read_len = _pec ? buf_len + 1 : buf_len;
    uint8_t read_buf[256];

    struct i2c_msg msgs[2];
    msgs[0].addr  = _addr;
    msgs[0].flags = 0;
    msgs[0].len   = static_cast<uint16_t>(data_len);
    msgs[0].buf   = const_cast<uint8_t*>(data);

    msgs[1].addr  = _addr;
    msgs[1].flags = I2C_M_RD;
    msgs[1].len   = static_cast<uint16_t>(read_len);
    msgs[1].buf   = read_buf;

    struct i2c_rdwr_ioctl_data rdwr;
    rdwr.msgs  = msgs;
    rdwr.nmsgs = 2;

    if (ioctl(_fd, I2C_RDWR, &rdwr) < 0)
        throw std::runtime_error(std::string("SMBus write_read: ") + strerror(errno));

    memcpy(buf, read_buf, buf_len);

    if (_pec) {
        uint8_t aw = _addr << 1;
        uint8_t ar = (_addr << 1) | 1;
        uint8_t crc = _crc8(&aw, 1);
        crc = _crc8(data, data_len, crc);
        crc = _crc8(&ar, 1, crc);
        crc = _crc8(buf, buf_len, crc);
        _valid = (crc == read_buf[buf_len]);
    }
}
#endif // __linux__
