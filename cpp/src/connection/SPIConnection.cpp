#include "SPIConnection.h"

void SPIConnection::read(uint8_t reg, uint8_t* buf, size_t len) {
    uint8_t cmd = reg | _readBit;
    if (len > 1 && _multiByteBit) cmd |= _multiByteBit;
    write_read(&cmd, 1, buf, len);
}

void SPIConnection::write(uint8_t reg, const uint8_t* data, size_t len) {
    uint8_t cmd = reg | ((len > 1 && _multiByteBit) ? _multiByteBit : 0);
    uint8_t payload[17];
    payload[0] = cmd;
    memcpy(payload + 1, data, len);
    Connection::write(payload, len + 1);
}

void SPIConnection::_write(const uint8_t* data, size_t len) {
    _bus.beginTransaction(_settings);
    digitalWrite(_cs_pin, LOW);
    for (size_t i = 0; i < len; i++)
        _bus.transfer(data[i]);
    digitalWrite(_cs_pin, HIGH);
    _bus.endTransaction();
}

void SPIConnection::_read(uint8_t* buf, size_t len) {
    _bus.beginTransaction(_settings);
    digitalWrite(_cs_pin, LOW);
    for (size_t i = 0; i < len; i++)
        buf[i] = _bus.transfer(0x00);
    digitalWrite(_cs_pin, HIGH);
    _bus.endTransaction();
}

void SPIConnection::_write_read(const uint8_t* data, size_t data_len,
                                 uint8_t* buf, size_t buf_len) {
    _bus.beginTransaction(_settings);
    digitalWrite(_cs_pin, LOW);
    for (size_t i = 0; i < data_len; i++)
        _bus.transfer(data[i]);
    for (size_t i = 0; i < buf_len; i++)
        buf[i] = _bus.transfer(0x00);
    digitalWrite(_cs_pin, HIGH);
    _bus.endTransaction();
}
