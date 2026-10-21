#include "LIS3DH.h"
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

// Sensitivity in g/LSB by mode and full-scale range (datasheet Table 4).
// Row index = MODE_LOW_POWER / MODE_NORMAL / MODE_HIGH_RES; column index =
// range code 0..3 (±2 / ±4 / ±8 / ±16 g).
static const float kSensitivityG[3][4] = {
    {16.0f / 1000.0f, 32.0f / 1000.0f, 64.0f / 1000.0f, 192.0f / 1000.0f},   // low-power (8-bit)
    {4.0f / 1000.0f,  8.0f / 1000.0f,  16.0f / 1000.0f,  48.0f / 1000.0f},   // normal (10-bit)
    {1.0f / 1000.0f,  2.0f / 1000.0f,   4.0f / 1000.0f,  12.0f / 1000.0f},   // high-res (12-bit)
};

// Right-shift applied to the left-justified 16-bit output pair, by mode.
static const uint8_t kShift[3] = {8, 6, 4};  // low-power, normal, high-res

// Interrupt-threshold LSB in mg by full-scale range (datasheet scale,
// full-scale / 128; the ±16 g value is taken as printed: 186 mg).
static const uint16_t kThresholdLsbMg[4] = {16, 32, 62, 186};

LIS3DHFull* LIS3DHFull::_activeInstance = nullptr;
uint8_t    LIS3DHFull::_activePin = 0;

LIS3DHMinimal::LIS3DHMinimal(RegisterConnection& connection)
    : _connection(connection) {
    // Step 2: verify WHO_AM_I (0x0F == 0x33).
    uint8_t chip_id = _read_reg(REG_WHO_AM_I);
    if (chip_id != WHO_AM_I_VALUE) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        abort();
    }

    // Step 3: no soft reset — put the control registers in a known state.
    // CTRL_REG0 and the interrupt-generator registers are left untouched
    // (reserved registers are never written).
    _write_reg(REG_CTRL_REG2, 0x00);
    _write_reg(REG_CTRL_REG3, 0x00);
    _write_reg(REG_CTRL_REG5, 0x00);
    _write_reg(REG_CTRL_REG6, 0x00);
    _write_reg(REG_FIFO_CTRL, 0x00);
    _write_reg(REG_TEMP_CFG, 0x00);

    // Step 4: block data update, ±2 g, high-resolution 12-bit mode.
    _write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT);

    // Step 5: leave power-down — this starts the conversions.
    _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);

    // Step 6: wait for the high-resolution turn-on (7/ODR = 70 ms at
    // 100 Hz) — the longer of the turn-on time and one ODR period.
    _delay_ms(_settle_ms());
}

void LIS3DHMinimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t LIS3DHMinimal::_read_reg(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void LIS3DHMinimal::_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    // Multi-byte I²C transfers set the sub-address MSb (auto-increment).
    _connection.read((uint32_t)(reg | 0x80), buf, len);
}

void LIS3DHMinimal::_delay_ms(uint32_t ms) {
    delay(ms);
}

int16_t LIS3DHMinimal::_decode_axis(uint8_t low, uint8_t high) const {
    uint32_t raw16 = (uint32_t)low | ((uint32_t)high << 8);
    int32_t signed16 = toSigned(raw16, 16);
    return (int16_t)(signed16 >> kShift[_mode]);
}

float LIS3DHMinimal::_sensitivity_g() const {
    return kSensitivityG[_mode][_range_g == 4 ? 1 : (_range_g == 8 ? 2 : (_range_g == 16 ? 3 : 0))];
}

uint32_t LIS3DHMinimal::_settle_ms() const {
    // The longer of the mode's turn-on time (datasheet Table 10) and one
    // ODR period. Only called with _rate_hz > 0 by the callers.
    float period_ms = 1000.0f / (float)_rate_hz;
    float turn_on_ms;
    if (_mode == MODE_LOW_POWER)   turn_on_ms = 1.0f;
    else if (_mode == MODE_NORMAL) turn_on_ms = 1.6f;
    else                           turn_on_ms = 7.0f * period_ms;
    float wait_ms = turn_on_ms > period_ms ? turn_on_ms : period_ms;
    return (uint32_t)lroundf(wait_ms);
}

