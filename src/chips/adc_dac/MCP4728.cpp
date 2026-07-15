#include "MCP4728.h"

MCP4728Minimal::MCP4728Minimal(Transport& transport)
    : _transport(transport) {}

void MCP4728Minimal::set_voltage(uint8_t channel, float fraction) {
    if (fraction < 0.0f) fraction = 0.0f;
    if (fraction > 1.0f) fraction = 1.0f;
    uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
    set_raw(channel, code);
}

void MCP4728Minimal::set_raw(uint8_t channel, uint16_t code) {
    if (channel > 3) channel = 3;
    if (code > 4095) code = 4095;
    _multi_write(channel, code, 0, 0, 0, 0);
}

void MCP4728Minimal::set_all(const float* fractions) {
    uint8_t buf[8];
    for (uint8_t i = 0; i < 4; i++) {
        float f = fractions[i];
        if (f < 0.0f) f = 0.0f;
        if (f > 1.0f) f = 1.0f;
        uint16_t code = (uint16_t)(f * 4095.0f + 0.5f);
        if (code > 4095) code = 4095;
        // Fast Write: byte1=[0 0 PD1 PD0 D11-D8] (PD=00), byte2=[D7-D0]
        // The C2 C1 bits are don't-care for channels B-D but kept zero here.
        buf[i * 2]     = (uint8_t)((code >> 8) & 0x0F);
        buf[i * 2 + 1] = (uint8_t)(code & 0xFF);
    }
    _transport.write(buf, 8);
}

void MCP4728Minimal::_multi_write(uint8_t channel, uint16_t code, uint8_t vref,
                                  uint8_t pd, uint8_t gain, uint8_t udac) {
    uint8_t buf[3] = {
        (uint8_t)(CMD_MULTI_WRITE_BASE | ((channel & 0x03) << 1) | (udac & 0x01)),
        (uint8_t)(((vref & 0x01) << 7) | ((pd & 0x03) << 5) |
                  ((gain & 0x01) << 4) | ((code >> 8) & 0x0F)),
        (uint8_t)(code & 0xFF)
    };
    _transport.write(buf, 3);
}

// MCP4728Full

MCP4728Full::MCP4728Full(Transport& transport)
    : MCP4728Minimal(transport) {}

void MCP4728Full::set_voltage_eeprom(uint8_t channel, float fraction, uint8_t vref, uint8_t gain) {
    if (fraction < 0.0f) fraction = 0.0f;
    if (fraction > 1.0f) fraction = 1.0f;
    uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
    _single_write(channel, code, vref, 0, gain, 0);
}

void MCP4728Full::set_raw_eeprom(uint8_t channel, uint16_t code, uint8_t vref, uint8_t gain) {
    if (channel > 3) channel = 3;
    if (code > 4095) code = 4095;
    _single_write(channel, code, vref, 0, gain, 0);
}

void MCP4728Full::set_all_eeprom(const float* fractions, const uint8_t* vrefs, const uint8_t* gains) {
    uint8_t buf[9];
    // Sequential Write starting at channel 0 (A): [0 1 0 1 0 0 0 UDAC] = 0x50
    buf[0] = CMD_SEQUENTIAL_BASE | 0x00;
    for (uint8_t i = 0; i < 4; i++) {
        float f = fractions[i];
        if (f < 0.0f) f = 0.0f;
        if (f > 1.0f) f = 1.0f;
        uint16_t code = (uint16_t)(f * 4095.0f + 0.5f);
        if (code > 4095) code = 4095;
        uint8_t v = vrefs[i] ? 1 : 0;
        uint8_t g = (gains[i] == 2) ? 1 : 0;
        // Per-channel byte layout (Multi-Write format): [V_REF PD1 PD0 Gx D11-D8]
        // PD bits are always 0 here (no power-down is set in this command).
        buf[1 + i * 2]     = (uint8_t)(((v & 0x01) << 7) | ((g & 0x01) << 4) | ((code >> 8) & 0x0F));
        buf[1 + i * 2 + 1] = (uint8_t)(code & 0xFF);
    }
    _transport.write(buf, 9);
}

