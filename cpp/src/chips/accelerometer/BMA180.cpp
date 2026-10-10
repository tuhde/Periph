#include "BMA180.h"
#include <stdlib.h>
#include <math.h>
#include <string.h>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/Freeertos.h>
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

// Low-pass bandwidth codes (BW_TCS bits 7:4), 1010-1111 not authorised.
static const uint16_t BW_HZ_TABLE[] = {10, 20, 40, 75, 150, 300, 600, 1200};
static const uint8_t  BW_CODE_TABLE[] = {0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70};

// Tap sensitivity duration codes (GAIN_T bits 2:0).
static const uint16_t TAP_MS_TABLE[] = {50, 75, 100, 150, 250, 500, 750, 1000};
static const uint8_t  TAP_CODE_TABLE[] = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};

// Duration time base: T_update = 417 µs, *dur = 5 * T_update ≈ 2.085 ms/LSB.
static constexpr float DUR_LSB_MS = 2.085f;

BMA180Minimal::BMA180Minimal(RegisterConnection& connection)
    : _connection(connection) {
    // First transaction must be something other than an acc LSB read (the
    // chip returns MSB=0 for the first LSB read after power-up). Read
    // CHIP_ID first.
    uint8_t chip_id = _read_reg(REG_CHIP_ID);
    if ((chip_id & CHIP_ID_MASK) != CHIP_ID_VALUE) {
        (void)chip_id;
        abort();
    }

    // Unlock image registers (0x20-0x3B) by setting ee_w = 1.
    uint8_t ctrl0 = _read_reg(REG_CTRL_REG0);
    ctrl0 |= CTRL_REG0_EE_W;
    _write_reg(REG_CTRL_REG0, ctrl0);

    // Set range = ±2 g via OFFSET_LSB1 bits 3:1, preserving offset_x LSBs
    // (bits 7:4) and smp_skip (bit 0).
    uint8_t olsb1 = _read_reg(REG_OFFSET_LSB1);
    olsb1 = (uint8_t)((olsb1 & 0xF1) | RANGE_2G);
    _write_reg(REG_OFFSET_LSB1, olsb1);

    // Set bw = 150 Hz via BW_TCS bits 7:4, preserving tcs bits 3:0.
    uint8_t bw_code = _nearest_bandwidth(150);
    uint8_t bw = _read_reg(REG_BW_TCS);
    bw = (uint8_t)((bw & 0x0F) | bw_code);
    _write_reg(REG_BW_TCS, bw);

    // Wait 1/(2*bw) for filtered data to settle.
    _delay_ms(4);
}

void BMA180Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t BMA180Minimal::_read_reg(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void BMA180Minimal::_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.read(reg, buf, len);
}

void BMA180Minimal::_delay_ms(uint32_t ms) {
    delay(ms);
}

uint8_t BMA180Minimal::_nearest_bandwidth(uint16_t bandwidth_hz) {
    uint8_t best = 0x40;  // 150 Hz
    int best_diff = 0x7FFF;
    for (uint8_t i = 0; i < sizeof(BW_HZ_TABLE) / sizeof(BW_HZ_TABLE[0]); i++) {
        int diff = abs((int)BW_HZ_TABLE[i] - (int)bandwidth_hz);
        if (diff < best_diff) {
            best_diff = diff;
            best = BW_CODE_TABLE[i];
        }
    }
    return best;
}

