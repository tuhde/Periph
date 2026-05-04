#pragma once
#include <stdint.h>
#include <stddef.h>
#include "Transport.h"

class I2CTransportLinux : public Transport {
public:
    I2CTransportLinux(int bus, uint8_t addr);
    ~I2CTransportLinux();

    void write(const uint8_t* data, size_t len) override;
    void read(uint8_t* buf, size_t len) override;
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    int     _fd;
    uint8_t _addr;
};
