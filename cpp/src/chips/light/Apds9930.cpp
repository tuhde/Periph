#include "Apds9930.h"
#include <assert.h>

#ifdef __linux__
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

APDS9930Minimal::APDS9930Minimal(Connection& connection)
    : _connection(connection) {
    DELAY_MS(6);
    uint8_t id = _read_reg(REG_ID);
    assert(id == 0x39);
    _write_reg(REG_ENABLE, 0x00);
    _write_reg(REG_ATIME, ATIME_DEFAULT);
    _write_reg(REG_PTIME, PTIME_DEFAULT);
    _write_reg(REG_PPULSE, PPULSE_DEFAULT);
    _write_reg(REG_CONTROL, CONTROL_DEFAULT);
    _write_reg(REG_ENABLE, ENABLE_DEFAULT);
    DELAY_MS(12);
}

void APDS9930Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { cmd_write(reg), value };
    _connection.write(buf, 2);
}

uint8_t APDS9930Minimal::_read_reg(uint8_t reg) {
    uint8_t cmd = cmd_read(reg);
    uint8_t val = 0;
    _connection.write_read(&cmd, 1, &val, 1);
    return val;
}

uint16_t APDS9930Minimal::_read_reg16(uint8_t reg) {
    uint8_t cmd = cmd_read(reg);
    uint8_t buf[2] = { 0, 0 };
    _connection.write_read(&cmd, 1, buf, 2);
    return ((uint16_t)buf[1] << 8) | buf[0];
}

void APDS9930Minimal::_special(uint8_t function_code) {
    uint8_t cmd = cmd_special(function_code);
    _connection.write(&cmd, 1);
}

static float again_factor(uint8_t again_idx, bool agl) {
    if (!agl) {
        const float t[4] = { 1.0f, 8.0f, 16.0f, 120.0f };
        return t[again_idx & 0x03];
    }
    const float t[4] = { 1.0f / 6.0f, 8.0f / 6.0f, 16.0f / 6.0f, 20.0f };
    return t[again_idx & 0x03];
}

float APDS9930Minimal::lux() {
    uint16_t ch0 = _read_reg16(REG_CH0DATAL);
    uint16_t ch1 = _read_reg16(REG_CH1DATAL);
    uint8_t ctrl = _read_reg(REG_CONTROL);
    uint8_t cfg  = _read_reg(REG_CONFIG);
    uint8_t atime = _read_reg(REG_ATIME);
    float alsit_ms = 2.73f * (256.0f - atime);
    float again_x = again_factor(ctrl & 0x03, (cfg & 0x04) != 0);
    float iac1 = (float)ch0 - 1.862f * (float)ch1;
    float iac2 = 0.746f * (float)ch0 - 1.291f * (float)ch1;
    float iac = iac1;
    if (iac2 > iac) iac = iac2;
    if (iac < 0.0f) iac = 0.0f;
    float lpc = (0.49f * 52.0f) / (alsit_ms * again_x);
    return iac * lpc;
}

uint16_t APDS9930Minimal::proximity() {
    return _read_reg16(REG_PDATAL);
}

// APDS9930Full

APDS9930Full::APDS9930Full(Connection& connection)
    : APDS9930Minimal(connection) {}

void APDS9930Full::configure_als(uint8_t atime, uint8_t again, bool agl) {
    _write_reg(REG_ATIME, atime);
    uint8_t ctrl = _read_reg(REG_CONTROL);
    ctrl = (ctrl & 0xFC) | (again & 0x03);
    _write_reg(REG_CONTROL, ctrl);
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (agl) cfg |= 0x04;
    else     cfg &= ~0x04;
    cfg &= ~0x06;
    _write_reg(REG_CONFIG, cfg);
}

