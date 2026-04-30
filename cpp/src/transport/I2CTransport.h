#pragma once
#include <Wire.h>
#include "Transport.h"

class I2CTransport : public Transport {
public:
    I2CTransport(TwoWire& bus, uint8_t addr) : _bus(bus), _addr(addr) {}

    void write(const uint8_t* data, size_t len) override;
    void read(uint8_t* buf, size_t len) override;
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override;

private:
    TwoWire& _bus;
    uint8_t  _addr;
};
