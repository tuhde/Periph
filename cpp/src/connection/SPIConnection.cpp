#include "SPIConnection.h"

void SPIConnection::read(uint32_t reg, uint8_t* buf, size_t len) {
    uint8_t cmd = static_cast<uint8_t>(reg) | _readBit;
    if (len > 1 && _multiByteBit) cmd |= _multiByteBit;
    write_read(&cmd, 1, buf, len);
}

void SPIConnection::write(uint32_t reg, const uint8_t* data, size_t len) {
    uint8_t cmd = (static_cast<uint8_t>(reg) & static_cast<uint8_t>(~_readBit)) | ((len > 1 && _multiByteBit) ? _multiByteBit : 0);
    uint8_t payload[17];
    payload[0] = cmd;
    memcpy(payload + 1, data, len);
    Connection::write(payload, len + 1);
}

void SPIConnection::idle_clocks(uint32_t n) {
    bool wasHeld = _held;
    if (wasHeld) deselect();
    _bus.beginTransaction(_settings);
    digitalWrite(_cs_pin, HIGH);
    for (uint32_t i = 0; i < (n + 7) / 8; i++)
        _bus.transfer(0xFF);
    _bus.endTransaction();
    if (wasHeld) select();
}

void SPIConnection::select() {
    if (_held) return;
    _bus.beginTransaction(_settings);
    digitalWrite(_cs_pin, LOW);
    _held = true;
}

void SPIConnection::deselect() {
    if (!_held) return;
    digitalWrite(_cs_pin, HIGH);
    _bus.endTransaction();
    _held = false;
}

void SPIConnection::_write(const uint8_t* data, size_t len) {
    if (!_held) { _bus.beginTransaction(_settings); digitalWrite(_cs_pin, LOW); }
    for (size_t i = 0; i < len; i++)
        _bus.transfer(data[i]);
    if (!_held) { digitalWrite(_cs_pin, HIGH); _bus.endTransaction(); }
}

void SPIConnection::_read(uint8_t* buf, size_t len) {
    if (!_held) { _bus.beginTransaction(_settings); digitalWrite(_cs_pin, LOW); }
    for (size_t i = 0; i < len; i++)
        buf[i] = _bus.transfer(0xFF);   // MOSI idles high (SD cards read other levels as commands)
    if (!_held) { digitalWrite(_cs_pin, HIGH); _bus.endTransaction(); }
}

void SPIConnection::_write_read(const uint8_t* data, size_t data_len,
                                 uint8_t* buf, size_t buf_len) {
    if (!_held) { _bus.beginTransaction(_settings); digitalWrite(_cs_pin, LOW); }
    for (size_t i = 0; i < data_len; i++)
        _bus.transfer(data[i]);
    for (size_t i = 0; i < buf_len; i++)
        buf[i] = _bus.transfer(0xFF);
    if (!_held) { digitalWrite(_cs_pin, HIGH); _bus.endTransaction(); }
}
