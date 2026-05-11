#include "I2CTransport.h"

void I2CTransport::write(const uint8_t* data, size_t len) {
    _bus.beginTransmission(_addr);
    _bus.write(data, len);
    _bus.endTransmission();
}

void I2CTransport::read(uint8_t* buf, size_t len) {
    _bus.requestFrom(_addr, (uint8_t)len);
    for (size_t i = 0; i < len; i++)
        buf[i] = _bus.read();
}

void I2CTransport::write_read(const uint8_t* data, size_t data_len,
                               uint8_t* buf, size_t buf_len) {
    _bus.beginTransmission(_addr);
    _bus.write(data, data_len);
    _bus.endTransmission(false);  // repeated start — keep bus
    _bus.requestFrom(_addr, (uint8_t)buf_len);
    for (size_t i = 0; i < buf_len; i++)
        buf[i] = _bus.read();
}
