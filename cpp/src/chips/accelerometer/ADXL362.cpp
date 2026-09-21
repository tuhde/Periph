#include "ADXL362.h"
#include <stdlib.h>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

// ODR codes (FILTER_CTL bits 2:0) and their actual rates in Hz.
namespace {
struct OdrCode { uint8_t code; float rate_hz; };
constexpr OdrCode ODR_CODES[] = {
    {0x00, 12.5f},
    {0x01, 25.0f},
    {0x02, 50.0f},
    {0x03, 100.0f},
    {0x04, 200.0f},
    {0x05, 400.0f},
    {0x06, 400.0f},
    {0x07, 400.0f},
};
}  // namespace

ADXL362Minimal::ADXL362Minimal(Connection& connection) : _connection(connection) {
    init();
}

void ADXL362Minimal::init() {
    // Power-up to standby turn-on time: ≤5 ms typical at 100 Hz.
    _delay_ms(5);

    uint8_t ids[3];
    _read_burst(REG_DEVID_AD, ids, 3);
    if (ids[0] != DEVID_AD_VALUE) {
        (void)ids;
        abort();
    }
    if (ids[1] != DEVID_MST_VALUE) {
        (void)ids;
        abort();
    }
    if (ids[2] != PARTID_VALUE) {
        (void)ids;
        abort();
    }

    _write_reg(REG_FILTER_CTL, FILTER_CTL_DEFAULT);
    _write_reg(REG_POWER_CTL, POWER_CTL_MEASURE);

    // Measurement-mode-instruction-to-valid-data: 4/ODR (40 ms at 100 Hz).
    _delay_ms(40);
}

void ADXL362Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[3] = { CMD_WRITE_REG, reg & 0x3F, value };
    _connection.write(buf, 3);
}

uint8_t ADXL362Minimal::_read_reg(uint8_t reg) {
    uint8_t cmd[2] = { CMD_READ_REG, reg & 0x3F };
    uint8_t val = 0;
    _connection.write_read(cmd, 2, &val, 1);
    return val;
}

void ADXL362Minimal::_read_burst(uint8_t reg, uint8_t* buf, uint8_t len) {
    uint8_t cmd[2] = { CMD_READ_REG, reg & 0x3F };
    _connection.write_read(cmd, 2, buf, len);
}

void ADXL362Minimal::_read_fifo(uint8_t* buf, uint8_t len) {
    uint8_t cmd[1] = { CMD_READ_FIFO };
    _connection.write_read(cmd, 1, buf, len);
}

uint16_t ADXL362Minimal::_read_fifo_entries() {
    uint8_t lo = _read_reg(REG_FIFO_ENTRIES_L);
    uint8_t hi = _read_reg(REG_FIFO_ENTRIES_H);
    return (uint16_t)(lo | ((hi & 0x03) << 8));
}

float ADXL362Minimal::_sensitivity() const {
    // _range_bits holds the FILTER_CTL RANGE field value (bits 7:6).
    if (_range_bits == 0x40) return SENSITIVITY_G_PER_LSB[1];
    if (_range_bits == 0x80 || _range_bits == 0xC0) return SENSITIVITY_G_PER_LSB[2];
    return SENSITIVITY_G_PER_LSB[0];
}

int16_t ADXL362Minimal::_sign_extend_12(uint16_t v) {
    v &= 0x0FFF;
    return (v & 0x0800) ? (int16_t)(v | 0xF000) : (int16_t)v;
}

void ADXL362Minimal::_delay_ms(unsigned long ms) {
    delay(ms);
}

void ADXL362Minimal::read(float& x, float& y, float& z) {
    uint8_t raw[6];
    _read_burst(REG_XDATA_L, raw, 6);
    int16_t rx = _sign_extend_12(((uint16_t)raw[1] & 0x0F) << 8 | raw[0]);
    int16_t ry = _sign_extend_12(((uint16_t)raw[3] & 0x0F) << 8 | raw[2]);
    int16_t rz = _sign_extend_12(((uint16_t)raw[5] & 0x0F) << 8 | raw[4]);
    float sens = _sensitivity();
    x = rx * sens;
    y = ry * sens;
    z = rz * sens;
}

// ADXL362Full

ADXL362Full::ADXL362Full(Connection& connection) : ADXL362Minimal(connection) {}

void ADXL362Full::device_id(uint8_t& devid_ad, uint8_t& devid_mst, uint8_t& partid, uint8_t& revid) {
    uint8_t ids[4];
    _read_burst(REG_DEVID_AD, ids, 4);
    devid_ad  = ids[0];
    devid_mst = ids[1];
    partid    = ids[2];
    revid     = ids[3];
}

void ADXL362Full::soft_reset() {
    _write_reg(REG_SOFT_RESET, SOFT_RESET_KEY);
    _delay_ms(1);
    _range_bits = 0x00;
    _odr_hz     = 100.0f;
}

