#include "BMA150.h"
#include <stdlib.h>
#include <math.h>
#include <string.h>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

BMA150Minimal::BMA150Minimal(RegisterConnection& connection)
    : _connection(connection) {
    // Verify CHIP_ID (bits 2:0 of register 0x00 == 0b010).
    uint8_t chip_id = _read_reg(REG_CHIP_ID);
    if ((chip_id & CHIP_ID_MASK) != CHIP_ID_VALUE) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        (void)chip_id;
        abort();
    }

    // Set range/bandwidth preserving factory calibration bits 7:5 of
    // RANGE_BW (0x14). Default: range ±2 g, bandwidth 100 Hz.
    uint8_t rb = _read_reg(REG_RANGE_BW);
    rb = static_cast<uint8_t>((rb & 0xE0) | RANGE_2G_MASK | BW_100HZ);
    _write_reg(REG_RANGE_BW, rb);

    // Allow filtered data to replace stale registers (1/(2*100Hz) = 5 ms).
    delay(5);
}

void BMA150Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t BMA150Minimal::_read_reg(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void BMA150Minimal::_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.read(reg, buf, len);
}

void BMA150Minimal::_delay_ms(uint32_t ms) {
    delay(ms);
}

void BMA150Minimal::read(float& x, float& y, float& z) {
    uint8_t raw[6];
    _read_burst(REG_ACC_X_LSB, raw, 6);
    // Per axis: raw = (MSB << 2) | (LSB >> 6), 0..1023 unsigned,
    // then sign-extend at 512. Bit 0 of each LSB register is the
    // new_data flag — the shift above drops it.
    int16_t rx = (int16_t)toSigned(((uint32_t)raw[1] << 2) | (raw[0] >> 6), 10);
    int16_t ry = (int16_t)toSigned(((uint32_t)raw[3] << 2) | (raw[2] >> 6), 10);
    int16_t rz = (int16_t)toSigned(((uint32_t)raw[5] << 2) | (raw[4] >> 6), 10);
    float scale;
    switch (_range_g) {
        case 4:  scale = SCALE_4G; break;
        case 8:  scale = SCALE_8G; break;
        case 2:
        default: scale = SCALE_2G; break;
    }
    x = (float)rx / scale;
    y = (float)ry / scale;
    z = (float)rz / scale;
}

// BMA150Full

BMA150Full::BMA150Full(RegisterConnection& connection)
    : BMA150Minimal(connection) {}

int16_t BMA150Full::_decode_axis(const uint8_t* p) {
    return (int16_t)toSigned(((uint32_t)p[1] << 2) | (p[0] >> 6), 10);
}

void BMA150Full::_read_raw_counts(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _read_burst(REG_ACC_X_LSB, raw, 6);
    x = _decode_axis(&raw[0]);
    y = _decode_axis(&raw[2]);
    z = _decode_axis(&raw[4]);
}

void BMA150Full::read_raw(int16_t& x, int16_t& y, int16_t& z) {
    _read_raw_counts(x, y, z);
}

uint8_t BMA150Full::_nearest_bandwidth(uint16_t bandwidth_hz) {
    static const uint16_t hz_table[] = {25, 50, 100, 190, 375, 750, 1500};
    static const uint8_t  bw_table[] = {BW_25HZ, BW_50HZ, BW_100HZ, BW_190HZ,
                                        BW_375HZ, BW_750HZ, BW_1500HZ};
    uint8_t best = BW_100HZ;
    int best_diff = 0x7FFF;
    for (uint8_t i = 0; i < sizeof(hz_table) / sizeof(hz_table[0]); i++) {
        int diff = abs((int)hz_table[i] - (int)bandwidth_hz);
        if (diff < best_diff) {
            best_diff = diff;
            best = bw_table[i];
        }
    }
    return best;
}

