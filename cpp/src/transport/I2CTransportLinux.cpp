#ifdef __linux__
#include "I2CTransportLinux.h"
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cerrno>

I2CTransportLinux::I2CTransportLinux(int bus, uint8_t addr) : _addr(addr) {
    char path[32];
    snprintf(path, sizeof(path), "/dev/i2c-%d", bus);
    _fd = open(path, O_RDWR);
    if (_fd < 0) { perror(path); exit(1); }
    if (ioctl(_fd, I2C_SLAVE, addr) < 0) { perror("ioctl I2C_SLAVE"); close(_fd); exit(1); }
}

I2CTransportLinux::~I2CTransportLinux() {
    if (_fd >= 0) close(_fd);
}

void I2CTransportLinux::write(const uint8_t* data, size_t len) {
    ::write(_fd, data, len);
}

void I2CTransportLinux::read(uint8_t* buf, size_t len) {
    ::read(_fd, buf, len);
}

void I2CTransportLinux::write_read(const uint8_t* data, size_t data_len,
                                    uint8_t* buf, size_t buf_len) {
    struct i2c_msg msgs[2];
    msgs[0].addr  = _addr;
    msgs[0].flags = 0;
    msgs[0].len   = static_cast<uint16_t>(data_len);
    msgs[0].buf   = const_cast<uint8_t*>(data);

    msgs[1].addr  = _addr;
    msgs[1].flags = I2C_M_RD;
    msgs[1].len   = static_cast<uint16_t>(buf_len);
    msgs[1].buf   = buf;

    struct i2c_rdwr_ioctl_data rdwr;
    rdwr.msgs  = msgs;
    rdwr.nmsgs = 2;

    if (ioctl(_fd, I2C_RDWR, &rdwr) < 0) perror("ioctl I2C_RDWR");
}
#endif // __linux__
