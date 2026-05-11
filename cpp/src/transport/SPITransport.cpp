#include "SPITransport.h"

void SPITransport::write(const uint8_t* data, size_t len) {
    _bus.beginTransaction(_settings);
    digitalWrite(_cs_pin, LOW);
    for (size_t i = 0; i < len; i++)
        _bus.transfer(data[i]);
    digitalWrite(_cs_pin, HIGH);
    _bus.endTransaction();
}

void SPITransport::read(uint8_t* buf, size_t len) {
    _bus.beginTransaction(_settings);
    digitalWrite(_cs_pin, LOW);
    for (size_t i = 0; i < len; i++)
        buf[i] = _bus.transfer(0x00);
    digitalWrite(_cs_pin, HIGH);
    _bus.endTransaction();
}

void SPITransport::write_read(const uint8_t* data, size_t data_len,
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