void BMA150Full::set_range(uint8_t range_g) {
    uint8_t range_mask;
    switch (range_g) {
        case 4:  range_mask = RANGE_4G_MASK; break;
        case 8:  range_mask = RANGE_8G_MASK; break;
        case 2:
        default: range_mask = RANGE_2G_MASK; break;
    }
    _range_g = (range_g == 4) ? 4 : (range_g == 8 ? 8 : 2);
    uint8_t rb = _read_reg(REG_RANGE_BW);
    // Preserve calibration bits 7:5; clear range + bandwidth; re-apply range only.
    rb = static_cast<uint8_t>((rb & 0xE0) | range_mask | (rb & 0x07));
    _write_reg(REG_RANGE_BW, rb);
}

void BMA150Full::set_bandwidth(uint16_t bandwidth_hz) {
    uint8_t bw_code = _nearest_bandwidth(bandwidth_hz);
    uint8_t rb = _read_reg(REG_RANGE_BW);
    // Preserve calibration bits 7:5 and range bits 4:3; clear bw bits 2:0.
    rb = static_cast<uint8_t>((rb & 0xF8) | bw_code);
    _write_reg(REG_RANGE_BW, rb);
}

float BMA150Full::read_temperature() {
    uint8_t raw = _read_reg(REG_TEMP);
    return (float)raw * 0.5f - 30.0f;
}

bool BMA150Full::new_data_available() {
    uint8_t x_lsb = _read_reg(REG_ACC_X_LSB);
    uint8_t y_lsb = _read_reg(REG_ACC_Y_LSB);
    uint8_t z_lsb = _read_reg(REG_ACC_Z_LSB);
    return (x_lsb & 0x01) && (y_lsb & 0x01) && (z_lsb & 0x01);
}

void BMA150Full::set_shadow(bool enabled) {
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (enabled) cfg |= 0x08; else cfg &= (uint8_t)~0x08;
    _write_reg(REG_CONFIG, cfg);
}

void BMA150Full::_write_threshold(uint8_t reg, float threshold_g) {
    int code = (int)lroundf(threshold_g * 255.0f / (float)_range_g);
    if (code < 0) code = 0;
    if (code > 255) code = 255;
    _write_reg(reg, (uint8_t)code);
}

void BMA150Full::_write_hyst(const char* kind, float hysteresis_g) {
    if (hysteresis_g < 0.0f) return;
    int code = (int)lroundf(hysteresis_g * 255.0f / (float)_range_g / 32.0f);
    if (code < 0) code = 0;
    if (code > 7) code = 7;
    uint8_t hd = _read_reg(REG_HYST_DUR);
    if (kind[0] == 'l') {
        // LG_hyst: bits 2:0.
        hd = static_cast<uint8_t>((hd & 0xF8) | (uint8_t)code);
    } else {
        // HG_hyst: bits 5:3.
        hd = static_cast<uint8_t>((hd & 0xC7) | ((uint8_t)code << 3));
    }
    _write_reg(REG_HYST_DUR, hd);
}

void BMA150Full::_write_int_counter(const char* kind, uint8_t counter) {
    uint8_t code = (counter & 0x03) << 4;  // 00=reset, 10=1, 20=2, 30=3 per ms
    if (counter > 3) return;
    uint8_t ic = _read_reg(REG_INT_CTRL);
    if (kind[0] == 'l') {
        // counter_LG: bits 3:2.
        ic = static_cast<uint8_t>((ic & 0xF3) | (uint8_t)code);
    } else {
        // counter_HG: bits 5:4.
        ic = static_cast<uint8_t>((ic & 0xCF) | (uint8_t)(code << 2));
    }
    _write_reg(REG_INT_CTRL, ic);
}

void BMA150Full::set_low_g(float threshold_g, uint16_t duration_ms,
                           float hysteresis_g, uint8_t counter) {
    _write_threshold(REG_LG_THRES, threshold_g);
    _write_reg(REG_LG_DUR, (uint8_t)(duration_ms > 255 ? 255 : duration_ms));
    _write_hyst("lg", hysteresis_g);
    _write_int_counter("lg", counter);
    _enable_source(SOURCE_LOW_G);
}

