#include "ADXL345.h"
#include <stdlib.h>
#include <cmath>

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

// ADXL345 BW_RATE codes (Rate bits 3:0) and their actual output data rates.
namespace {
struct RateCode { uint8_t code; float rate_hz; };
constexpr RateCode RATE_CODES[] = {
    {0x0F, 3200.0f},
    {0x0E, 1600.0f},
    {0x0D, 800.0f},
    {0x0C, 400.0f},
    {0x0B, 200.0f},
    {0x0A, 100.0f},
    {0x09, 50.0f},
    {0x08, 25.0f},
    {0x07, 12.5f},
    {0x06, 6.25f},
};
}  // namespace

ADXL345Minimal::ADXL345Minimal(Connection& connection, bool spi)
    : _connection(connection), _spi(spi) {
    _write_reg(REG_DATA_FORMAT, DATA_FORMAT_DEFAULT);
    _write_reg(REG_BW_RATE, BW_RATE_DEFAULT);
    _write_reg(REG_POWER_CTL, POWER_CTL_DEFAULT);

    uint8_t devid = 0;
    _read_reg(REG_DEVID, &devid, 1);
    if (devid != DEVID_VALUE) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        // Use abort() rather than throwing (exceptions are disabled per
        // platform convention in this repo).
        (void)devid;
        abort();
    }

    // Per datasheet: allow at least one ODR period before reading data.
    delay(11);
}

uint8_t ADXL345Minimal::_cmd_byte(uint8_t reg, bool read, bool multi) const {
    uint8_t addr = reg & 0x3F;
    if (multi)  addr |= 0x40;
    if (read)   addr |= 0x80;
    return addr;
}

void ADXL345Minimal::_write_reg(uint8_t reg, uint8_t value) {
    if (_spi) {
        uint8_t buf[2] = { _cmd_byte(reg, false, false), value };
        _connection.write(buf, 2);
    } else {
        uint8_t buf[2] = { reg, value };
        _connection.write(buf, 2);
    }
}

void ADXL345Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    if (_spi) {
        uint8_t cmd = _cmd_byte(reg, true, len > 1);
        _connection.write_read(&cmd, 1, buf, len);
    } else {
        uint8_t addr = reg;
        _connection.write_read(&addr, 1, buf, len);
    }
}

void ADXL345Minimal::_delay_ms(uint32_t ms) {
    delay(ms);
}