void BMA180Minimal::read(float& x, float& y, float& z) {
    uint8_t raw[6];
    _read_burst(REG_ACC_X_LSB, raw, 6);
    // 14-bit two's complement; bit 1 of each LSB is 0, bit 0 is new_data.
    int16_t rx = (int16_t)toSigned(((uint32_t)raw[1] << 6) | (raw[0] >> 2), 14);
    int16_t ry = (int16_t)toSigned(((uint32_t)raw[3] << 6) | (raw[2] >> 2), 14);
    int16_t rz = (int16_t)toSigned(((uint32_t)raw[5] << 6) | (raw[4] >> 2), 14);
    float scale;
    if      (_range_g == 1)   scale = SCALE_1G;
    else if (_range_g == 1.5f) scale = SCALE_1_5G;
    else if (_range_g == 2)   scale = SCALE_2G;
    else if (_range_g == 3)   scale = SCALE_3G;
    else if (_range_g == 4)   scale = SCALE_4G;
    else if (_range_g == 8)   scale = SCALE_8G;
    else if (_range_g == 16)  scale = SCALE_16G;
    else                      scale = SCALE_2G;
    x = (float)rx / scale;
    y = (float)ry / scale;
    z = (float)rz / scale;
}

// BMA180Full

BMA180Full::BMA180Full(RegisterConnection& connection)
    : BMA180Minimal(connection) {}

int16_t BMA180Full::_decode_axis(const uint8_t* p) {
    return (int16_t)toSigned(((uint32_t)p[1] << 6) | (p[0] >> 2), 14);
}

void BMA180Full::_read_raw_counts(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _read_burst(REG_ACC_X_LSB, raw, 6);
    x = _decode_axis(&raw[0]);
    y = _decode_axis(&raw[2]);
    z = _decode_axis(&raw[4]);
}

void BMA180Full::read_raw(int16_t& x, int16_t& y, int16_t& z) {
    _read_raw_counts(x, y, z);
}

void BMA180Full::set_range(float range_g) {
    uint8_t bits;
    if      (range_g == 1)   bits = RANGE_1G;
    else if (range_g == 1.5f) bits = RANGE_1_5G;
    else if (range_g == 2)   bits = RANGE_2G;
    else if (range_g == 3)   bits = RANGE_3G;
    else if (range_g == 4)   bits = RANGE_4G;
    else if (range_g == 8)   bits = RANGE_8G;
    else if (range_g == 16)  bits = RANGE_16G;
    else { (void)range_g; bits = RANGE_2G; }
    _range_g = range_g;
    _range_bits = bits;
    uint8_t olsb1 = _read_reg(REG_OFFSET_LSB1);
    olsb1 = (uint8_t)((olsb1 & ~OLSB1_RANGE_MASK) | bits);
    _write_reg(REG_OFFSET_LSB1, olsb1);
}

void BMA180Full::set_bandwidth(uint16_t bandwidth_hz) {
    uint8_t bw_code = _nearest_bandwidth(bandwidth_hz);
    uint8_t bw = _read_reg(REG_BW_TCS);
    bw = (uint8_t)((bw & 0x0F) | bw_code);
    _write_reg(REG_BW_TCS, bw);
    _delay_ms(4);
}

void BMA180Full::set_filter_mode(uint8_t mode) {
    if (mode == 0) return;
    uint8_t code;
    if (mode == 1)      code = BW_HIGH_PASS_1HZ;
    else if (mode == 2) code = BW_BAND_PASS;
    else { (void)mode; return; }
    uint8_t bw = _read_reg(REG_BW_TCS);
    bw = (uint8_t)((bw & 0x0F) | code);
    _write_reg(REG_BW_TCS, bw);
    _delay_ms(4);
}

void BMA180Full::set_mode(uint8_t mode) {
    if (mode > 3) return;
    uint8_t tcoz = _read_reg(REG_TCO_Z);
    tcoz = (uint8_t)((tcoz & ~TCO_Z_MODE_MASK) | (mode & 0x03));
    _write_reg(REG_TCO_Z, tcoz);
}

void BMA180Full::set_resolution(uint8_t bits) {
    uint8_t ot = _read_reg(REG_OFFSET_T);
    if (bits == 12)      ot |= OT_12BIT_MASK;
    else if (bits == 14) ot &= (uint8_t)~OT_12BIT_MASK;
    else return;
    _write_reg(REG_OFFSET_T, ot);
}