void BMA150Full::set_high_g(float threshold_g, uint16_t duration_ms,
                            float hysteresis_g, uint8_t counter) {
    _write_threshold(REG_HG_THRES, threshold_g);
    _write_reg(REG_HG_DUR, (uint8_t)(duration_ms > 255 ? 255 : duration_ms));
    _write_hyst("hg", hysteresis_g);
    _write_int_counter("hg", counter);
    _enable_source(SOURCE_HIGH_G);
}

void BMA150Full::set_any_motion(float threshold_g, uint8_t samples) {
    // 15.6 mg/LSB at ±2 g; the chip scales with range.
    float scale = (float)(_range_g == 4 ? SCALE_4G : (_range_g == 8 ? SCALE_8G : SCALE_2G)) / 256.0f;
    int code = (int)lroundf(threshold_g / (0.0156f * scale));
    if (code < 0) code = 0;
    if (code > 255) code = 255;
    _write_reg(REG_ANY_MOTION_THRES, (uint8_t)code);

    uint8_t dur_code = 0;
    switch (samples) {
        case 3: dur_code = 0x40; break;
        case 5: dur_code = 0x80; break;
        case 7: dur_code = 0xC0; break;
        case 1:
        default: dur_code = 0x00; break;
    }
    uint8_t hd = _read_reg(REG_HYST_DUR);
    hd = static_cast<uint8_t>((hd & 0x3F) | dur_code);
    _write_reg(REG_HYST_DUR, hd);

    // any-motion needs enable_adv_INT=1.
    uint8_t cfg = _read_reg(REG_CONFIG);
    cfg |= 0x40;
    _write_reg(REG_CONFIG, cfg);

    _enable_source(SOURCE_ANY_MOTION);
}

void BMA150Full::set_alert(bool enabled) {
    if (enabled) {
        _enabled_sources &= (uint8_t)~SOURCE_ANY_MOTION;
        uint8_t cfg = _read_reg(REG_CONFIG);
        cfg |= 0x40;  // enable_adv_INT
        _write_reg(REG_CONFIG, cfg);
        _enable_source(SOURCE_ALERT);
    } else {
        _disable_source(SOURCE_ALERT);
    }
}

void BMA150Full::set_latch(bool enabled) {
    uint8_t cfg = _read_reg(REG_CONFIG);
    if (enabled) cfg |= 0x10; else cfg &= (uint8_t)~0x10;
    _write_reg(REG_CONFIG, cfg);
}

void BMA150Full::clear_interrupt() {
    if (_sleeping) return;
    uint8_t ctrl = _read_reg(REG_CTRL);
    _write_reg(REG_CTRL, ctrl | 0x40);
}

void BMA150Full::enable_interrupt(uint8_t source) {
    if (source == SOURCE_NEW_DATA) {
        _enabled_sources &= 0x0F;
    } else {
        _enabled_sources &= (uint8_t)~SOURCE_NEW_DATA;
        if (source == SOURCE_ANY_MOTION) {
            _enabled_sources &= (uint8_t)~SOURCE_ALERT;
        } else if (source == SOURCE_ALERT) {
            _enabled_sources &= (uint8_t)~SOURCE_ANY_MOTION;
        }
    }
    _enable_source(source);
}

void BMA150Full::disable_interrupt(uint8_t source) {
    _disable_source(source);
}

uint8_t BMA150Full::poll_interrupt() {
    return _read_reg(REG_STATUS);
}

void BMA150Full::set_wake_up(bool enabled, uint16_t pause_ms) {
    uint8_t pause_code;
    switch (pause_ms) {
        case 80:   pause_code = 0x02; break;
        case 320:  pause_code = 0x04; break;
        case 2560: pause_code = 0x06; break;
        case 20:
        default:   pause_code = 0x00; break;
    }
    uint8_t cfg = _read_reg(REG_CONFIG);
    cfg = static_cast<uint8_t>((cfg & 0xF9) | pause_code);
    if (enabled) cfg |= 0x01; else cfg &= (uint8_t)~0x01;
    _write_reg(REG_CONFIG, cfg);
}

