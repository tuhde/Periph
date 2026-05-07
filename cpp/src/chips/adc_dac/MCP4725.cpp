#include "MCP4725.h"

MCP4725Minimal::MCP4725Minimal(Transport& transport)
    : _MCP47xxBase(transport) {}

void MCP4725Minimal::set_voltage(float fraction) {
    uint16_t code = (uint16_t)(fraction * 4095.0f);
    if (code > 4095) code = 4095;
    set_raw(code);
}

void MCP4725Minimal::set_raw(uint16_t code) {
    if (code > 4095) code = 4095;
    uint8_t buf[2] = {
        (uint8_t)((code >> 8) & 0x0F),
        (uint8_t)(code & 0xFF)
    };
    _transport.write(buf, 2);
}

MCP4725Full::MCP4725Full(Transport& transport)
    : MCP4725Minimal(transport) {}

void MCP4725Full::set_voltage_eeprom(float fraction) {
    uint16_t code = (uint16_t)(fraction * 4095.0f);
    if (code > 4095) code = 4095;
    set_raw_eeprom(code);
}

void MCP4725Full::set_raw_eeprom(uint16_t code) {
    if (code > 4095) code = 4095;
    uint8_t buf[3] = {
        (uint8_t)(WRITE_DAC_EEPROM | ((code >> 8) & 0x0F)),
        (uint8_t)(code & 0xFF),
        0x00
    };
    _transport.write(buf, 3);
}

MCP4725ReadResult MCP4725Full::read() {
    uint8_t raw[5] = {0};
    uint8_t cmd[1] = {0x00};
    _transport.write_read(cmd, 1, raw, 5);

    MCP4725ReadResult result;
    result.code = ((uint16_t)(raw[1] << 8) | raw[2]) >> 4;
    result.voltage_fraction = result.code / 4095.0f;
    result.power_down = (raw[0] >> 2) & 0x03;
    result.eeprom_code = ((uint16_t)(raw[3] & 0x0F) << 8) | raw[4];
    result.eeprom_power_down = (raw[3] >> 4) & 0x03;
    result.eeprom_ready = (raw[0] & 0x80) != 0;
    result.por = (raw[0] & 0x40) != 0;
    return result;
}

void MCP4725Full::set_power_down(uint8_t mode) {
    uint8_t cmd[1] = {0x00};
    uint8_t raw[1] = {0};
    _transport.write_read(cmd, 1, raw, 1);
    uint8_t pd_bits = (mode & 0x03) << 4;
    uint8_t buf[3] = { (uint8_t)(raw[0] & 0x0F) | pd_bits, 0x00, 0x00 };
    _transport.write(buf, 3);
}