float BMA180Full::read_temperature() {
    uint8_t raw = _read_reg(REG_TEMP);
    int8_t s = (raw < 128) ? (int8_t)raw : (int8_t)(raw - 256);
    return 25.0f + ((float)s - 2.0f) * 0.5f;
}

bool BMA180Full::new_data_available() {
    uint8_t x_lsb = _read_reg(REG_ACC_X_LSB);
    uint8_t y_lsb = _read_reg(REG_ACC_Y_LSB);
    uint8_t z_lsb = _read_reg(REG_ACC_Z_LSB);
    return ((x_lsb & 0x01) && (y_lsb & 0x01) && (z_lsb & 0x01));
}

void BMA180Full::set_shadow(bool enabled) {
    uint8_t gy = _read_reg(REG_GAIN_Y);
    if (enabled) gy &= (uint8_t)~GY_SHADOW;
    else         gy |= GY_SHADOW;
    _write_reg(REG_GAIN_Y, gy);
}

void BMA180Full::set_sample_skip(bool enabled) {
    uint8_t olsb1 = _read_reg(REG_OFFSET_LSB1);
    if (enabled) olsb1 |= OLSB1_SMP_SKIP;
    else         olsb1 &= (uint8_t)~OLSB1_SMP_SKIP;
    _write_reg(REG_OFFSET_LSB1, olsb1);
}

void BMA180Full::_write_threshold(uint8_t reg, float threshold_g) {
    int code = (int)lroundf(threshold_g / _range_g * 255.0f);
    if (code < 0)   code = 0;
    if (code > 255) code = 255;
    _write_reg(reg, (uint8_t)code);
}

void BMA180Full::_write_slope_threshold(uint8_t reg, float threshold_g) {
    int code = (int)lroundf(threshold_g / (0.0156f * _range_g / 2.0f));
    if (code < 0)   code = 0;
    if (code > 255) code = 255;
    _write_reg(reg, (uint8_t)code);
}

void BMA180Full::_write_low_dur(uint16_t duration_ms) {
    int code = (int)lroundf((float)duration_ms / DUR_LSB_MS);
    if (code < 0)   code = 0;
    if (code > 127) code = 127;
    uint8_t ld = _read_reg(REG_LOW_DUR);
    ld = (uint8_t)((ld & 0x01) | ((code & 0x7F) << 1));
    _write_reg(REG_LOW_DUR, ld);
}

void BMA180Full::_write_high_dur(uint16_t duration_ms) {
    int code = (int)lroundf((float)duration_ms / DUR_LSB_MS);
    if (code < 0)   code = 0;
    if (code > 127) code = 127;
    uint8_t hd = _read_reg(REG_HIGH_DUR);
    hd = (uint8_t)((hd & 0x01) | ((code & 0x7F) << 1));
    _write_reg(REG_HIGH_DUR, hd);
}

void BMA180Full::_write_low_hy(float hysteresis_g) {
    int code = (int)lroundf(hysteresis_g / _range_g * 255.0f / 32.0f);
    if (code < 0)  code = 0;
    if (code > 31) code = 31;
    uint8_t hy = _read_reg(REG_HY);
    hy = (uint8_t)((hy & ~HY_LOW_MASK) | (code & HY_LOW_MASK));
    _write_reg(REG_HY, hy);
    uint8_t cr4 = _read_reg(REG_CTRL_REG4);
    cr4 = (uint8_t)((cr4 & ~(0x03 << CR4_LOW_HY_SHIFT)) | (((code >> 3) & 0x03) << CR4_LOW_HY_SHIFT));
    _write_reg(REG_CTRL_REG4, cr4);
}

void BMA180Full::_write_high_hy(float hysteresis_g) {
    int code = (int)lroundf(hysteresis_g / _range_g * 255.0f / 32.0f);
    if (code < 0)  code = 0;
    if (code > 31) code = 31;
    uint8_t hy = _read_reg(REG_HY);
    hy = (uint8_t)((hy & 0x07) | ((code & 0x1F) << HY_HIGH_SHIFT));
    _write_reg(REG_HY, hy);
}

