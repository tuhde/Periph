#include "INA219.h"

INA219Minimal::INA219Minimal(Transport& transport, float r_shunt, float max_current)
    : _transport(transport) {
    _current_lsb = max_current / 32768.0f;
    _cal = (uint16_t)(0.04096f / (_current_lsb * r_shunt)) & 0xFFFE;
    _write_reg(REG_CAL, _cal);
}

void INA219Minimal::_write_reg(uint8_t reg, uint16_t value) {
    uint8_t buf[3] = { reg, (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _transport.write(buf, 3);
}

uint16_t INA219Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[2];
    _transport.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0] << 8) | buf[1];
}

int16_t INA219Minimal::_read_reg_signed(uint8_t reg) {
    return static_cast<int16_t>(_read_reg(reg));
}

float INA219Minimal::voltage() {
    return (_read_reg(REG_BUS) >> 3) * 4e-3f;
}

float INA219Minimal::shunt_voltage() {
    return _read_reg_signed(REG_SHUNT) * 10e-6f;
}

float INA219Minimal::current() {
    return _read_reg_signed(REG_CURRENT) * _current_lsb;
}

float INA219Minimal::power() {
    return _read_reg(REG_POWER) * 20.0f * _current_lsb;
}

INA219Full::INA219Full(Transport& transport, float r_shunt, float max_current)
    : INA219Minimal(transport, r_shunt, max_current) {}

void INA219Full::configure(uint8_t brng, uint8_t pga, uint8_t badc, uint8_t sadc, uint8_t mode) {
    uint16_t config = ((uint16_t)(brng & 1) << 13)
                    | ((uint16_t)(pga & 3) << 11)
                    | ((uint16_t)(badc & 0xF) << 7)
                    | ((uint16_t)(sadc & 0xF) << 3)
                    | (mode & 7);
    _mode = mode & 7;
    _write_reg(REG_CONFIG, config);
    _write_reg(REG_CAL, _cal);
}

bool INA219Full::conversion_ready() {
    return (_read_reg(REG_BUS) & 0x0002) != 0;
}

bool INA219Full::overflow() {
    return (_read_reg(REG_BUS) & 0x0001) != 0;
}

void INA219Full::reset() {
    _write_reg(REG_CONFIG, 0x8000);
    _write_reg(REG_CAL, _cal);
}

void INA219Full::shutdown() {
    uint16_t config = _read_reg(REG_CONFIG);
    _mode = config & 0x07;
    _write_reg(REG_CONFIG, config & 0xFFF8u);
}

void INA219Full::wake() {
    uint16_t config = _read_reg(REG_CONFIG);
    _write_reg(REG_CONFIG, (config & 0xFFF8u) | _mode);
}

void INA219Full::trigger() {
    uint16_t config = _read_reg(REG_CONFIG);
    _write_reg(REG_CONFIG, (config & 0xFFF8u) | _mode);
}