void LIS3DHMinimal::read(float& x, float& y, float& z) {
    uint8_t raw[6];
    _read_burst(REG_OUT_X_L, raw, 6);
    float scale = _sensitivity_g();
    x = (float)_decode_axis(raw[0], raw[1]) * scale;
    y = (float)_decode_axis(raw[2], raw[3]) * scale;
    z = (float)_decode_axis(raw[4], raw[5]) * scale;
}

// LIS3DHFull

LIS3DHFull::LIS3DHFull(RegisterConnection& connection)
    : LIS3DHMinimal(connection) {}

void LIS3DHFull::_write_reg_rmw(uint8_t reg, uint8_t set_mask, uint8_t clear_mask) {
    uint8_t value = _read_reg(reg);
    value = (uint8_t)((value | set_mask) & (uint8_t)~clear_mask);
    _write_reg(reg, value);
}

void LIS3DHFull::_write_reg_field(uint8_t reg, uint8_t value, uint8_t shift, uint8_t mask) {
    uint8_t old = _read_reg(reg);
    uint8_t combined = (uint8_t)((old & (uint8_t)~(uint8_t)(mask << shift)) | ((value & mask) << shift));
    _write_reg(reg, combined);
}

uint8_t LIS3DHFull::_generator_cfg_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_CFG : REG_INT1_CFG;
}

uint8_t LIS3DHFull::_generator_src_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_SRC : REG_INT1_SRC;
}

uint8_t LIS3DHFull::_generator_ths_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_THS : REG_INT1_THS;
}

uint8_t LIS3DHFull::_generator_dur_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_DUR : REG_INT1_DUR;
}

void LIS3DHFull::set_range(uint8_t range_g) {
    uint8_t fs;
    switch (range_g) {
        case 4:  fs = 1; break;
        case 8:  fs = 2; break;
        case 16: fs = 3; break;
        case 2:
        default: fs = 0; break;
    }
    _range_g = (range_g == 4) ? 4 : (range_g == 8 ? 8 : (range_g == 16 ? 16 : 2));
    _write_reg_field(REG_CTRL_REG4, fs, 4, 0x03);
}

void LIS3DHFull::set_data_rate(uint16_t rate_hz) {
    uint8_t code;
    switch (rate_hz) {
        case 0:    code = 0x00; break;  // power-down
        case 1:    code = 0x01; break;
        case 10:   code = 0x02; break;
        case 25:   code = 0x03; break;
        case 50:   code = 0x04; break;
        case 100:  code = 0x05; break;
        case 200:  code = 0x06; break;
        case 400:  code = 0x07; break;
        case 1620: code = 0x08; break;  // low-power mode only
        case 1344: code = 0x09; break;  // normal / high-resolution only
        case 5376: code = 0x09; break;  // low-power mode only (same code)
        default:   abort(); break;
    }
    if ((rate_hz == 1620 || rate_hz == 5376) && _mode != MODE_LOW_POWER) abort();
    if (rate_hz == 1344 && _mode == MODE_LOW_POWER) abort();
    _write_reg_field(REG_CTRL_REG1, code, 4, 0x0F);
    _rate_hz = rate_hz;
    if (rate_hz > 0) _delay_ms(_settle_ms());
}

void LIS3DHFull::set_mode(uint8_t mode) {
    if (mode > MODE_HIGH_RES) abort();
    // LPen = 1 with HR = 1 is not allowed — validate the combination first.
    if (_rate_hz == 1344 && mode == MODE_LOW_POWER) abort();
    if ((_rate_hz == 1620 || _rate_hz == 5376) && mode != MODE_LOW_POWER) abort();
    _write_reg_rmw(REG_CTRL_REG1, mode == MODE_LOW_POWER ? 0x08 : 0x00,
                   mode == MODE_LOW_POWER ? 0x00 : 0x08);
    if (mode == MODE_HIGH_RES)
        _write_reg_rmw(REG_CTRL_REG4, 0x08, 0x00);  // HR = 1
    else
        _write_reg_rmw(REG_CTRL_REG4, 0x00, 0x08);  // HR = 0
    _mode = mode;
    _delay_ms(_settle_ms());
}