void ADXL362Full::set_range(uint8_t range_g) {
    uint8_t code = 0;
    switch (range_g) {
        case 2:  code = 0x00; break;
        case 4:  code = 0x40; break;
        case 8:  code = 0x80; break;
        default: return;
    }
    uint8_t f = _read_reg(REG_FILTER_CTL);
    f = (f & 0x3F) | (code & 0xC0);
    _write_reg(REG_FILTER_CTL, f);
    _range_bits = code;
    if (_odr_hz > 0) {
        _delay_ms((unsigned long)(1000.0f / _odr_hz + 1));
    }
}

void ADXL362Full::set_odr(float odr_hz) {
    uint8_t best_code = ODR_CODES[0].code;
    float   best_rate = ODR_CODES[0].rate_hz;
    float   best_diff = fabsf(best_rate - odr_hz);
    for (size_t i = 1; i < sizeof(ODR_CODES) / sizeof(ODR_CODES[0]); i++) {
        float d = fabsf(ODR_CODES[i].rate_hz - odr_hz);
        if (d < best_diff) {
            best_code = ODR_CODES[i].code;
            best_rate = ODR_CODES[i].rate_hz;
            best_diff = d;
        }
    }
    uint8_t f = _read_reg(REG_FILTER_CTL);
    f = (f & 0xF8) | (best_code & 0x07);
    _write_reg(REG_FILTER_CTL, f);
    _odr_hz = best_rate;
}

void ADXL362Full::set_half_bandwidth(bool enabled) {
    uint8_t f = _read_reg(REG_FILTER_CTL);
    if (enabled) f |= 0x10; else f &= ~0x10;
    _write_reg(REG_FILTER_CTL, f);
}

void ADXL362Full::set_noise_mode(uint8_t mode) {
    uint8_t p = _read_reg(REG_POWER_CTL);
    p = (p & 0xCF) | ((mode << 4) & 0x30);
    _write_reg(REG_POWER_CTL, p);
}

void ADXL362Full::set_wakeup_mode(bool enabled) {
    uint8_t p = _read_reg(REG_POWER_CTL);
    if (enabled) p |= 0x08; else p &= ~0x08;
    _write_reg(REG_POWER_CTL, p);
}

void ADXL362Full::set_autosleep(bool enabled) {
    uint8_t p = _read_reg(REG_POWER_CTL);
    if (enabled) p |= 0x04; else p &= ~0x04;
    _write_reg(REG_POWER_CTL, p);
}

void ADXL362Full::set_external_clock(bool enabled) {
    uint8_t p = _read_reg(REG_POWER_CTL);
    if (enabled) p |= 0x40; else p &= ~0x40;
    _write_reg(REG_POWER_CTL, p);
}

void ADXL362Full::set_external_sample_trigger(bool enabled) {
    uint8_t f = _read_reg(REG_FILTER_CTL);
    if (enabled) f |= 0x08; else f &= ~0x08;
    _write_reg(REG_FILTER_CTL, f);
}

void ADXL362Full::read_8bit(float& x, float& y, float& z) {
    uint8_t raw[3];
    _read_burst(REG_XDATA, raw, 3);
    auto s8 = [](uint8_t v) -> int16_t {
        return (v & 0x80) ? (int16_t)(v - 256) : (int16_t)v;
    };
    float sens = _sensitivity() * 16.0f;
    x = s8(raw[0]) * sens;
    y = s8(raw[1]) * sens;
    z = s8(raw[2]) * sens;
}

float ADXL362Full::temperature() {
    uint8_t raw[2];
    _read_burst(REG_TEMP_L, raw, 2);
    int16_t raw12 = _sign_extend_12(((uint16_t)raw[1] & 0x0F) << 8 | raw[0]);
    return TEMP_BIAS_C + (raw12 - TEMP_BIAS_LSB) * TEMP_SCALE_C;
}

uint8_t ADXL362Full::status() {
    return _read_reg(REG_STATUS);
}

bool ADXL362Full::awake() {
    return (status() & STATUS_AWAKE) != 0;
}

bool ADXL362Full::data_ready() {
    return (status() & STATUS_DATA_READY) != 0;
}

uint16_t ADXL362Full::fifo_entries() {
    return _read_fifo_entries();
}

void ADXL362Full::configure_fifo(uint8_t mode, bool store_temp, uint16_t watermark) {
    uint8_t fc = (mode & 0x03) | ((uint8_t)(watermark >> 8) << 3) | (store_temp ? 0x04 : 0x00);
    _write_reg(REG_FIFO_CONTROL, fc);
    _write_reg(REG_FIFO_SAMPLES, (uint8_t)(watermark & 0xFF));
}

uint16_t ADXL362Full::read_fifo(uint8_t* axis_out, float* value_out, uint16_t max_in) {
    uint16_t n = _read_fifo_entries();
    if (n > max_in) n = max_in;
    if (n == 0) return 0;
    uint8_t raw[1024];
    uint16_t bytes = n * 2;
    if (bytes > sizeof(raw)) bytes = sizeof(raw);
    _read_fifo(raw, (uint8_t)bytes);
    float sens = _sensitivity();
    for (uint16_t i = 0; i < n; i++) {
        uint8_t lo = raw[2 * i];
        uint8_t hi = raw[2 * i + 1];
        uint16_t raw16 = ((uint16_t)hi << 8) | lo;
        uint8_t axis = (raw16 >> 14) & 0x03;
        int16_t raw12 = _sign_extend_12(raw16 & 0x0FFF);
        axis_out[i] = axis;
        if (axis == AXIS_TEMP) {
            value_out[i] = TEMP_BIAS_C + (raw12 - TEMP_BIAS_LSB) * TEMP_SCALE_C;
        } else {
            value_out[i] = raw12 * sens;
        }
    }
    return n;
}

