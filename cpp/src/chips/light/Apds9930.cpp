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
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
#define DELAY_MS(ms) HAL_Delay(ms)
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

APDS9930Minimal::APDS9930Minimal(RegisterConnection& connection)
    : _connection(connection) {
    DELAY_MS(6);
    uint8_t id = _read_reg(REG_ID);
    assert(id == 0x39);
    { uint8_t v = 0x00; _connection.write(cmd_write(REG_ENABLE), &v, 1); }
    { uint8_t v = ATIME_DEFAULT; _connection.write(cmd_write(REG_ATIME), &v, 1); }
    { uint8_t v = PTIME_DEFAULT; _connection.write(cmd_write(REG_PTIME), &v, 1); }
    { uint8_t v = PPULSE_DEFAULT; _connection.write(cmd_write(REG_PPULSE), &v, 1); }
    { uint8_t v = CONTROL_DEFAULT; _connection.write(cmd_write(REG_CONTROL), &v, 1); }
    { uint8_t v = ENABLE_DEFAULT; _connection.write(cmd_write(REG_ENABLE), &v, 1); }
    DELAY_MS(12);
}

uint8_t APDS9930Minimal::_read_reg(uint8_t reg) {
    uint8_t val = 0;
    _connection.read(cmd_read(reg), &val, 1);
    return val;
}

uint16_t APDS9930Minimal::_read_reg16(uint8_t reg) {
    uint8_t buf[2] = { 0, 0 };
    _connection.read(cmd_read(reg), buf, 2);
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

APDS9930Full::APDS9930Full(RegisterConnection& connection)
    : APDS9930Minimal(connection) {}

void APDS9930Full::configure_als(uint8_t atime, uint8_t again, bool agl) {
    { uint8_t v = atime; _connection.write(cmd_write(REG_ATIME), &v, 1); }
    uint8_t ctrl = _read_reg(REG_CONTROL);
    ctrl = (ctrl & 0xFC) | (again & 0x03);
    { uint8_t v = ctrl; _connection.write(cmd_write(REG_CONTROL), &v, 1); }
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (agl) cfg |= 0x04;
    else     cfg &= ~0x04;
    cfg &= ~0x06;
    { uint8_t v = cfg; _connection.write(cmd_write(REG_CONFIG), &v, 1); }
}

void APDS9930Full::configure_proximity(uint8_t ppulse, uint8_t pgain,
                                       uint8_t pdrive, bool pdl, uint8_t ptime) {
    { uint8_t v = ppulse; _connection.write(cmd_write(REG_PPULSE), &v, 1); }
    { uint8_t v = ptime; _connection.write(cmd_write(REG_PTIME), &v, 1); }
    uint8_t ctrl = _read_reg(REG_CONTROL);
    ctrl = (ctrl & 0x03)
         | ((pdrive & 0x03) << 6)
         | 0x20
         | ((pgain & 0x03) << 2);
    { uint8_t v = ctrl; _connection.write(cmd_write(REG_CONTROL), &v, 1); }
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (pdl) cfg |= 0x01;
    else     cfg &= ~0x01;
    cfg &= ~0x06;
    { uint8_t v = cfg; _connection.write(cmd_write(REG_CONFIG), &v, 1); }
}

void APDS9930Full::configure_wait(uint8_t wtime, bool wlong) {
    { uint8_t v = wtime; _connection.write(cmd_write(REG_WTIME), &v, 1); }
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (wlong) cfg |= 0x02;
    else       cfg &= ~0x02;
    cfg &= ~0x04;
    { uint8_t v = cfg; _connection.write(cmd_write(REG_CONFIG), &v, 1); }
    uint8_t en = _read_reg(REG_ENABLE);
    en |= 0x08;
    { uint8_t v = en; _connection.write(cmd_write(REG_ENABLE), &v, 1); }
}

void APDS9930Full::disable_wait() {
    uint8_t en = _read_reg(REG_ENABLE);
    en &= ~0x08;
    { uint8_t v = en; _connection.write(cmd_write(REG_ENABLE), &v, 1); }
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
    { uint8_t v = low & 0xFF; _connection.write(cmd_write(REG_AILTL), &v, 1); }
    { uint8_t v = (low >> 8) & 0xFF; _connection.write(cmd_write(REG_AILTH), &v, 1); }
    { uint8_t v = high & 0xFF; _connection.write(cmd_write(REG_AIHTL), &v, 1); }
    { uint8_t v = (high >> 8) & 0xFF; _connection.write(cmd_write(REG_AIHTH), &v, 1); }
    uint8_t pers = _read_reg(REG_PERS);
    pers = (pers & 0xF0) | (persistence & 0x0F);
    { uint8_t v = pers; _connection.write(cmd_write(REG_PERS), &v, 1); }
    uint8_t en = _read_reg(REG_ENABLE);
    en |= 0x10;
    { uint8_t v = en; _connection.write(cmd_write(REG_ENABLE), &v, 1); }
}

void APDS9930Full::set_proximity_thresholds(uint16_t low, uint16_t high, uint8_t persistence) {
    if (low > high) high = low;
    { uint8_t v = low & 0xFF; _connection.write(cmd_write(REG_PILTL), &v, 1); }
    { uint8_t v = (low >> 8) & 0xFF; _connection.write(cmd_write(REG_PILTH), &v, 1); }
    { uint8_t v = high & 0xFF; _connection.write(cmd_write(REG_PIHTL), &v, 1); }
    { uint8_t v = (high >> 8) & 0xFF; _connection.write(cmd_write(REG_PIHTH), &v, 1); }
    uint8_t pers = _read_reg(REG_PERS);
    pers = (pers & 0x0F) | ((persistence & 0x0F) << 4);
    { uint8_t v = pers; _connection.write(cmd_write(REG_PERS), &v, 1); }
    uint8_t en = _read_reg(REG_ENABLE);
    en |= 0x20;
    { uint8_t v = en; _connection.write(cmd_write(REG_ENABLE), &v, 1); }
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
    { uint8_t v = enc; _connection.write(cmd_write(REG_POFFSET), &v, 1); }
}

void APDS9930Full::sleep_after_interrupt(bool enable) {
    uint8_t en = _read_reg(REG_ENABLE);
    if (enable) en |= 0x40;
    else        en &= ~0x40;
    { uint8_t v = en; _connection.write(cmd_write(REG_ENABLE), &v, 1); }
}