void LIS3DHFull::set_axes(bool x, bool y, bool z) {
    uint8_t axes = (uint8_t)((x ? 0x01 : 0) | (y ? 0x02 : 0) | (z ? 0x04 : 0));
    _write_reg_field(REG_CTRL_REG1, axes, 0, 0x07);
}

void LIS3DHFull::power_down() {
    _write_reg_field(REG_CTRL_REG1, 0x00, 4, 0x0F);
    _rate_hz = 0;
}

void LIS3DHFull::read_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _read_burst(REG_OUT_X_L, raw, 6);
    x = _decode_axis(raw[0], raw[1]);
    y = _decode_axis(raw[2], raw[3]);
    z = _decode_axis(raw[4], raw[5]);
}

bool LIS3DHFull::new_data_available() {
    return (_read_reg(REG_STATUS) & 0x08) != 0;
}

uint8_t LIS3DHFull::read_status() {
    return _read_reg(REG_STATUS);
}

int16_t LIS3DHFull::read_temperature_delta() {
    // Enable ADC_EN + TEMP_EN on first use; wait one ODR period for the
    // first conversion.
    uint8_t cfg = _read_reg(REG_TEMP_CFG);
    if ((cfg & 0xC0) != 0xC0) {
        _write_reg(REG_TEMP_CFG, (uint8_t)(cfg | 0xC0));
        if (_rate_hz > 0) _delay_ms((uint32_t)lroundf(1000.0f / (float)_rate_hz));
    }
    uint8_t raw = _read_reg(REG_OUT_ADC3_H);
    return (int16_t)(int8_t)raw;
}

float LIS3DHFull::read_adc(uint8_t channel) {
    if (channel < 1 || channel > 3) abort();
    if (channel == 3 && (_read_reg(REG_TEMP_CFG) & 0x40)) abort();  // TEMP_EN = 1
    uint8_t base = (uint8_t)(REG_OUT_ADC1_L + (channel - 1) * 2);
    uint8_t raw[2];
    _read_burst(base, raw, 2);
    // 10-bit in normal / high-resolution mode, 8-bit in low-power mode.
    uint8_t shift = _mode == MODE_LOW_POWER ? 8 : 6;
    uint32_t raw16 = (uint32_t)raw[0] | ((uint32_t)raw[1] << 8);
    int32_t counts = toSigned(raw16, 16) >> shift;
    // Derived mapping: 0 ↔ 1.200 V and ±512 counts ↔ ±400 mV.
    return 1.200f + (float)counts * (0.400f / 512.0f);
}

void LIS3DHFull::enable_adc(bool enabled) {
    _write_reg_rmw(REG_TEMP_CFG, enabled ? 0x80 : 0x00, enabled ? 0x00 : 0x80);
}

void LIS3DHFull::set_high_pass(uint8_t mode, uint8_t cutoff, bool filter_output,
                               bool on_ia1, bool on_ia2, bool on_click) {
    if (mode > 3 || cutoff > 3) abort();
    uint8_t value = (uint8_t)((mode << 6) | (cutoff << 4));
    if (filter_output) value |= 0x08;  // FDS
    if (on_click)      value |= 0x04;  // HPCLICK
    if (on_ia2)        value |= 0x02;  // HP_IA2
    if (on_ia1)        value |= 0x01;  // HP_IA1
    _write_reg(REG_CTRL_REG2, value);
}

uint8_t LIS3DHFull::reset_high_pass() {
    return _read_reg(REG_REFERENCE);
}

void LIS3DHFull::set_reference(uint8_t value) {
    _write_reg(REG_REFERENCE, value);
}