void BMA180Full::_write_low_axis_enables(uint8_t axes) {
    uint8_t hli = _read_reg(REG_HIGH_LOW_INFO);
    hli = (uint8_t)((hli & 0xF1) | ((axes & 0x07) << HLI_LOW_AXIS_SHIFT));
    _write_reg(REG_HIGH_LOW_INFO, hli);
}

void BMA180Full::_write_high_axis_enables(uint8_t axes) {
    uint8_t hli = _read_reg(REG_HIGH_LOW_INFO);
    hli = (uint8_t)((hli & 0x0F) | ((axes & 0x07) << HLI_HIGH_AXIS_SHIFT));
    _write_reg(REG_HIGH_LOW_INFO, hli);
}

void BMA180Full::_write_slope_axis_enables(uint8_t axes) {
    uint8_t st = _read_reg(REG_SLOPE_TAPSENS);
    st = (uint8_t)((st & 0x0F) | ((axes & 0x07) << STI_SLOPE_AXIS_SHIFT));
    _write_reg(REG_SLOPE_TAPSENS, st);
}

void BMA180Full::_write_tap_axis_enables(uint8_t axes) {
    uint8_t st = _read_reg(REG_SLOPE_TAPSENS);
    st = (uint8_t)((st & 0xF1) | ((axes & 0x07) << STI_TAP_AXIS_SHIFT));
    _write_reg(REG_SLOPE_TAPSENS, st);
}

void BMA180Full::_write_filt_bit(uint8_t reg, uint8_t bit, bool enabled) {
    uint8_t v = _read_reg(reg);
    if (enabled) v |= bit;
    else         v &= (uint8_t)~bit;
    _write_reg(reg, v);
}

void BMA180Full::_write_debounce(const char* kind, uint8_t counter) {
    if (counter > 3) return;
    uint8_t code = (counter & 0x03) << 2;  // LG pos (bits 3:2); HG shifts by 2 more
    uint8_t cr4 = _read_reg(REG_CTRL_REG4);
    if (kind[0] == 'l') {
        cr4 = (uint8_t)((cr4 & ~(0x03 << CR4_FF_CD_SHIFT)) | (code & (0x03 << CR4_FF_CD_SHIFT)));
    } else {
        cr4 = (uint8_t)((cr4 & ~(0x03 << CR4_MOT_CD_SHIFT)) | ((code << 2) & (0x03 << CR4_MOT_CD_SHIFT)));
    }
    _write_reg(REG_CTRL_REG4, cr4);
}

void BMA180Full::_write_slope_dur(uint8_t samples) {
    uint8_t code;
    switch (samples) {
        case 3:  code = 0x01; break;
        case 5:  code = 0x02; break;
        case 7:  code = 0x03; break;
        case 1:
        default: code = 0x00; break;
    }
    uint8_t tcox = _read_reg(REG_TCO_X);
    tcox = (uint8_t)((tcox & ~TCO_X_SLOPE_MASK) | code);
    _write_reg(REG_TCO_X, tcox);
}

uint8_t BMA180Full::_nearest_tap_dur(uint16_t window_ms) {
    uint8_t best = 0x04;  // 250 ms
    int best_diff = 0x7FFF;
    for (uint8_t i = 0; i < sizeof(TAP_MS_TABLE) / sizeof(TAP_MS_TABLE[0]); i++) {
        if (TAP_MS_TABLE[i] >= window_ms) {
            int diff = (int)TAP_MS_TABLE[i] - (int)window_ms;
            if (diff < best_diff) {
                best_diff = diff;
                best = TAP_CODE_TABLE[i];
            }
        }
    }
    return best;
}

void BMA180Full::_write_tap_dur(uint16_t window_ms) {
    uint8_t code = _nearest_tap_dur(window_ms);
    uint8_t gt = _read_reg(REG_GAIN_T);
    gt = (uint8_t)((gt & ~GT_TAP_MASK) | code);
    _write_reg(REG_GAIN_T, gt);
}