void MCP4728Full::set_vref(uint8_t vref_a, uint8_t vref_b, uint8_t vref_c, uint8_t vref_d) {
    uint8_t byte1 = (uint8_t)(CMD_WRITE_VREF |
        ((vref_a ? 1 : 0) << 3) | ((vref_b ? 1 : 0) << 2) |
        ((vref_c ? 1 : 0) << 1) |  (vref_d ? 1 : 0));
    _transport.write(&byte1, 1);
}

void MCP4728Full::set_gain(uint8_t gain_a, uint8_t gain_b, uint8_t gain_c, uint8_t gain_d) {
    uint8_t byte1 = (uint8_t)(CMD_WRITE_GAIN |
        ((gain_a == 2 ? 1 : 0) << 3) | ((gain_b == 2 ? 1 : 0) << 2) |
        ((gain_c == 2 ? 1 : 0) << 1) |  (gain_d == 2 ? 1 : 0));
    _transport.write(&byte1, 1);
}

void MCP4728Full::set_power_down(uint8_t pd_a, uint8_t pd_b, uint8_t pd_c, uint8_t pd_d) {
    uint8_t byte1 = (uint8_t)(CMD_WRITE_POWERDOWN |
        (((pd_a >> 1) & 0x01) << 4) | ((pd_a & 0x01) << 3) |
        (((pd_b >> 1) & 0x01) << 2) | ((pd_b & 0x01) << 1));
    uint8_t byte2 = (uint8_t)(
        (((pd_c >> 1) & 0x01) << 6) | ((pd_c & 0x01) << 5) |
        (((pd_d >> 1) & 0x01) << 4) | ((pd_d & 0x01) << 3));
    uint8_t buf[2] = { byte1, byte2 };
    _transport.write(buf, 2);
}

MCP4728Full::ReadResult MCP4728Full::read() {
    uint8_t buf[24] = {0};
    _transport.read(buf, 24);
    ReadResult result = {};
    result.eeprom_ready = (buf[0] & 0x80) != 0;
    for (uint8_t i = 0; i < 4; i++) {
        uint8_t b = i * 3;
        result.channel[i].vref        = (buf[b + 1] >> 7) & 0x01;
        result.channel[i].power_down  = (buf[b + 1] >> 5) & 0x03;
        result.channel[i].gain        = ((buf[b + 1] >> 4) & 0x01) ? 2 : 1;
        result.channel[i].code        = (uint16_t)(((buf[b + 1] & 0x0F) << 8) | buf[b + 2]);
    }
    for (uint8_t i = 0; i < 4; i++) {
        uint8_t b = 12 + i * 3;
        result.channel[i].eeprom_vref       = (buf[b + 1] >> 7) & 0x01;
        result.channel[i].eeprom_power_down = (buf[b + 1] >> 5) & 0x03;
        result.channel[i].eeprom_gain       = ((buf[b + 1] >> 4) & 0x01) ? 2 : 1;
        result.channel[i].eeprom_code       = (uint16_t)(((buf[b + 1] & 0x0F) << 8) | buf[b + 2]);
    }
    return result;
}

bool MCP4728Full::is_eeprom_ready() {
    uint8_t buf[1] = {0};
    _transport.read(buf, 1);
    return (buf[0] & 0x80) != 0;
}

void MCP4728Full::software_update() {
    uint8_t buf[2] = { ADDR_GENERAL_CALL, GC_SOFTWARE_UPD };
    _transport.write(buf, 2);
}

void MCP4728Full::wake_up() {
    uint8_t buf[2] = { ADDR_GENERAL_CALL, GC_WAKE };
    _transport.write(buf, 2);
}

void MCP4728Full::reset() {
    uint8_t buf[2] = { ADDR_GENERAL_CALL, GC_RESET };
    _transport.write(buf, 2);
}

void MCP4728Full::_single_write(uint8_t channel, uint16_t code, uint8_t vref,
                                uint8_t pd, uint8_t gain, uint8_t udac) {
    if (channel > 3) channel = 3;
    if (code > 4095) code = 4095;
    uint8_t buf[3] = {
        (uint8_t)(CMD_SINGLE_WRITE | ((channel & 0x03) << 1) | (udac & 0x01)),
        (uint8_t)(((vref & 0x01) << 7) | ((pd & 0x03) << 5) |
                  ((gain & 0x01) << 4) | ((code >> 8) & 0x0F)),
        (uint8_t)(code & 0xFF)
    };
    _transport.write(buf, 3);
}