void LIS3DHFull::set_fifo(uint8_t mode, uint8_t watermark, bool trigger_int2) {
    if (mode > 3 || watermark > 31) abort();
    uint8_t value = (uint8_t)((mode << 6) | (trigger_int2 ? 0x20 : 0) | watermark);
    _write_reg(REG_FIFO_CTRL, value);
    _write_reg_rmw(REG_CTRL_REG5, mode == 0 ? 0x00 : 0x40, mode == 0 ? 0x40 : 0x00);
}

uint8_t LIS3DHFull::fifo_level() {
    return (uint8_t)(_read_reg(REG_FIFO_SRC) & 0x1F);
}

uint8_t LIS3DHFull::read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples) {
    if (!x_buf || !y_buf || !z_buf || max_samples == 0) return 0;
    uint8_t level = fifo_level();
    uint8_t n = level < max_samples ? level : max_samples;
    float scale = _sensitivity_g();
    for (uint8_t i = 0; i < n; i++) {
        uint8_t raw[6];
        _read_burst(REG_OUT_X_L, raw, 6);
        x_buf[i] = (float)_decode_axis(raw[0], raw[1]) * scale;
        y_buf[i] = (float)_decode_axis(raw[2], raw[3]) * scale;
        z_buf[i] = (float)_decode_axis(raw[4], raw[5]) * scale;
    }
    return n;
}

void LIS3DHFull::read_fifo_status(bool& watermark, bool& overrun, bool& empty, uint8_t& level) {
    uint8_t raw = _read_reg(REG_FIFO_SRC);
    watermark = (raw & 0x80) != 0;
    overrun   = (raw & 0x40) != 0;
    empty     = (raw & 0x20) != 0;
    level     = (uint8_t)(raw & 0x1F);
}

uint8_t LIS3DHFull::_threshold_code(float threshold_g) {
    uint8_t range_index = _range_g == 4 ? 1 : (_range_g == 8 ? 2 : (_range_g == 16 ? 3 : 0));
    int code = (int)lroundf(threshold_g * 1000.0f / (float)kThresholdLsbMg[range_index]);
    if (code < 0) code = 0;
    if (code > 127) code = 127;
    return (uint8_t)code;
}

uint8_t LIS3DHFull::_duration_code(uint16_t duration_ms, uint8_t limit) {
    // 1 LSb = 1/ODR; while powered down there is no ODR to convert with,
    // so the code is 0.
    if (_rate_hz == 0) return 0;
    int code = (int)lroundf((float)duration_ms * (float)_rate_hz / 1000.0f);
    if (code < 0) code = 0;
    if (code > limit) code = limit;
    return (uint8_t)code;
}

void LIS3DHFull::set_motion_interrupt(uint8_t generator, uint8_t events, float threshold_g,
                                      uint16_t duration_ms, bool and_mode) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    if (events > 0x3F) abort();
    uint8_t cfg = (uint8_t)((and_mode ? 0x80 : 0x00) | events);
    _write_reg(_generator_cfg_reg(generator), cfg);
    _write_reg(_generator_ths_reg(generator), _threshold_code(threshold_g));
    _write_reg(_generator_dur_reg(generator), _duration_code(duration_ms, 127));
}

void LIS3DHFull::set_free_fall(uint8_t generator, float threshold_g, uint16_t duration_ms) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    // All three axes low, AND mode: INTx_CFG = AOI + XLIE/YLIE/ZLIE.
    _write_reg(_generator_cfg_reg(generator), 0x95);
    _write_reg(_generator_ths_reg(generator), _threshold_code(threshold_g));
    _write_reg(_generator_dur_reg(generator), _duration_code(duration_ms, 127));
}

void LIS3DHFull::set_orientation(uint8_t generator, bool position, bool four_d) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    uint8_t axis_zones = four_d ? 0x0F : 0x3F;
    uint8_t cfg = (uint8_t)((position ? 0xC0 : 0x40) | axis_zones);
    _write_reg(_generator_cfg_reg(generator), cfg);
    uint8_t d4d_bit = generator == GENERATOR_2 ? 0x01 : 0x04;
    _write_reg_rmw(REG_CTRL_REG5, four_d ? d4d_bit : 0x00, four_d ? 0x00 : d4d_bit);
}

