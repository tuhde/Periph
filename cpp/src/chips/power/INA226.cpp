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