void BMA180Full::_write_cr3_bit(uint8_t bit, bool enabled) {
    if (_sleeping) return;
    uint8_t cr3 = _read_reg(REG_CTRL_REG3);
    if (enabled) cr3 |= bit;
    else         cr3 &= (uint8_t)~bit;
    _write_reg(REG_CTRL_REG3, cr3);
}

void BMA180Full::set_low_g(float threshold_g, uint16_t duration_ms,
                           float hysteresis_g, uint8_t axes,
                           uint8_t counter, bool filtered) {
    _write_threshold(REG_LOW_TH, threshold_g);
    _write_low_dur(duration_ms);
    _write_low_hy(hysteresis_g);
    _write_low_axis_enables(axes);
    _write_filt_bit(REG_HIGH_LOW_INFO, HLI_LOW_FILT_BIT, filtered);
    _write_debounce("lg", counter);
    _enable_source(SOURCE_LOW_G);
}

void BMA180Full::set_high_g(float threshold_g, uint16_t duration_ms,
                            float hysteresis_g, uint8_t axes,
                            uint8_t counter, bool filtered) {
    _write_threshold(REG_HIGH_TH, threshold_g);
    _write_high_dur(duration_ms);
    _write_high_hy(hysteresis_g);
    _write_high_axis_enables(axes);
    _write_filt_bit(REG_HIGH_LOW_INFO, HLI_HIGH_FILT_BIT, filtered);
    _write_debounce("hg", counter);
    _enable_source(SOURCE_HIGH_G);
}

void BMA180Full::set_slope(float threshold_g, uint8_t samples,
                           uint8_t axes, bool filtered) {
    _write_slope_threshold(REG_SLOPE_TH, threshold_g);
    _write_slope_dur(samples);
    _write_slope_axis_enables(axes);
    _write_filt_bit(REG_SLOPE_TAPSENS, STI_SLOPE_FILT_BIT, filtered);
    _write_cr3_bit(CR3_SLOPE_INT, true);
    _write_cr3_bit(CR3_SLOPE_ALERT, false);
    _write_cr3_bit(CR3_ADV_INT, true);
    _enable_source(SOURCE_SLOPE);
}

void BMA180Full::set_alert(bool enabled) {
    if (enabled) {
        _enabled_sources &= (uint8_t)~SOURCE_SLOPE;
        _write_cr3_bit(CR3_SLOPE_INT, false);
        _write_cr3_bit(CR3_SLOPE_ALERT, true);
        _write_cr3_bit(CR3_ADV_INT, true);
        _enable_source(SOURCE_ALERT);
    } else {
        _disable_source(SOURCE_ALERT);
        _write_cr3_bit(CR3_SLOPE_ALERT, false);
    }
}

void BMA180Full::set_tap(float threshold_g, uint16_t window_ms,
                         uint8_t axes, bool filtered) {
    _write_slope_threshold(REG_TAPSENS_TH, threshold_g);
    _write_tap_dur(window_ms);
    _write_tap_axis_enables(axes);
    _write_filt_bit(REG_SLOPE_TAPSENS, STI_TAP_FILT_BIT, filtered);
    _enable_source(SOURCE_TAP);
}

void BMA180Full::set_latch(bool enabled) {
    _write_cr3_bit(CR3_LAT_INT, enabled);
}

void BMA180Full::clear_interrupt() {
    if (_sleeping) return;
    uint8_t ctrl0 = _read_reg(REG_CTRL_REG0);
    _write_reg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_RESET_INT);
}