void BMA150Full::sleep() {
    if (_sleeping) return;
    uint8_t ctrl = _read_reg(REG_CTRL);
    _write_reg(REG_CTRL, ctrl | 0x01);
    _sleeping = true;
}

void BMA150Full::wake() {
    if (!_sleeping) return;
    uint8_t ctrl = _read_reg(REG_CTRL);
    _write_reg(REG_CTRL, ctrl & (uint8_t)~0x01);
    delay(2);
    _sleeping = false;
}

void BMA150Full::soft_reset() {
    uint8_t ctrl = _read_reg(REG_CTRL);
    _write_reg(REG_CTRL, ctrl | 0x02);
    delay(30);
    // Restore range/bandwidth without touching calibration bits 7:5.
    uint8_t range_mask;
    switch (_range_g) {
        case 4:  range_mask = RANGE_4G_MASK; break;
        case 8:  range_mask = RANGE_8G_MASK; break;
        case 2:
        default: range_mask = RANGE_2G_MASK; break;
    }
    uint8_t rb = _read_reg(REG_RANGE_BW);
    rb = static_cast<uint8_t>((rb & 0xE0) | range_mask | BW_100HZ);
    _write_reg(REG_RANGE_BW, rb);
    _sleeping = false;
}

bool BMA150Full::self_test() {
    uint8_t ctrl = _read_reg(REG_CTRL);
    _write_reg(REG_CTRL, ctrl | 0x04);
    delay(100);
    uint8_t status = _read_reg(REG_STATUS);
    _write_reg(REG_CTRL, ctrl);
    return (status & STATUS_ST_RESULT) != 0;
}

uint8_t BMA150Full::read_status() {
    return _read_reg(REG_STATUS);
}

void BMA150Full::read_version(uint8_t& al_version, uint8_t& ml_version) {
    uint8_t raw = _read_reg(REG_VERSION);
    al_version = (raw >> 4) & 0x0F;
    ml_version = raw & 0x0F;
}

uint8_t BMA150Full::read_customer(uint8_t index) {
    if (index == 0) return _read_reg(REG_CUSTOMER_1);
    return _read_reg(REG_CUSTOMER_2);
}

void BMA150Full::write_customer(uint8_t index, uint8_t value) {
    if (index == 0) _write_reg(REG_CUSTOMER_1, value);
    else            _write_reg(REG_CUSTOMER_2, value);
}

void BMA150Full::_enable_source(uint8_t source) {
    if (_sleeping) return;
    _enabled_sources |= source;
    if (source == SOURCE_NEW_DATA) {
        uint8_t cfg = _read_reg(REG_CONFIG);
        cfg |= 0x20;
        _write_reg(REG_CONFIG, cfg);
        return;
    }
    uint8_t ic = _read_reg(REG_INT_CTRL);
    if (source == SOURCE_LOW_G)      ic |= 0x01;
    if (source == SOURCE_HIGH_G)     ic |= 0x02;
    if (source == SOURCE_ANY_MOTION) ic |= 0x40;
    if (source == SOURCE_ALERT)     ic |= 0x80;
    _write_reg(REG_INT_CTRL, ic);
}

void BMA150Full::_disable_source(uint8_t source) {
    _enabled_sources &= (uint8_t)~source;
    if (source == SOURCE_NEW_DATA) {
        uint8_t cfg = _read_reg(REG_CONFIG);
        cfg &= (uint8_t)~0x20;
        _write_reg(REG_CONFIG, cfg);
        return;
    }
    uint8_t ic = _read_reg(REG_INT_CTRL);
    if (source == SOURCE_LOW_G)      ic &= (uint8_t)~0x01;
    if (source == SOURCE_HIGH_G)     ic &= (uint8_t)~0x02;
    if (source == SOURCE_ANY_MOTION) ic &= (uint8_t)~0x40;
    if (source == SOURCE_ALERT)     ic &= (uint8_t)~0x80;
    _write_reg(REG_INT_CTRL, ic);
}
