#include <string.h>
#include "I2CConnection.h"

void I2CConnection::_write(const uint8_t* data, size_t len) {
    _bus.beginTransmission(_addr);
    _bus.write(data, len);
    _setOk(_bus.endTransmission() == 0);
}

void I2CConnection::_read(uint8_t* buf, size_t len) {
    size_t got = _bus.requestFrom(_addr, (uint8_t)len);
    for (size_t i = 0; i < len; i++)
        buf[i] = _bus.read();
    bool good = (got == len);
    if (!good) memset(buf, 0, len);   // never leave stale bytes behind
    _setOk(good);
}

void I2CConnection::_write_read(const uint8_t* data, size_t data_len,
                                 uint8_t* buf, size_t buf_len) {
    _bus.beginTransmission(_addr);
    _bus.write(data, data_len);
    uint8_t rc = _bus.endTransmission(false);  // repeated start — keep bus
    size_t got = _bus.requestFrom(_addr, (uint8_t)buf_len);
    for (size_t i = 0; i < buf_len; i++)
        buf[i] = _bus.read();
    bool good = (rc == 0 && got == buf_len);
    if (!good) memset(buf, 0, buf_len);
    _setOk(good);
}