void ADXL362Full::set_activity_threshold(float threshold_g, bool referenced) {
    float sens = _sensitivity();
    int32_t raw = (int32_t)lroundf(threshold_g / sens);
    if (raw < 0) raw = 0;
    if (raw > 0x3FF) raw = 0x3FF;
    _write_reg(REG_THRESH_ACT_L, (uint8_t)(raw & 0xFF));
    _write_reg(REG_THRESH_ACT_H, (uint8_t)((raw >> 8) & 0x03));
    uint8_t aic = _read_reg(REG_ACT_INACT_CTL);
    if (referenced) aic |= 0x02; else aic &= ~0x02;
    _write_reg(REG_ACT_INACT_CTL, aic);
}

void ADXL362Full::set_activity_time(uint8_t samples) {
    _write_reg(REG_TIME_ACT, samples);
}

void ADXL362Full::set_inactivity_threshold(float threshold_g, bool referenced) {
    float sens = _sensitivity();
    int32_t raw = (int32_t)lroundf(threshold_g / sens);
    if (raw < 0) raw = 0;
    if (raw > 0x3FF) raw = 0x3FF;
    _write_reg(REG_THRESH_INACT_L, (uint8_t)(raw & 0xFF));
    _write_reg(REG_THRESH_INACT_H, (uint8_t)((raw >> 8) & 0x03));
    uint8_t aic = _read_reg(REG_ACT_INACT_CTL);
    if (referenced) aic |= 0x08; else aic &= ~0x08;
    _write_reg(REG_ACT_INACT_CTL, aic);
}

void ADXL362Full::set_inactivity_time(uint16_t samples) {
    _write_reg(REG_TIME_INACT_L, (uint8_t)(samples & 0xFF));
    _write_reg(REG_TIME_INACT_H, (uint8_t)((samples >> 8) & 0xFF));
}

void ADXL362Full::enable_activity_detection(bool enabled) {
    uint8_t aic = _read_reg(REG_ACT_INACT_CTL);
    if (enabled) aic |= 0x01; else aic &= ~0x01;
    _write_reg(REG_ACT_INACT_CTL, aic);
}

void ADXL362Full::enable_inactivity_detection(bool enabled) {
    uint8_t aic = _read_reg(REG_ACT_INACT_CTL);
    if (enabled) aic |= 0x04; else aic &= ~0x04;
    _write_reg(REG_ACT_INACT_CTL, aic);
}

void ADXL362Full::set_link_loop_mode(uint8_t mode) {
    uint8_t aic = _read_reg(REG_ACT_INACT_CTL);
    aic = (aic & 0xCF) | ((mode << 4) & 0x30);
    _write_reg(REG_ACT_INACT_CTL, aic);
}

uint8_t _adxl362_intmap_bit(uint8_t source) {
    switch (source) {
        case ADXL362Full::SOURCE_DATA_READY:    return 0x01;
        case ADXL362Full::SOURCE_FIFO_READY:    return 0x02;
        case ADXL362Full::SOURCE_FIFO_WATERMARK: return 0x04;
        case ADXL362Full::SOURCE_FIFO_OVERRUN:  return 0x08;
        case ADXL362Full::SOURCE_ACT:           return 0x10;
        case ADXL362Full::SOURCE_INACT:         return 0x20;
        case ADXL362Full::SOURCE_AWAKE:         return 0x40;
        default: return 0x00;
    }
}

void ADXL362Full::set_interrupt(uint8_t pin, uint8_t source, bool enabled) {
    uint8_t reg = (pin == 1) ? REG_INTMAP1 : REG_INTMAP2;
    uint8_t cur = _read_reg(reg);
    uint8_t bit = _adxl362_intmap_bit(source);
    if (enabled) cur |= bit; else cur &= ~bit;
    _write_reg(reg, cur);
}

void ADXL362Full::set_interrupt_polarity(uint8_t pin, bool active_low) {
    uint8_t reg = (pin == 1) ? REG_INTMAP1 : REG_INTMAP2;
    uint8_t cur = _read_reg(reg);
    if (active_low) cur |= 0x80; else cur &= ~0x80;
    _write_reg(reg, cur);
}

void ADXL362Full::self_test(bool enabled) {
    uint8_t st = _read_reg(REG_SELF_TEST);
    if (enabled) st |= 0x01; else st &= ~0x01;
    _write_reg(REG_SELF_TEST, st);
    if (enabled && _odr_hz > 0) {
        _delay_ms((unsigned long)(4000.0f / _odr_hz + 1));
    }
}