void LIS3DHFull::set_latch(uint8_t generator, bool enabled) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    uint8_t bit = generator == GENERATOR_2 ? 0x02 : 0x08;
    _write_reg_rmw(REG_CTRL_REG5, enabled ? bit : 0x00, enabled ? 0x00 : bit);
}

uint8_t LIS3DHFull::read_interrupt_source(uint8_t generator) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    return _read_reg(_generator_src_reg(generator));
}

void LIS3DHFull::set_click(uint8_t single, uint8_t double_, float threshold_g,
                           uint16_t time_limit_ms, uint16_t latency_ms,
                           uint16_t window_ms, bool latch) {
    if (single > 7 || double_ > 7) abort();
    // Axis masks (X=1, Y=2, Z=4) map onto the per-axis XS/YS/ZS and
    // XD/YD/ZD enable bits of CLICK_CFG.
    uint8_t cfg = (uint8_t)((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
                            ((double_ & 0x01) << 1) | ((double_ & 0x02) << 2) | ((double_ & 0x04) << 3));
    uint8_t ths = (uint8_t)(_threshold_code(threshold_g) | (latch ? 0x80 : 0x00));
    _write_reg(REG_CLICK_CFG, cfg);
    _write_reg(REG_CLICK_THS, ths);
    _write_reg(REG_TIME_LIMIT, _duration_code(time_limit_ms, 127));
    _write_reg(REG_TIME_LATENCY, _duration_code(latency_ms, 255));
    _write_reg(REG_TIME_WINDOW, _duration_code(window_ms, 255));
}

uint8_t LIS3DHFull::read_click_source() {
    return _read_reg(REG_CLICK_SRC);
}

void LIS3DHFull::set_activity(float threshold_g, uint16_t duration_ms) {
    uint8_t code = _threshold_code(threshold_g);
    if (code == 0) abort();  // ACT_THS must be non-zero.
    // ACT_DUR: 1 LSb = (8·ACT_DUR + 1)/ODR, inverted and rounded.
    int dur = 0;
    if (_rate_hz > 0) dur = (int)lroundf(((float)duration_ms * (float)_rate_hz / 1000.0f - 1.0f) / 8.0f);
    if (dur < 0) dur = 0;
    if (dur > 255) dur = 255;
    _write_reg(REG_ACT_THS, code);
    _write_reg(REG_ACT_DUR, (uint8_t)dur);
}

void LIS3DHFull::set_interrupt_polarity(bool active_low) {
    _write_reg_rmw(REG_CTRL_REG6, active_low ? 0x02 : 0x00, active_low ? 0x00 : 0x02);
}

uint8_t LIS3DHFull::_source_bit(uint16_t source) {
    uint8_t bit = 0;
    while (source > 1) {
        source >>= 1;
        bit++;
    }
    return bit;
}

void LIS3DHFull::_route(uint16_t source, uint8_t pin, uint8_t& reg, uint8_t& mask, uint8_t& track) {
    if (pin != 1 && pin != 2) abort();
    // INT1-only: data-ready, aux data-ready, watermark, overrun.
    // INT2-only: activity, boot. Both: IA1, IA2, click.
    switch (source) {
        case SOURCE_DATA_READY:     reg = REG_CTRL_REG3; mask = 0x10; track = 1; return;
        case SOURCE_AUX_DATA_READY: reg = REG_CTRL_REG3; mask = 0x08; track = 1; return;
        case SOURCE_WATERMARK:      reg = REG_CTRL_REG3; mask = 0x04; track = 1; return;
        case SOURCE_OVERRUN:        reg = REG_CTRL_REG3; mask = 0x02; track = 1; return;
        case SOURCE_IA1:            reg = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3; mask = 0x40; track = (uint8_t)pin; return;
        case SOURCE_IA2:            reg = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3; mask = 0x20; track = (uint8_t)pin; return;
        case SOURCE_CLICK:          reg = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3; mask = 0x80; track = (uint8_t)pin; return;
        case SOURCE_ACTIVITY:       reg = REG_CTRL_REG6; mask = 0x08; track = 2; return;
        case SOURCE_BOOT:           reg = REG_CTRL_REG6; mask = 0x10; track = 2; return;
        default: abort(); return;
    }
}

void LIS3DHFull::enable_interrupt(uint16_t source, uint8_t pin) {
    uint8_t reg, mask, track;
    _route(source, pin, reg, mask, track);
    _write_reg_rmw(reg, mask, 0x00);
    if (track == 1) _enabled1 |= (uint8_t)(1u << _source_bit(source));
    else            _enabled2 |= (uint8_t)(1u << _source_bit(source));
}

void LIS3DHFull::disable_interrupt(uint16_t source, uint8_t pin) {
    uint8_t reg, mask, track;
    _route(source, pin, reg, mask, track);
    _write_reg_rmw(reg, 0x00, mask);
    if (track == 1) _enabled1 &= (uint8_t)~(uint8_t)(1u << _source_bit(source));
    else            _enabled2 &= (uint8_t)~(uint8_t)(1u << _source_bit(source));
}

uint16_t LIS3DHFull::poll_interrupt() {
    uint8_t e1 = _enabled1, e2 = _enabled2;
    uint8_t enabled = (uint8_t)(e1 | e2);
    uint16_t status = 0;
    // INT1-only sources are routed through CTRL_REG3 (e1).
    if ((e1 & 0x01) && (_read_reg(REG_STATUS) & 0x08))
        status |= SOURCE_DATA_READY;
    if ((e1 & 0x02) && (_read_reg(REG_STATUS_AUX) & 0x08))
        status |= SOURCE_AUX_DATA_READY;
    if ((e1 & 0x0C) != 0) {
        uint8_t fifo = _read_reg(REG_FIFO_SRC);
        if ((e1 & 0x04) && (fifo & 0x80)) status |= SOURCE_WATERMARK;
        if ((e1 & 0x08) && (fifo & 0x40)) status |= SOURCE_OVERRUN;
    }
    // Generator flags live in their own source register no matter which
    // pin the generator is routed to.
    if ((enabled & 0x10) && (_read_reg(REG_INT1_SRC) & 0x40))
        status |= SOURCE_IA1;
    if ((enabled & 0x20) && (_read_reg(REG_INT2_SRC) & 0x40))
        status |= SOURCE_IA2;
    if ((enabled & 0x40) && (_read_reg(REG_CLICK_SRC) & 0x40))
        status |= SOURCE_CLICK;
    return status;
}

void LIS3DHFull::on_interrupt(void (*callback)(uint16_t status), uint8_t pin) {
    if (pin == 1) {
        _callback1 = callback;
        _intPin1 = _connection.intPin();
        _activeInstance = this;
        if (_intPin1) _intPin1->onEdge(&_edge_trampoline1, InputPin::kFalling);
    } else if (pin == 2) {
        _callback2 = callback;
        _intPin2 = _connection.intPin();
        _activeInstance = this;
        if (_intPin2) _intPin2->onEdge(&_edge_trampoline2, InputPin::kFalling);
    }
}

void LIS3DHFull::off_interrupt(uint8_t pin) {
    if (pin == 1) {
        if (_intPin1) _intPin1->offEdge(&_edge_trampoline1);
        _intPin1 = nullptr;
        _callback1 = nullptr;
    } else if (pin == 2) {
        if (_intPin2) _intPin2->offEdge(&_edge_trampoline2);
        _intPin2 = nullptr;
        _callback2 = nullptr;
    }
}

void LIS3DHFull::_edge_trampoline1() {
    if (_activeInstance) _activeInstance->_handle_edge(1);
}

void LIS3DHFull::_edge_trampoline2() {
    if (_activeInstance) _activeInstance->_handle_edge(2);
}

void LIS3DHFull::_handle_edge(uint8_t pin) {
    uint16_t status = poll_interrupt();
    if (!status) return;
    if (pin == 2 && _callback2) _callback2(status);
    else if (_callback1)        _callback1(status);
}

void LIS3DHFull::reboot() {
    _write_reg_rmw(REG_CTRL_REG5, 0x80, 0x00);  // BOOT = 1
    _delay_ms(5);
    // The datasheet does not say whether the control registers survive,
    // so the Initialization Sequence is re-run afterwards.
    _range_g = 2;
    _mode = MODE_HIGH_RES;
    _rate_hz = 100;
    _enabled1 = 0;
    _enabled2 = 0;
    _write_reg(REG_CTRL_REG2, 0x00);
    _write_reg(REG_CTRL_REG3, 0x00);
    _write_reg(REG_CTRL_REG5, 0x00);
    _write_reg(REG_CTRL_REG6, 0x00);
    _write_reg(REG_FIFO_CTRL, 0x00);
    _write_reg(REG_TEMP_CFG, 0x00);
    _write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT);
    _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
    _delay_ms(_settle_ms());
}

