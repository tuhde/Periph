#include "INA226.h"

INA226Minimal::INA226Minimal(Transport& transport, float r_shunt, float max_current)
    : _transport(transport) {
    _current_lsb = max_current / 32768.0f;
    _cal = (uint16_t)(0.00512f / (_current_lsb * r_shunt));
    _write_reg(REG_CONFIG, CONFIG_DEFAULT);
    _write_reg(REG_CAL, _cal);
}

void INA226Minimal::_write_reg(uint8_t reg, uint16_t value) {
    uint8_t buf[3] = { reg, (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _transport.write(buf, 3);
}

uint16_t INA226Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[2];
    _transport.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0] << 8) | buf[1];
}

int16_t INA226Minimal::_read_reg_signed(uint8_t reg) {
    return static_cast<int16_t>(_read_reg(reg));
}

float INA226Minimal::voltage() {
    return _read_reg(REG_BUS) * 1.25e-3f;
}

float INA226Minimal::shunt_voltage() {
    return _read_reg_signed(REG_SHUNT) * 2.5e-6f;
}

float INA226Minimal::current() {
    return _read_reg_signed(REG_CURRENT) * _current_lsb;
}

float INA226Minimal::power() {
    return _read_reg(REG_POWER) * 25.0f * _current_lsb;
}

// INA226Full

INA226Full::INA226Full(Transport& transport, float r_shunt, float max_current)
    : INA226Minimal(transport, r_shunt, max_current) {}

void INA226Full::configure(uint8_t avg, uint8_t vbus_ct, uint8_t vsh_ct, uint8_t mode) {
    uint16_t config = ((uint16_t)(avg & 0x07) << 9)
                    | ((uint16_t)(vbus_ct & 0x07) << 6)
                    | ((uint16_t)(vsh_ct & 0x07) << 3)
                    | (mode & 0x07);
    _mode = mode & 0x07;
    _write_reg(REG_CONFIG, config);
}

bool INA226Full::conversion_ready() {
    return (_read_reg(REG_MASK) & 0x0008) != 0;
}

bool INA226Full::overflow() {
    return (_read_reg(REG_MASK) & 0x0004) != 0;
}

void INA226Full::set_alert(uint16_t function, float limit, bool polarity, bool latch) {
    uint16_t raw = 0;
    if (function == SOL || function == SUL)
        raw = (uint16_t)(limit / 2.5e-6f);
    else if (function == BOL || function == BUL)
        raw = (uint16_t)(limit / 1.25e-3f);
    else if (function == POL)
        raw = (uint16_t)(limit / (25.0f * _current_lsb));
    uint16_t mask = function | (polarity ? 0x0002u : 0u) | (latch ? 0x0001u : 0u);
    _write_reg(REG_MASK, mask);
    _write_reg(REG_ALERT, raw);
}

uint16_t INA226Full::alert_flags() {
    return _read_reg(REG_MASK);
}

void INA226Full::reset() {
    _write_reg(REG_CONFIG, 0x8000);
    _write_reg(REG_CAL, _cal);
}

void INA226Full::shutdown() {
    uint16_t config = _read_reg(REG_CONFIG);
    _mode = config & 0x07;
    _write_reg(REG_CONFIG, config & 0xFFF8u);
}

void INA226Full::wake() {
    uint16_t config = _read_reg(REG_CONFIG);
    _write_reg(REG_CONFIG, (config & 0xFFF8u) | _mode);
}

uint16_t INA226Full::manufacturer_id() {
    return _read_reg(REG_MFR_ID);
}

uint16_t INA226Full::die_id() {
    return _read_reg(REG_DIE_ID);
}