void APDS9930Full::configure_proximity(uint8_t ppulse, uint8_t pgain,
                                       uint8_t pdrive, bool pdl, uint8_t ptime) {
    _write_reg(REG_PPULSE, ppulse);
    _write_reg(REG_PTIME, ptime);
    uint8_t ctrl = _read_reg(REG_CONTROL);
    ctrl = (ctrl & 0x03)
         | ((pdrive & 0x03) << 6)
         | 0x20
         | ((pgain & 0x03) << 2);
    _write_reg(REG_CONTROL, ctrl);
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (pdl) cfg |= 0x01;
    else     cfg &= ~0x01;
    cfg &= ~0x06;
    _write_reg(REG_CONFIG, cfg);
}

void APDS9930Full::configure_wait(uint8_t wtime, bool wlong) {
    _write_reg(REG_WTIME, wtime);
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (wlong) cfg |= 0x02;
    else       cfg &= ~0x02;
    cfg &= ~0x04;
    _write_reg(REG_CONFIG, cfg);
    uint8_t en = _read_reg(REG_ENABLE);
    en |= 0x08;
    _write_reg(REG_ENABLE, en);
}

void APDS9930Full::disable_wait() {
    uint8_t en = _read_reg(REG_ENABLE);
    en &= ~0x08;
    _write_reg(REG_ENABLE, en);
}

uint16_t APDS9930Full::ch0() {
    return _read_reg16(REG_CH0DATAL);
}

uint16_t APDS9930Full::ch1() {
    return _read_reg16(REG_CH1DATAL);
}

void APDS9930Full::status(bool& avalid, bool& pvalid, bool& psat,
                          bool& aint, bool& pint) {
    uint8_t s = _read_reg(REG_STATUS);
    avalid = (s & 0x01) != 0;
    pvalid = (s & 0x02) != 0;
    psat   = (s & 0x40) != 0;
    aint   = (s & 0x10) != 0;
    pint   = (s & 0x20) != 0;
}

void APDS9930Full::set_als_thresholds(uint16_t low, uint16_t high, uint8_t persistence) {
    if (low > high) high = low;
    _write_reg(REG_AILTL, low & 0xFF);
    _write_reg(REG_AILTH, (low >> 8) & 0xFF);
    _write_reg(REG_AIHTL, high & 0xFF);
    _write_reg(REG_AIHTH, (high >> 8) & 0xFF);
    uint8_t pers = _read_reg(REG_PERS);
    pers = (pers & 0xF0) | (persistence & 0x0F);
    _write_reg(REG_PERS, pers);
    uint8_t en = _read_reg(REG_ENABLE);
    en |= 0x10;
    _write_reg(REG_ENABLE, en);
}

void APDS9930Full::set_proximity_thresholds(uint16_t low, uint16_t high, uint8_t persistence) {
    if (low > high) high = low;
    _write_reg(REG_PILTL, low & 0xFF);
    _write_reg(REG_PILTH, (low >> 8) & 0xFF);
    _write_reg(REG_PIHTL, high & 0xFF);
    _write_reg(REG_PIHTH, (high >> 8) & 0xFF);
    uint8_t pers = _read_reg(REG_PERS);
    pers = (pers & 0x0F) | ((persistence & 0x0F) << 4);
    _write_reg(REG_PERS, pers);
    uint8_t en = _read_reg(REG_ENABLE);
    en |= 0x20;
    _write_reg(REG_ENABLE, en);
}

void APDS9930Full::clear_interrupt(uint8_t channel) {
    if (channel == 1) {
        _special(CFN_CLEAR_ALS);
    } else if (channel == 2) {
        _special(CFN_CLEAR_PROXIMITY);
    } else {
        _special(CFN_CLEAR_BOTH);
    }
}

void APDS9930Full::set_proximity_offset(int8_t offset) {
    uint8_t enc;
    if (offset >= 0) {
        enc = 0x80 | ((uint8_t)offset & 0x7F);
    } else {
        enc = ((uint8_t)(-offset)) & 0x7F;
    }
    _write_reg(REG_POFFSET, enc);
}

void APDS9930Full::sleep_after_interrupt(bool enable) {
    uint8_t en = _read_reg(REG_ENABLE);
    if (enable) en |= 0x40;
    else        en &= ~0x40;
    _write_reg(REG_ENABLE, en);
}