#include "PCF8591.h"

PCF8591Minimal::PCF8591Minimal(Transport& transport)
    : _transport(transport) {}

uint8_t PCF8591Minimal::read_channel(uint8_t channel) {
    uint8_t ch = (channel < NUM_CHANNELS) ? channel : 0;
    uint8_t ctrl = CONTROL_DEFAULT | (ch & 0x03);
    _transport.write(&ctrl, 1);
    uint8_t buf[2] = {0, 0};
    _transport.read(buf, 2);
    return buf[1];
}

void PCF8591Minimal::read_all(uint8_t* out) {
    uint8_t ctrl = CONTROL_DEFAULT | 0x04;  // AI=1
    _transport.write(&ctrl, 1);
    uint8_t buf[NUM_CHANNELS + 1] = {0, 0, 0, 0, 0};
    _transport.read(buf, NUM_CHANNELS + 1);
    out[0] = buf[1];
    out[1] = buf[2];
    out[2] = buf[3];
    out[3] = buf[4];
}

// PCF8591Full

PCF8591Full::PCF8591Full(Transport& transport)
    : PCF8591Minimal(transport),
      _control(CONTROL_DEFAULT),
      _input_mode(MODE_4_SINGLE_ENDED),
      _dac_enabled(false),
      _auto_increment(false),
      _last_channel(0) {}

void PCF8591Full::configure(uint8_t input_mode, bool auto_increment, bool dac_enabled) {
    uint8_t aip = input_mode & 0x03;
    uint8_t ai  = auto_increment ? 0x04 : 0x00;
    uint8_t aoe = dac_enabled     ? 0x40 : 0x00;
    _control = (aip << 4) | aoe | ai | (_last_channel & 0x03);
    _input_mode     = aip;
    _auto_increment = auto_increment;
    _dac_enabled    = dac_enabled;
    _transport.write(&_control, 1);
}

float PCF8591Full::read_channel_voltage(uint8_t channel, float vref, float vagnd) {
    uint8_t raw = read_channel(channel);
    return vagnd + (float)raw * (vref - vagnd) / 256.0f;
}

void PCF8591Full::read_all_voltage(float* out, float vref, float vagnd) {
    uint8_t raw[NUM_CHANNELS];
    read_all(raw);
    float vfs = vref - vagnd;
    for (uint8_t i = 0; i < NUM_CHANNELS; i++) {
        out[i] = vagnd + (float)raw[i] * vfs / 256.0f;
    }
}

int8_t PCF8591Full::read_differential(uint8_t channel) {
    uint8_t ch = channel & 0x03;
    _last_channel = ch;
    uint8_t ctrl = _control | (ch & 0x03);
    return _read_signed_byte(ctrl);
}

int8_t PCF8591Full::_read_signed_byte(uint8_t ctrl) {
    _transport.write(&ctrl, 1);
    uint8_t buf[2] = {0, 0};
    _transport.read(buf, 2);
    int8_t raw = (int8_t)buf[1];
    return raw;
}

void PCF8591Full::set_dac(uint8_t value) {
    uint8_t ctrl = (_control | 0x40) & ~0x04;  // AOE=1, AI=0
    _control     = ctrl;
    _dac_enabled = true;
    uint8_t buf[2] = { ctrl, value };
    _transport.write(buf, 2);
}

void PCF8591Full::set_dac_voltage(float voltage_fraction) {
    if (voltage_fraction < 0.0f) voltage_fraction = 0.0f;
    if (voltage_fraction > 1.0f) voltage_fraction = 1.0f;
    uint8_t value = (uint8_t)(voltage_fraction * 255.0f + 0.5f);
    set_dac(value);
}

void PCF8591Full::disable_dac() {
    uint8_t ctrl = _control & ~0x40;  // AOE=0
    _control     = ctrl;
    _dac_enabled = false;
    _transport.write(&ctrl, 1);
}
