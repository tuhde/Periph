#include "MB85RC.h"

// --- Minimal ---

MB85RCMinimal::MB85RCMinimal(RegisterConnection& connection)
    : _connection(connection) {}

uint8_t MB85RCMinimal::read_byte(uint16_t address) {
    if (address >= SIZE) return 0;
    uint8_t val = 0;
    _connection.read(address, &val, 1);
    return val;
}

bool MB85RCMinimal::write_byte(uint16_t address, uint8_t value) {
    if (address >= SIZE) return false;
    _connection.write(address, &value, 1);
    return true;
}

// --- Full ---

MB85RCFull::MB85RCFull(RegisterConnection& connection)
    : MB85RCMinimal(connection) {}

bool MB85RCFull::_in_range(uint16_t address, uint32_t length) {
    return address < SIZE && (uint32_t)address + length <= SIZE;
}

void MB85RCFull::set_max_chunk(uint8_t value) {
    if (value < 1) value = 1;
    if (value > 32) value = 32;
    _max_chunk = value;
}

bool MB85RCFull::read(uint16_t address, uint8_t* buf, uint16_t length) {
    if (!_in_range(address, length)) return false;
    while (length > 0) {
        uint8_t n = length > _max_chunk ? _max_chunk : (uint8_t)length;
        _connection.read(address, buf, n);
        address += n;
        buf += n;
        length -= n;
    }
    return true;
}

bool MB85RCFull::write(uint16_t address, const uint8_t* data, uint16_t length) {
    if (!_in_range(address, length)) return false;
    while (length > 0) {
        uint8_t n = length > _max_chunk ? _max_chunk : (uint8_t)length;
        _connection.write(address, data, n);
        address += n;
        data += n;
        length -= n;
    }
    return true;
}

bool MB85RCFull::fill(uint16_t address, uint16_t length, uint8_t value) {
    if (!_in_range(address, length)) return false;
    uint8_t chunk[32];
    for (uint8_t i = 0; i < _max_chunk; i++) chunk[i] = value;
    while (length > 0) {
        uint8_t n = length > _max_chunk ? _max_chunk : (uint8_t)length;
        _connection.write(address, chunk, n);
        address += n;
        length -= n;
    }
    return true;
}

void MB85RCFull::read_device_id(RegisterConnection& idConnection, uint16_t* manufacturer,
                                uint8_t* density, uint8_t* product, uint8_t address) {
    uint8_t word = DEVICE_ID_BASE + ((address & 0x07) << 1);
    uint8_t raw[3] = {0, 0, 0};
    idConnection.read(word, raw, 3);
    *manufacturer = ((uint16_t)raw[0] << 4) | (raw[1] >> 4);
    *density = raw[1] & 0x0F;
    *product = raw[2];
}
