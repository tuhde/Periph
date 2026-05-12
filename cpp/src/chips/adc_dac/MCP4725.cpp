#include "MCP4725.h"

MCP4725Minimal::MCP4725Minimal(Transport& transport)
    : _transport(transport) {}

void MCP4725Minimal::set_voltage(float fraction) {
    if (fraction < 0.0f) fraction = 0.0f;
    if (fraction > 1.0f) fraction = 1.0f;
    uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
    _fast_write(code, 0);
}

void MCP4725Minimal::set_raw(uint16_t code) {
    if (code > 4095) code = 4095;
    _fast_write(code, 0);
}

void MCP4725Minimal::_fast_write(uint16_t code, uint8_t pd_mode) {
    uint8_t buf[2] = {
        (uint8_t)((pd_mode & 0x03) << 4) | (uint8_t)((code >> 8) & 0x0F),
        (uint8_t)(code & 0xFF)
    };
    _transport.write(buf, 2);
}

// MCP4725Full

MCP4725Full::MCP4725Full(Transport& transport)
    : MCP4725Minimal(transport) {}

void MCP4725Full::set_voltage_eeprom(float fraction) {
    if (fraction < 0.0f) fraction = 0.0f;
    if (fraction > 1.0f) fraction = 1.0f;
    uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
    _write_dac_eeprom(code, 0);
}

void MCP4725Full::set_raw_eeprom(uint16_t code) {
    if (code > 4095) code = 4095;
    _write_dac_eeprom(code, 0);
}

MCP4725Full::ReadResult MCP4725Full::read() {
    uint8_t cmd = 0x00;
    uint8_t buf[5] = {0, 0, 0, 0, 0};
    _transport.write_read(&cmd, 1, buf, 5);
    ReadResult result = {};
    result.eeprom_ready = (buf[0] & 0x80) != 0;
    result.power_down = (buf[0] >> 2) & 0x03;
    result.code = ((uint16_t)(buf[1] & 0x0F) << 8) | buf[2];
    result.voltage_fraction = result.code / 4095.0f;
    result.eeprom_power_down = (buf[3] >> 6) & 0x03;
    result.eeprom_code = ((uint16_t)(buf[3] & 0x0F) << 8) | buf[4];
    return result;
}

void MCP4725Full::set_power_down(uint8_t mode) {
    if (mode > 3) mode = 3;
    uint16_t code = _read_dac_code();
    _fast_write(code, mode);
}

void MCP4725Full::wake_up() {
    uint8_t buf[2] = { ADDR_GENERAL_CALL, GC_WAKE };
    _transport.write(buf, 2);
}

void MCP4725Full::reset() {
    uint8_t buf[2] = { ADDR_GENERAL_CALL, GC_RESET };
    _transport.write(buf, 2);
}

bool MCP4725Full::is_eeprom_ready() {
    uint8_t cmd = 0x00;
    uint8_t buf[1] = {0};
    _transport.write_read(&cmd, 1, buf, 1);
    return (buf[0] & 0x80) != 0;
}

void MCP4725Full::_write_dac_eeprom(uint16_t code, uint8_t pd_mode) {
    uint8_t buf[3] = {
        (uint8_t)(CMD_WRITE_DAC_EEPROM | ((pd_mode & 0x03) << 1)),
        (uint8_t)((code >> 4) & 0xFF),
        (uint8_t)((code & 0x0F) << 4)
    };
    _transport.write(buf, 3);
}

uint16_t MCP4725Full::_read_dac_code() {
    uint8_t cmd = 0x00;
    uint8_t buf[2] = {0, 0};
    _transport.write_read(&cmd, 1, buf, 2);
    return ((uint16_t)(buf[0] & 0x0F) << 8) | buf[1];
}