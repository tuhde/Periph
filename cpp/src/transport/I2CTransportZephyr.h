#pragma once
#include <zephyr/drivers/i2c.h>
#include "Transport.h"

class I2CTransportZephyr : public Transport {
public:
    I2CTransportZephyr(const struct device *dev, uint8_t addr)
        : _dev(dev), _addr(addr) {}

    void write(const uint8_t* data, size_t len) override {
        i2c_write(_dev, data, len, _addr);
    }

    void read(uint8_t* buf, size_t len) override {
        i2c_read(_dev, buf, len, _addr);
    }

    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        i2c_write_read(_dev, _addr, data, data_len, buf, buf_len);
    }

private:
    const struct device *_dev;
    uint8_t _addr;
};