void LIS3DHFull::set_sdo_pullup(bool connected) {
    // CTRL_REG0 is not a normal register: bit 4 stays 1, bits 6:5 and
    // 3:0 stay 0; only SDO_PU_DISC (bit 7) is touched.
    _write_reg(REG_CTRL_REG0, connected ? 0x10 : 0x90);
}

// Average `samples` raw readings per axis, spacing them `sample_ms` apart.
void LIS3DHFull::_self_test_average(LIS3DHFull& chip, int16_t* out, uint8_t samples, uint32_t sample_ms) {
    int32_t x = 0, y = 0, z = 0;
    int16_t rx, ry, rz;
    for (uint8_t i = 0; i < samples; i++) {
        chip.read_raw(rx, ry, rz);
        x += rx;
        y += ry;
        z += rz;
        chip._delay_ms(sample_ms);
    }
    out[0] = (int16_t)(x / samples);
    out[1] = (int16_t)(y / samples);
    out[2] = (int16_t)(z / samples);
}

bool LIS3DHFull::self_test(uint8_t mode) {
    if (mode != 1 && mode != 2) abort();
    uint8_t saved_ctrl1 = _read_reg(REG_CTRL_REG1);
    uint8_t saved_ctrl4 = _read_reg(REG_CTRL_REG4);
    uint8_t saved_range = _range_g;
    uint8_t saved_mode = _mode;
    uint16_t saved_rate = _rate_hz;

    int16_t before[3], after[3];
    // ±2 g, normal (10-bit) mode, 50 Hz, BDU on. ST bits: 01 = self-test 0,
    // 10 = self-test 1.
    _write_reg(REG_CTRL_REG4, 0x80);             // BDU=1, FS=00 (±2 g), ST=00
    _write_reg(REG_CTRL_REG1, 0x47);             // ODR=0100 (50 Hz), LPen=0, X/Y/Z
    _range_g = 2;
    _mode = MODE_NORMAL;
    _rate_hz = 50;
    _delay_ms(20);  // turn-on (1.6 ms) plus settle
    _self_test_average(*this, before, 5, 20);

    _write_reg(REG_CTRL_REG4, (uint8_t)(0x80 | (mode == 1 ? 0x02 : 0x04)));
    _delay_ms(40);  // 2 samples in normal mode
    _self_test_average(*this, after, 5, 20);

    // Restore the previous CTRL_REG1 / CTRL_REG4 configuration (ST back to 00).
    _write_reg(REG_CTRL_REG1, saved_ctrl1);
    _write_reg(REG_CTRL_REG4, saved_ctrl4);
    _range_g = saved_range;
    _mode = saved_mode;
    _rate_hz = saved_rate;

    // Every axis must show a 17-360 LSb change (1 LSb = 4 mg at 10-bit / ±2 g).
    bool ok = true;
    for (uint8_t i = 0; i < 3; i++) {
        int16_t delta = (int16_t)(after[i] - before[i]);
        if (delta < 0) delta = -delta;
        if (delta < 17 || delta > 360) ok = false;
    }
    return ok;
}