int16_t ADXL345Minimal::_decode_signed(const uint8_t* p) {
    int16_t v = (int16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
    return v;
}

void ADXL345Minimal::read(float& x, float& y, float& z) {
    uint8_t raw[6];
    _read_reg(REG_DATAX0, raw, 6);
    int16_t rx = _decode_signed(&raw[0]);
    int16_t ry = _decode_signed(&raw[2]);
    int16_t rz = _decode_signed(&raw[4]);
    x = rx * FULL_RES_SCALE_G_PER_LSB;
    y = ry * FULL_RES_SCALE_G_PER_LSB;
    z = rz * FULL_RES_SCALE_G_PER_LSB;
}

// ADXL345Full

ADXL345Full::ADXL345Full(Connection& connection, bool spi)
    : ADXL345Minimal(connection, spi) {}

void ADXL345Full::set_range(uint8_t range_g) {
    uint8_t code = 0;
    switch (range_g) {
        case 2:  code = 0; break;
        case 4:  code = 1; break;
        case 8:  code = 2; break;
        case 16: code = 3; break;
        default: return;
    }
    _range_bits = code;
    uint8_t df = 0;
    _read_reg(REG_DATA_FORMAT, &df, 1);
    df = (df & ~0x03) | (code & 0x03);
    if (_full_res) df |= 0x08;
    _write_reg(REG_DATA_FORMAT, df);
}

void ADXL345Full::set_data_rate(float rate_hz) {
    uint8_t best_code = RATE_CODES[0].code;
    float   best_rate = RATE_CODES[0].rate_hz;
    float   best_diff = (float)fabsf(best_rate - rate_hz);
    for (size_t i = 1; i < sizeof(RATE_CODES) / sizeof(RATE_CODES[0]); i++) {
        float d = (float)fabsf(RATE_CODES[i].rate_hz - rate_hz);
        if (d < best_diff) {
            best_code = RATE_CODES[i].code;
            best_rate = RATE_CODES[i].rate_hz;
            best_diff = d;
        }
    }
    uint8_t bw = 0;
    _read_reg(REG_BW_RATE, &bw, 1);
    bw = (bw & ~0x0F) | (best_code & 0x0F);
    _write_reg(REG_BW_RATE, bw);
}

void ADXL345Full::set_low_power(bool enabled) {
    uint8_t bw = 0;
    _read_reg(REG_BW_RATE, &bw, 1);
    if (enabled) bw |= 0x10;
    else         bw &= ~0x10;
    _write_reg(REG_BW_RATE, bw);
}

uint8_t ADXL345Full::_encode_offset(float offset_g) {
    int32_t raw = (int32_t)lroundf(offset_g / 0.0156f);
    if (raw >  127) raw =  127;
    if (raw < -128) raw = -128;
    return (uint8_t)(int8_t)raw;
}

void ADXL345Full::set_offset(float x, float y, float z) {
    _write_reg(REG_OFSX, _encode_offset(x));
    _write_reg(REG_OFSY, _encode_offset(y));
    _write_reg(REG_OFSZ, _encode_offset(z));
}

void ADXL345Full::calibrate_offset(float target_x, float target_y, float target_z,
                                   uint16_t samples) {
    double sx = 0.0, sy = 0.0, sz = 0.0;
    for (uint16_t i = 0; i < samples; i++) {
        float x, y, z;
        read(x, y, z);
        sx += x; sy += y; sz += z;
        delay(11);
    }
    sx /= samples; sy /= samples; sz /= samples;
    set_offset((float)(target_x - sx), (float)(target_y - sy), (float)(target_z - sz));
}

void ADXL345Full::set_tap_detection(float threshold_g, float duration_ms,
                                    uint8_t axes, bool suppress) {
    _write_reg(REG_THRESH_TAP, (uint8_t)lroundf(threshold_g / 0.0625f));
    _write_reg(REG_DUR, (uint8_t)lroundf(duration_ms / 0.625f));
    uint8_t tap_axes = (axes & 0x07) | (suppress ? 0x08 : 0x00);
    _write_reg(REG_TAP_AXES, tap_axes);
    _enable_interrupt(INT_SINGLE_TAP);
}

void ADXL345Full::set_double_tap(float latency_ms, float window_ms) {
    _write_reg(REG_LATENT, (uint8_t)lroundf(latency_ms / 1.25f));
    _write_reg(REG_WINDOW, (uint8_t)lroundf(window_ms / 1.25f));
    _enable_interrupt(INT_DOUBLE_TAP);
}

void ADXL345Full::set_activity(float threshold_g, uint8_t axes, bool ac_coupled) {
    _write_reg(REG_THRESH_ACT, (uint8_t)lroundf(threshold_g / 0.0625f));
    uint8_t aic = 0;
    _read_reg(REG_ACT_INACT_CTL, &aic, 1);
    aic &= ~0xF0;
    if (ac_coupled) aic |= 0x80;
    aic |= axes & 0x70;
    _write_reg(REG_ACT_INACT_CTL, aic);
    _enable_interrupt(INT_ACTIVITY);
}

void ADXL345Full::set_inactivity(float threshold_g, float time_sec,
                                 uint8_t axes, bool ac_coupled) {
    _write_reg(REG_THRESH_INACT, (uint8_t)lroundf(threshold_g / 0.0625f));
    _write_reg(REG_TIME_INACT, (uint8_t)lroundf(time_sec));
    uint8_t aic = 0;
    _read_reg(REG_ACT_INACT_CTL, &aic, 1);
    aic &= ~0x0F;
    if (ac_coupled) aic |= 0x08;
    aic |= axes & 0x07;
    _write_reg(REG_ACT_INACT_CTL, aic);
    _enable_interrupt(INT_INACTIVITY);
}

void ADXL345Full::set_free_fall(float threshold_g, float time_ms) {
    _write_reg(REG_THRESH_FF, (uint8_t)lroundf(threshold_g / 0.0625f));
    _write_reg(REG_TIME_FF, (uint8_t)lroundf(time_ms / 5.0f));
    _enable_interrupt(INT_FREE_FALL);
}

void ADXL345Full::set_interrupt(uint8_t source, bool enabled, uint8_t pin) {
    uint8_t ie = 0, im = 0;
    _read_reg(REG_INT_ENABLE, &ie, 1);
    _read_reg(REG_INT_MAP, &im, 1);
    if (enabled) {
        ie |= source;
        if (pin == 2) im |= source;
        else          im &= ~source;
    } else {
        ie &= ~source;
    }
    _write_reg(REG_INT_ENABLE, ie);
    _write_reg(REG_INT_MAP, im);
}

void ADXL345Full::_enable_interrupt(uint8_t source) {
    set_interrupt(source, true, 1);
}

uint8_t ADXL345Full::read_interrupt_source() {
    uint8_t src = 0;
    _read_reg(REG_INT_SOURCE, &src, 1);
    return src;
}

void ADXL345Full::set_fifo_mode(uint8_t mode, uint8_t samples) {
    uint8_t fifo_ctl = (mode & 0xC0) | (samples & 0x1F);
    _write_reg(REG_FIFO_CTL, fifo_ctl);
}

uint8_t ADXL345Full::fifo_count() {
    uint8_t status = 0;
    _read_reg(REG_FIFO_STATUS, &status, 1);
    return status & 0x3F;
}

uint8_t ADXL345Full::read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples) {
    uint8_t n = fifo_count();
    if (n > max_samples) n = max_samples;
    for (uint8_t i = 0; i < n; i++) {
        uint8_t raw[6];
        _read_reg(REG_DATAX0, raw, 6);
        int16_t rx = _decode_signed(&raw[0]);
        int16_t ry = _decode_signed(&raw[2]);
        int16_t rz = _decode_signed(&raw[4]);
        x_buf[i] = rx * FULL_RES_SCALE_G_PER_LSB;
        y_buf[i] = ry * FULL_RES_SCALE_G_PER_LSB;
        z_buf[i] = rz * FULL_RES_SCALE_G_PER_LSB;
    }
    return n;
}