void BMA180Full::enable_interrupt(uint8_t source) {
    if (source == SOURCE_NEW_DATA) {
        _write_cr3_bit(CR3_NEW_DATA_INT, true);
    } else {
        _write_cr3_bit(CR3_NEW_DATA_INT, false);
        if (source == SOURCE_SLOPE) {
            _write_cr3_bit(CR3_SLOPE_ALERT, false);
            _write_cr3_bit(CR3_SLOPE_INT, true);
            _write_cr3_bit(CR3_ADV_INT, true);
            _disable_source(SOURCE_ALERT);
        } else if (source == SOURCE_ALERT) {
            _write_cr3_bit(CR3_SLOPE_INT, false);
            _write_cr3_bit(CR3_SLOPE_ALERT, true);
            _write_cr3_bit(CR3_ADV_INT, true);
            _disable_source(SOURCE_SLOPE);
        } else if (source == SOURCE_HIGH_G) {
            _write_cr3_bit(CR3_HIGH_INT, true);
        } else if (source == SOURCE_LOW_G) {
            _write_cr3_bit(CR3_LOW_INT, true);
        } else if (source == SOURCE_TAP) {
            _write_cr3_bit(CR3_TAP_INT, true);
        }
    }
    _enable_source(source);
}

void BMA180Full::disable_interrupt(uint8_t source) {
    if (source == SOURCE_NEW_DATA)        _write_cr3_bit(CR3_NEW_DATA_INT, false);
    else if (source == SOURCE_SLOPE)      _write_cr3_bit(CR3_SLOPE_INT, false);
    else if (source == SOURCE_ALERT) {
        _write_cr3_bit(CR3_SLOPE_ALERT, false);
        _write_cr3_bit(CR3_ADV_INT, false);
    } else if (source == SOURCE_HIGH_G)   _write_cr3_bit(CR3_HIGH_INT, false);
    else if (source == SOURCE_LOW_G)      _write_cr3_bit(CR3_LOW_INT, false);
    else if (source == SOURCE_TAP)        _write_cr3_bit(CR3_TAP_INT, false);
    _disable_source(source);
}

uint8_t BMA180Full::poll_interrupt() {
    return _read_reg(REG_STATUS_REG3);
}

void BMA180Full::read_status(uint8_t& s1, uint8_t& s2, uint8_t& s3, uint8_t& s4) {
    s1 = _read_reg(REG_STATUS_REG1);
    s2 = _read_reg(REG_STATUS_REG2);
    s3 = _read_reg(REG_STATUS_REG3);
    s4 = _read_reg(REG_STATUS_REG4);
}

void BMA180Full::set_wake_up(bool enabled, uint16_t pause_ms) {
    uint8_t code;
    switch (pause_ms) {
        case 80:    code = 0x01; break;
        case 320:   code = 0x02; break;
        case 2560:  code = 0x03; break;
        case 20:
        default:    code = 0x00; break;
    }
    uint8_t tcoy = _read_reg(REG_TCO_Y);
    tcoy = (uint8_t)((tcoy & ~TCO_Y_WAKE_MASK) | code);
    _write_reg(REG_TCO_Y, tcoy);
    uint8_t gz = _read_reg(REG_GAIN_Z);
    if (enabled) gz |= GZ_WAKE_UP;
    else         gz &= (uint8_t)~GZ_WAKE_UP;
    _write_reg(REG_GAIN_Z, gz);
}

void BMA180Full::sleep() {
    if (_sleeping) return;
    uint8_t ctrl0 = _read_reg(REG_CTRL_REG0);
    _write_reg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_SLEEP);
    _sleeping = true;
}

void BMA180Full::wake() {
    if (!_sleeping) return;
    uint8_t ctrl0 = _read_reg(REG_CTRL_REG0);
    _write_reg(REG_CTRL_REG0, ctrl0 & (uint8_t)~CTRL_REG0_SLEEP);
    _delay_ms(2);
    _sleeping = false;
}

