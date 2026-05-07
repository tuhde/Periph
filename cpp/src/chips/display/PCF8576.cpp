#include "PCF8576.h"

const uint8_t PCF8576Minimal::SEG_7SEG[11] = {
    0xED, 0x60, 0xA7, 0xE3, 0x6A, 0xCB, 0xCF, 0xE0, 0xEF, 0xEB, 0x00
};

PCF8576Minimal::PCF8576Minimal(Transport& transport, uint8_t address)
    : _transport(transport), _address(address), _subaddress(0) {
    uint8_t dev_sel[1] = { CMD_DEV_SEL | 0x01 | (_subaddress << 1) };
    _write_cmd(dev_sel, 1);
    uint8_t mode_set[1] = { 0x88 };
    _write_cmd(mode_set, 1);
    uint8_t load_dp[1] = { CMD_LOAD_DP | 0x00 };
    _write_cmd(load_dp, 1);
    uint8_t zeros[40] = {0};
    _transport.write(zeros, 40);
}

void PCF8576Minimal::_write_cmd(const uint8_t* data, size_t len) {
    _transport.write(data, len);
}

void PCF8576Minimal::clear() {
    uint8_t cmd[2] = { CMD_LOAD_DP | 0x00, 0x00 };
    _transport.write(cmd, 2);
    uint8_t zeros[40] = {0};
    _transport.write(zeros, 40);
}

void PCF8576Minimal::write_raw(uint8_t address, const uint8_t* data, size_t len) {
    if (address >= 40 || (address + len) > 40) return;
    uint8_t cmd[1] = { CMD_LOAD_DP | address };
    _transport.write(cmd, 1);
    _transport.write(data, len);
}

void PCF8576Minimal::set_digit_7seg(uint8_t position, uint8_t segments) {
    if (position >= 20) return;
    uint8_t addr = position * 2;
    uint8_t cmd[1] = { CMD_LOAD_DP | addr };
    _transport.write(cmd, 1);
    _transport.write(&segments, 1);
}

// PCF8576Full

PCF8576Full::PCF8576Full(Transport& transport, uint8_t address)
    : PCF8576Minimal(transport, address) {}

uint8_t PCF8576Full::_build_mode_set(uint8_t backplanes, uint8_t bias, bool enable) {
    uint8_t e = enable ? 0x08 : 0x00;
    uint8_t b = bias ? 0x04 : 0x00;
    uint8_t m = (backplanes == 4) ? 0 :
               (backplanes == 1) ? 1 :
               (backplanes == 2) ? 2 : 3;
    return 0x80 | e | b | m;
}

void PCF8576Full::enable() {
    uint8_t mode[1] = { _build_mode_set(4, 0, true) };
    _write_cmd(mode, 1);
}

void PCF8576Full::disable() {
    uint8_t mode[1] = { _build_mode_set(4, 0, false) };
    _write_cmd(mode, 1);
}

void PCF8576Full::set_mode(uint8_t backplanes, uint8_t bias) {
    uint8_t mode[1] = { _build_mode_set(backplanes, bias, true) };
    _write_cmd(mode, 1);
}

void PCF8576Full::set_blink(uint8_t frequency, bool alternate_bank) {
    uint8_t ab = alternate_bank ? 0x04 : 0x00;
    uint8_t cmd[1] = { CMD_BLINK | ab | (frequency & 0x03) };
    _write_cmd(cmd, 1);
}

void PCF8576Full::set_bank(uint8_t input_bank, uint8_t output_bank) {
    uint8_t i = input_bank ? 0x02 : 0x00;
    uint8_t o = output_bank ? 0x01 : 0x00;
    uint8_t cmd[1] = { CMD_BANK | i | o };
    _write_cmd(cmd, 1);
}

void PCF8576Full::device_select(uint8_t subaddress) {
    if (subaddress > 7) return;
    _subaddress = subaddress;
    uint8_t cmd[1] = { CMD_DEV_SEL | 0x01 | (subaddress << 1) };
    _write_cmd(cmd, 1);
}