void ADXL345Full::set_sleep(bool enabled, uint8_t wakeup_hz) {
    uint8_t pwr = 0;
    _read_reg(REG_POWER_CTL, &pwr, 1);
    if (enabled) {
        uint8_t wakeup_code = WAKEUP_8_HZ;
        switch (wakeup_hz) {
            case 8: wakeup_code = WAKEUP_8_HZ; break;
            case 4: wakeup_code = WAKEUP_4_HZ; break;
            case 2: wakeup_code = WAKEUP_2_HZ; break;
            case 1: wakeup_code = WAKEUP_1_HZ; break;
            default: return;
        }
        pwr = (pwr & ~0x06) | wakeup_code | 0x08;
        pwr |= 0x04;
    } else {
        pwr &= ~0x04;
    }
    _write_reg(REG_POWER_CTL, pwr);
}

void ADXL345Full::set_link_mode(bool enabled) {
    uint8_t pwr = 0;
    _read_reg(REG_POWER_CTL, &pwr, 1);
    if (enabled) pwr |= 0x40;
    else         pwr &= ~0x40;
    _write_reg(REG_POWER_CTL, pwr);
}

void ADXL345Full::set_auto_sleep(bool enabled) {
    uint8_t pwr = 0;
    _read_reg(REG_POWER_CTL, &pwr, 1);
    if (enabled) pwr |= 0x20;
    else         pwr &= ~0x20;
    _write_reg(REG_POWER_CTL, pwr);
}

void ADXL345Full::self_test(bool enabled) {
    uint8_t df = 0;
    _read_reg(REG_DATA_FORMAT, &df, 1);
    if (enabled) df |= 0x80;
    else         df &= ~0x80;
    _write_reg(REG_DATA_FORMAT, df);
}