void BMA180Full::soft_reset() {
    _write_reg(REG_RESET, SOFT_RESET_CMD);
    _delay_ms(30);
    _range_g = 2.0f;
    _range_bits = RANGE_2G;
    uint8_t chip_id = _read_reg(REG_CHIP_ID);
    if ((chip_id & CHIP_ID_MASK) != CHIP_ID_VALUE) {
        (void)chip_id;
        abort();
    }
    uint8_t ctrl0 = _read_reg(REG_CTRL_REG0);
    ctrl0 |= CTRL_REG0_EE_W;
    _write_reg(REG_CTRL_REG0, ctrl0);
    uint8_t olsb1 = _read_reg(REG_OFFSET_LSB1);
    olsb1 = (uint8_t)((olsb1 & 0xF1) | RANGE_2G);
    _write_reg(REG_OFFSET_LSB1, olsb1);
    uint8_t bw_code = _nearest_bandwidth(150);
    uint8_t bw = _read_reg(REG_BW_TCS);
    bw = (uint8_t)((bw & 0x0F) | bw_code);
    _write_reg(REG_BW_TCS, bw);
    _delay_ms(4);
    _sleeping = false;
}

bool BMA180Full::self_test() {
    uint8_t ctrl0 = _read_reg(REG_CTRL_REG0);
    _write_reg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_ST0);
    _delay_ms(10);
    int16_t x, y, z;
    _read_raw_counts(x, y, z);
    _write_reg(REG_CTRL_REG0, ctrl0);
    bool ok = (abs(x) > 200) && (abs(y) > 200) && (abs(z) > 200);
    soft_reset();
    return ok;
}

void BMA180Full::calibrate_offset(uint8_t axes, uint8_t mode) {
    if (mode > 3) return;
    // offset_finetuning = mode (bits 1:0 of CTRL_REG4 0x22).
    uint8_t cr4 = _read_reg(REG_CTRL_REG4);
    cr4 = (uint8_t)((cr4 & 0xFC) | (mode & 0x03));
    _write_reg(REG_CTRL_REG4, cr4);
    // Per-axis calibration.
    struct { uint8_t bit; uint8_t mask; } ax[3] = {
        {0x80, 0x01},  // en_offset_x
        {0x40, 0x02},  // en_offset_y
        {0x20, 0x04},  // en_offset_z
    };
    for (uint8_t i = 0; i < 3; i++) {
        if (!(axes & ax[i].mask)) continue;
        uint8_t ctrl1 = _read_reg(REG_CTRL_REG1);
        ctrl1 |= ax[i].bit;
        _write_reg(REG_CTRL_REG1, ctrl1);
        // Wait for offset_st_s (bit 1 of STATUS_REG1).
        for (uint8_t t = 0; t < 100; t++) {
            uint8_t s1 = _read_reg(REG_STATUS_REG1);
            if (s1 & 0x02) break;
            _delay_ms(100);
        }
        ctrl1 = _read_reg(REG_CTRL_REG1);
        ctrl1 &= (uint8_t)~ax[i].bit;
        _write_reg(REG_CTRL_REG1, ctrl1);
    }
    // Restore offset_finetuning = 00.
    cr4 = _read_reg(REG_CTRL_REG4);
    cr4 &= 0xFC;
    _write_reg(REG_CTRL_REG4, cr4);
}

void BMA180Full::read_version(uint8_t& al_version, uint8_t& ml_version) {
    uint8_t raw = _read_reg(REG_VERSION);
    al_version = (raw >> 4) & 0x0F;
    ml_version = raw & 0x0F;
}

uint8_t BMA180Full::read_customer(uint8_t index) {
    if (index == 0) return _read_reg(REG_CD1);
    return _read_reg(REG_CD2);
}

void BMA180Full::write_customer(uint8_t index, uint8_t value) {
    if (index == 0) _write_reg(REG_CD1, value);
    else            _write_reg(REG_CD2, value);
}

void BMA180Full::_enable_source(uint8_t source) {
    if (_sleeping) return;
    _enabled_sources |= source;
}

void BMA180Full::_disable_source(uint8_t source) {
    _enabled_sources &= (uint8_t)~source;
}