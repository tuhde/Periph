#include "PCF8576.h"

PCF8576Minimal::PCF8576Minimal(Transport& transport)
    : _transport(transport), _backplanes(4) {
    _do_clear();
}

uint8_t PCF8576Minimal::_cmd_mode(bool enable, uint8_t bias, uint8_t mode) const {
    return CMD_MODE_SET | (enable ? DISPLAY_ON : DISPLAY_OFF) | bias | mode;
}

void PCF8576Minimal::_send_commands(const uint8_t* cmds, size_t n) {
    if (n == 0) return;
    uint8_t buf[8];
    for (size_t i = 0; i < n - 1; i++) {
        buf[i] = 0x80 | (cmds[i] & 0x7F);
    }
    buf[n - 1] = cmds[n - 1] & 0x7F;
    _transport.write(buf, n);
}

void PCF8576Minimal::_send_commands_with_data(const uint8_t* cmds, size_t n_cmds,
                                              const uint8_t* data, size_t n_data) {
    if (n_cmds == 0) return;
    uint8_t buf[64];
    size_t pos = 0;
    for (size_t i = 0; i < n_cmds - 1; i++) {
        buf[pos++] = 0x80 | (cmds[i] & 0x7F);
    }
    buf[pos++] = cmds[n_cmds - 1] & 0x7F;
    for (size_t i = 0; i < n_data; i++) {
        buf[pos++] = data[i];
    }
    _transport.write(buf, pos);
}

void PCF8576Minimal::_do_clear() {
    uint8_t cmd = _cmd_mode(true, BIAS_1_3, MODE_1_4);
    _send_commands(&cmd, 1);

    uint8_t load = CMD_LOAD_PTR | 0x00;
    uint8_t zeros[20] = {0};
    _send_commands_with_data(&load, 1, zeros, 20);
}

void PCF8576Minimal::clear() {
    _do_clear();
}

void PCF8576Minimal::write_raw(uint8_t address, const uint8_t* data, size_t len) {
    if (len == 0) return;
    uint8_t load = CMD_LOAD_PTR | (address & 0x3F);
    _send_commands_with_data(&load, 1, data, len);
}

void PCF8576Minimal::set_digit_7seg(uint8_t position, uint8_t segments) {
    uint8_t load = CMD_LOAD_PTR | ((position * 2) & 0x3F);
    uint8_t seg = segments;
    _send_commands_with_data(&load, 1, &seg, 1);
}

// PCF8576Full

PCF8576Full::PCF8576Full(Transport& transport)
    : PCF8576Minimal(transport), _enabled(true), _bias(BIAS_1_3) {}

uint8_t PCF8576Full::_mode_code(uint8_t backplanes) const {
    switch (backplanes) {
        case BACKPLANES_1: return MODE_STATIC;
        case BACKPLANES_2: return MODE_1_2;
        case BACKPLANES_3: return MODE_1_3;
        case BACKPLANES_4: return MODE_1_4;
        default:           return MODE_1_4;
    }
}

void PCF8576Full::_apply_mode() {
    uint8_t bias_bits = (_bias == BIAS_1_2) ? BIAS_1_2 : BIAS_1_3;
    uint8_t cmd = _cmd_mode(_enabled, bias_bits, _mode_code(_backplanes));
    _send_commands(&cmd, 1);
}

void PCF8576Full::enable() {
    _enabled = true;
    _apply_mode();
}

void PCF8576Full::disable() {
    _enabled = false;
    _apply_mode();
}

void PCF8576Full::set_mode(uint8_t backplanes, uint8_t bias) {
    _backplanes = backplanes;
    _bias = bias;
    _apply_mode();
}

void PCF8576Full::set_blink(uint8_t frequency, bool alternate_bank) {
    uint8_t ab = alternate_bank ? 0x04 : 0x00;
    uint8_t cmd = CMD_BLINK_SELECT | ab | (frequency & 0x03);
    _send_commands(&cmd, 1);
}

void PCF8576Full::set_bank(uint8_t input_bank, uint8_t output_bank) {
    uint8_t cmd = CMD_BANK_SELECT | ((input_bank & 1) << 1) | (output_bank & 1);
    _send_commands(&cmd, 1);
}

void PCF8576Full::device_select(uint8_t subaddress) {
    uint8_t cmd = CMD_DEVICE_SELECT | (subaddress & 0x07);
    _send_commands(&cmd, 1);
}
