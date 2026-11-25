#include "INA260.h"

INA260Minimal::INA260Minimal(RegisterConnection& connection)
    : _connection(connection) {}

void INA260Minimal::_write_reg(uint8_t reg, uint16_t value) {
    uint8_t buf[2] = { (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _connection.write(reg, buf, 2);
}

uint16_t INA260Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[2];
    _connection.read(reg, buf, 2);
    return ((uint16_t)buf[0] << 8) | buf[1];
}

int16_t INA260Minimal::_read_reg_signed(uint8_t reg) {
    return static_cast<int16_t>(_read_reg(reg));
}

float INA260Minimal::voltage() {
    return _read_reg(REG_BUS) * 1.25e-3f;
}

float INA260Minimal::current() {
    return _read_reg_signed(REG_CURRENT) * 1.25e-3f;
}

float INA260Minimal::power() {
    return _read_reg(REG_POWER) * 10e-3f;
}

// INA260Full

INA260Full::INA260Full(RegisterConnection& connection)
    : INA260Minimal(connection) {}

void INA260Full::_write_config(uint8_t mode) {
    uint16_t config = 0x6000
                    | ((uint16_t)_avg << 9)
                    | ((uint16_t)_vbus_ct << 6)
                    | ((uint16_t)_ish_ct << 3)
                    | (mode & 0x07);
    _write_reg(REG_CONFIG, config);
}

void INA260Full::configure(uint8_t avg, uint8_t vbus_ct, uint8_t ish_ct, uint8_t mode) {
    _avg = avg & 0x07;
    _vbus_ct = vbus_ct & 0x07;
    _ish_ct = ish_ct & 0x07;
    _mode = mode & 0x07;
    _write_config(_mode);
}

void INA260Full::trigger(uint8_t mode) {
    _write_config(mode);
}

bool INA260Full::conversion_ready() {
    return (_read_reg(REG_MASK) & 0x0008) != 0;
}

bool INA260Full::overflow() {
    return (_read_reg(REG_MASK) & 0x0004) != 0;
}

void INA260Full::set_alert(uint16_t function, float limit, bool polarity, bool latch) {
    float scaled;
    bool has_limit = true;
    if (function == OCL || function == UCL || function == BOL || function == BUL)
        scaled = limit / 1.25e-3f;
    else if (function == POL)
        scaled = limit / 10e-3f;
    else {
        scaled = 0.0f;
        has_limit = false;
    }
    if (has_limit) {
        int32_t raw = (int32_t)(scaled + (scaled < 0.0f ? -0.5f : 0.5f));
        _write_reg(REG_ALERT, (uint16_t)(raw & 0xFFFF));
    }
    uint16_t mask = function | (polarity ? 0x0002u : 0u) | (latch ? 0x0001u : 0u);
    _write_reg(REG_MASK, mask);
}

void INA260Full::clear_alert() {
    _write_reg(REG_MASK, 0);
}

uint16_t INA260Full::alert_flags() {
    return _read_reg(REG_MASK);
}

void INA260Full::reset() {
    _write_reg(REG_CONFIG, 0x8000);
    _avg = 0;
    _vbus_ct = 4;
    _ish_ct = 4;
    _mode = 7;
}

void INA260Full::shutdown() {
    _write_config(MODE_POWER_DOWN);
}

void INA260Full::wake() {
    _write_config(_mode);
}

uint16_t INA260Full::manufacturer_id() {
    return _read_reg(REG_MFR_ID);
}

uint16_t INA260Full::die_id() {
    return _read_reg(REG_DIE_ID);
}
