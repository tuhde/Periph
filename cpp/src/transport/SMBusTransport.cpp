#include "SMBusTransport.h"

SMBusTransport::SMBusTransport(TwoWire& bus, uint8_t addr, bool pec)
    : _bus(bus), _addr(addr), _pec(pec) {}

uint8_t SMBusTransport::_crc8(const uint8_t* data, size_t len, uint8_t crc) {
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++)
            crc = (crc & 0x80) ? (crc << 1) ^ 0x07 : crc << 1;
    }
    return crc;
}

void SMBusTransport::write(const uint8_t* data, size_t len) {
    _valid = true;
    _bus.beginTransmission(_addr);
    _bus.write(data, len);
    if (_pec) {
        uint8_t addr_byte = _addr << 1;
        uint8_t crc = _crc8(&addr_byte, 1);
        crc = _crc8(data, len, crc);
        _bus.write(crc);
    }
    _bus.endTransmission();
}

void SMBusTransport::read(uint8_t* buf, size_t len) {
    _valid = true;
    size_t req = _pec ? len + 1 : len;
    _bus.requestFrom(_addr, (uint8_t)req);
    for (size_t i = 0; i < req; i++)
        buf[i] = _bus.read();
    if (_pec) {
        uint8_t addr_byte = (_addr << 1) | 1;
        uint8_t crc = _crc8(&addr_byte, 1);
        crc = _crc8(buf, len, crc);
        _valid = (crc == buf[len]);
    }
}

void SMBusTransport::write_read(const uint8_t* data, size_t data_len,
                                 uint8_t* buf, size_t buf_len) {
    _valid = true;
    _bus.beginTransmission(_addr);
    _bus.write(data, data_len);
    _bus.endTransmission(false);  // repeated start
    size_t req = _pec ? buf_len + 1 : buf_len;
    _bus.requestFrom(_addr, (uint8_t)req);
    for (size_t i = 0; i < req; i++)
        buf[i] = _bus.read();
    if (_pec) {
        uint8_t aw = _addr << 1;
        uint8_t ar = (_addr << 1) | 1;
        uint8_t crc = _crc8(&aw, 1);
        crc = _crc8(data, data_len, crc);
        crc = _crc8(&ar, 1, crc);
        crc = _crc8(buf, buf_len, crc);
        _valid = (crc == buf[buf_len]);
    }
}
