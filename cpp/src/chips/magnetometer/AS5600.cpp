#include "AS5600.h"

AS5600Minimal::AS5600Minimal(Transport& transport)
    : _transport(transport) {
    uint8_t status = _read_reg8(REG_STATUS);
    if (!(status & STATUS_MD)) {
        // In C++ we use a simple flag; caller should check is_magnet_detected()
    }
}

uint8_t AS5600Minimal::_read_reg8(uint8_t reg) {
    uint8_t buf[1];
    _transport.write_read(&reg, 1, buf, 1);
    return buf[0];
}

uint16_t AS5600Minimal::_read_reg16(uint8_t reg) {
    uint8_t buf[2];
    _transport.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0] << 8) | buf[1];
}

void AS5600Minimal::_write_reg8(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

void AS5600Minimal::_write_reg16(uint8_t reg, uint16_t value) {
    uint8_t buf[3] = { reg, (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _transport.write(buf, 3);
}

float AS5600Minimal::angle() {
    return (float)angle_raw() * 360.0f / 4096.0f;
}

uint16_t AS5600Minimal::angle_raw() {
    uint16_t raw = _read_reg16(REG_ANGLE_H);
    return raw & 0x0FFF;
}

bool AS5600Minimal::is_magnet_detected() {
    return (_read_reg8(REG_STATUS) & STATUS_MD) != 0;
}

bool AS5600Minimal::is_magnet_too_strong() {
    return (_read_reg8(REG_STATUS) & STATUS_MH) != 0;
}

bool AS5600Minimal::is_magnet_too_weak() {
    return (_read_reg8(REG_STATUS) & STATUS_ML) != 0;
}

// AS5600Full

AS5600Full::AS5600Full(Transport& transport)
    : AS5600Minimal(transport) {}

uint16_t AS5600Full::raw_angle() {
    uint16_t raw = _read_reg16(REG_RAW_ANGLE_H);
    return raw & 0x0FFF;
}

float AS5600Full::raw_angle_degrees() {
    return (float)raw_angle() * 360.0f / 4096.0f;
}

uint8_t AS5600Full::agc() {
    return _read_reg8(REG_AGC);
}

uint16_t AS5600Full::magnitude() {
    uint16_t raw = _read_reg16(REG_MAGNITUDE_H);
    return raw & 0x0FFF;
}

uint8_t AS5600Full::status_byte() {
    return _read_reg8(REG_STATUS);
}

void AS5600Full::configure(uint8_t pm, uint8_t hyst, uint8_t outs,
                           uint8_t pwmf, uint8_t sf, uint8_t fth, bool wd) {
    uint8_t conf_h = _read_reg8(REG_CONF_H);
    uint8_t conf_l = _read_reg8(REG_CONF_L);
    conf_h = (conf_h & 0xC0) | ((wd ? 1 : 0) << 5) | ((fth & 0x07) << 2) | (sf & 0x03);
    conf_l = ((pwmf & 0x03) << 6) | ((outs & 0x03) << 4) | ((hyst & 0x03) << 2) | (pm & 0x03);
    _write_reg16(REG_CONF_H, ((uint16_t)conf_h << 8) | conf_l);
}

void AS5600Full::set_zero_position(uint16_t pos) {
    _write_reg8(REG_ZPOS_H, (pos >> 8) & 0x0F);
    _write_reg8(REG_ZPOS_L, pos & 0xFF);
}

void AS5600Full::set_max_position(uint16_t pos) {
    _write_reg8(REG_MPOS_H, (pos >> 8) & 0x0F);
    _write_reg8(REG_MPOS_L, pos & 0xFF);
}

void AS5600Full::set_max_angle(uint16_t span) {
    _write_reg8(REG_MANG_H, (span >> 8) & 0x0F);
    _write_reg8(REG_MANG_L, span & 0xFF);
}

uint16_t AS5600Full::zero_position() {
    uint16_t raw = _read_reg16(REG_ZPOS_H);
    return raw & 0x0FFF;
}

uint16_t AS5600Full::max_position() {
    uint16_t raw = _read_reg16(REG_MPOS_H);
    return raw & 0x0FFF;
}

uint16_t AS5600Full::max_angle() {
    uint16_t raw = _read_reg16(REG_MANG_H);
    return raw & 0x0FFF;
}

uint8_t AS5600Full::burn_count() {
    return _read_reg8(REG_ZMCO) & 0x03;
}

void AS5600Full::burn_angle() {
    uint8_t status = _read_reg8(REG_STATUS);
    if (!(status & STATUS_MD)) {
        return;  // magnet not detected; in C++ we silently return
    }
    uint8_t zmco = _read_reg8(REG_ZMCO) & 0x03;
    if (zmco >= 3) {
        return;  // ZMCO limit reached
    }
    _write_reg8(REG_BURN, 0x80);
}

void AS5600Full::burn_setting() {
    uint8_t zmco = _read_reg8(REG_ZMCO) & 0x03;
    if (zmco != 0) {
        return;  // ZMCO must be 0
    }
    _write_reg8(REG_BURN, 0x40);
}
