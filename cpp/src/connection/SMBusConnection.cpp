#include <string.h>
#include "SMBusConnection.h"

SMBusConnection::SMBusConnection(TwoWire& bus, uint8_t addr, bool pec,
                                  InputPin* intPin, OutputPin* enPin, uint8_t regBytes, bool enActiveHigh)
    : RegisterConnection(intPin, enPin, regBytes, enActiveHigh), _bus(bus), _addr(addr), _pec(pec) {
    if (addr < 0x08 || addr > 0x77) _valid = false;
}

uint8_t SMBusConnection::_crc8(const uint8_t* data, size_t len, uint8_t crc) {
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++)
            crc = (crc & 0x80) ? (crc << 1) ^ 0x07 : crc << 1;
    }
    return crc;
}

void SMBusConnection::_write(const uint8_t* data, size_t len) {
    _valid = true;
    _bus.beginTransmission(_addr);
    _bus.write(data, len);
    if (_pec) {
        uint8_t addr_byte = _addr << 1;
        uint8_t crc = _crc8(&addr_byte, 1);
        crc = _crc8(data, len, crc);
        _bus.write(crc);
    }
    _setOk(_bus.endTransmission() == 0);
}

void SMBusConnection::_read(uint8_t* buf, size_t len) {
    _valid = true;
    size_t want = _pec ? len + 1 : len;
    size_t got = _bus.requestFrom(_addr, (uint8_t)want);
    for (size_t i = 0; i < len; i++)
        buf[i] = _bus.read();
    bool xfer_ok = (got == want);
    if (_pec) {
        uint8_t recv_pec = _bus.read();
        uint8_t addr_byte = (_addr << 1) | 1;
        uint8_t crc = _crc8(&addr_byte, 1);
        crc = _crc8(buf, len, crc);
        _valid = (crc == recv_pec);
    }
    if (!xfer_ok) memset(buf, 0, len);   // never leave stale bytes behind
    _setOk(xfer_ok && _valid);
}

void SMBusConnection::_write_read(const uint8_t* data, size_t data_len,
                                   uint8_t* buf, size_t buf_len) {
    _valid = true;
    _bus.beginTransmission(_addr);
    _bus.write(data, data_len);
    uint8_t rc = _bus.endTransmission(false);  // repeated start
    size_t want = _pec ? buf_len + 1 : buf_len;
    size_t got = _bus.requestFrom(_addr, (uint8_t)want);
    for (size_t i = 0; i < buf_len; i++)
        buf[i] = _bus.read();
    bool xfer_ok = (rc == 0 && got == want);
    if (_pec) {
        uint8_t recv_pec = _bus.read();
        uint8_t aw = _addr << 1;
        uint8_t ar = (_addr << 1) | 1;
        uint8_t crc = _crc8(&aw, 1);
        crc = _crc8(data, data_len, crc);
        crc = _crc8(&ar, 1, crc);
        crc = _crc8(buf, buf_len, crc);
        _valid = (crc == recv_pec);
    }
    if (!xfer_ok) memset(buf, 0, buf_len);
    _setOk(xfer_ok && _valid);
}
