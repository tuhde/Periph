#include "LSM303.h"
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

// Sensitivity in g/LSB by mode and full-scale range.
static const float kSensitivityG[3][4] = {
    {16.0f / 1000.0f, 32.0f / 1000.0f, 64.0f / 1000.0f, 192.0f / 1000.0f},   // low-power
    {4.0f / 1000.0f,  8.0f / 1000.0f,  16.0f / 1000.0f,  48.0f / 1000.0f},   // normal
    {1.0f / 1000.0f,  2.0f / 1000.0f,   4.0f / 1000.0f,  12.0f / 1000.0f},   // high-resolution
};

// Right-shift applied to the left-justified 16-bit accelerometer pair.
static const uint8_t kShift[3] = {8, 6, 4};

// Magnetometer X/Y and Z gain in LSB per gauss for each GN code.
struct MagGain { uint8_t code; float xy; float z; uint16_t range_ut; };
static const MagGain kMagGains[] = {
    {0x20, 1100.0f, 980.0f, 130},
    {0x40, 855.0f,  760.0f, 190},
    {0x60, 670.0f,  600.0f, 250},
    {0x80, 450.0f,  400.0f, 400},
    {0xA0, 400.0f,  355.0f, 470},
    {0xC0, 330.0f,  295.0f, 560},
    {0xE0, 230.0f,  205.0f, 810},
};

static const MagGain* mag_gain_for_code(uint8_t code) {
    for (uint8_t i = 0; i < sizeof(kMagGains) / sizeof(kMagGains[0]); i++)
        if (kMagGains[i].code == code) return &kMagGains[i];
    return &kMagGains[0];
}

static uint8_t mag_code_for_range(uint16_t range_ut) {
    for (uint8_t i = 0; i < sizeof(kMagGains) / sizeof(kMagGains[0]); i++)
        if (kMagGains[i].range_ut == range_ut) return kMagGains[i].code;
    return 0x20;
}

LSM303Full* LSM303Full::_activeInstance = nullptr;
uint8_t    LSM303Full::_activePin = 0;

LSM303Minimal::LSM303Minimal(RegisterConnection& accel_connection,
                             RegisterConnection& mag_connection)
    : _accel(accel_connection), _mag(mag_connection) {
    _run_init_sequence();
}

void LSM303Minimal::_run_init_sequence() {
    // Step 1: boot download time.
    _delay_ms(5);

    // Step 2: magnetometer identity 0x48 0x34 0x33.
    uint8_t identity[3];
    _mag_read_burst(MAG_REG_IRA, identity, 3);
    if (identity[0] != 0x48 || identity[1] != 0x34 || identity[2] != 0x33) abort();

    // Step 3: known accelerometer state (no soft reset).
    _write_reg(REG_CTRL_REG2, 0x00);
    _write_reg(REG_CTRL_REG3, 0x00);
    _write_reg(REG_CTRL_REG5, 0x00);
    _write_reg(REG_CTRL_REG6, 0x00);
    _write_reg(REG_FIFO_CTRL, 0x00);

    // Step 4: BDU, ±2 g, high-resolution.
    _write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT);

    // Step 5: leave power-down.
    _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);

    // Step 6: magnetometer 15 Hz, ±130 µT, continuous.
    _mag_write_reg(MAG_REG_CRA, 0x10);
    _mag_write_reg(MAG_REG_CRB, _mag_gain_code);
    _mag_write_reg(MAG_REG_MR, 0x00);

    // Step 7: wait for the first samples.
    uint32_t settle = _settle_ms();
    uint32_t mag_settle = (uint32_t)lroundf(1000.0f / _mag_rate_hz);
    _delay_ms(settle > mag_settle ? settle : mag_settle);
}

void LSM303Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _accel.write(reg, &value, 1);
}

uint8_t LSM303Minimal::_read_reg(uint8_t reg) {
    uint8_t v = 0;
    _accel.read(reg, &v, 1);
    return v;
}

void LSM303Minimal::_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    _accel.read((uint32_t)(reg | 0x80), buf, len);
}

void LSM303Minimal::_delay_ms(uint32_t ms) {
    delay(ms);
}

int16_t LSM303Minimal::_decode_axis(uint8_t low, uint8_t high) const {
    uint32_t raw16 = (uint32_t)low | ((uint32_t)high << 8);
    int32_t signed16 = toSigned(raw16, 16);
    return (int16_t)(signed16 >> kShift[_mode]);
}

float LSM303Minimal::_sensitivity_g() const {
    return kSensitivityG[_mode][_range_g == 4 ? 1 : (_range_g == 8 ? 2 : (_range_g == 16 ? 3 : 0))];
}

uint32_t LSM303Minimal::_settle_ms() const {
    if (_rate_hz == 0) return 0;
    float period_ms = 1000.0f / (float)_rate_hz;
    float turn_on_ms;
    if (_mode == MODE_LOW_POWER)   turn_on_ms = 1.0f;
    else if (_mode == MODE_NORMAL) turn_on_ms = 1.6f;
    else                           turn_on_ms = 7.0f * period_ms;
    float wait_ms = turn_on_ms > period_ms ? turn_on_ms : period_ms;
    return (uint32_t)lroundf(wait_ms);
}

void LSM303Minimal::_mag_write_reg(uint8_t reg, uint8_t value) {
    _mag.write(reg, &value, 1);
}

uint8_t LSM303Minimal::_mag_read_reg(uint8_t reg) {
    uint8_t v = 0;
    _mag.read(reg, &v, 1);
    return v;
}

void LSM303Minimal::_mag_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    // The magnetometer pointer always auto-increments; no special bit.
    _mag.read(reg, buf, len);
}

float LSM303Minimal::_mag_xy_gain() const {
    return mag_gain_for_code(_mag_gain_code)->xy;
}

float LSM303Minimal::_mag_z_gain() const {
    return mag_gain_for_code(_mag_gain_code)->z;
}

int16_t LSM303Minimal::_decode_mag_axis(uint8_t high, uint8_t low) const {
    uint32_t raw16 = ((uint32_t)high << 8) | (uint32_t)low;
    return (int16_t)toSigned(raw16, 16);
}

void LSM303Minimal::read_acceleration(float& x, float& y, float& z) {
    uint8_t raw[6];
    _read_burst(REG_OUT_X_L, raw, 6);
    float scale = _sensitivity_g();
    x = (float)_decode_axis(raw[0], raw[1]) * scale;
    y = (float)_decode_axis(raw[2], raw[3]) * scale;
    z = (float)_decode_axis(raw[4], raw[5]) * scale;
}

void LSM303Minimal::read_magnetic(float& x, float& y, float& z) {
    // Wire order is X, Z, Y; each value is high byte first.
    uint8_t raw[6];
    _mag_read_burst(MAG_REG_OUT_X_H, raw, 6);
    x = (float)_decode_mag_axis(raw[0], raw[1]) / _mag_xy_gain() * 100.0f;
    z = (float)_decode_mag_axis(raw[2], raw[3]) / _mag_z_gain() * 100.0f;
    y = (float)_decode_mag_axis(raw[4], raw[5]) / _mag_xy_gain() * 100.0f;
}

// LSM303Full

LSM303Full::LSM303Full(RegisterConnection& accel_connection,
                       RegisterConnection& mag_connection)
    : LSM303Minimal(accel_connection, mag_connection) {}

void LSM303Full::_write_reg_rmw(uint8_t reg, uint8_t set_mask, uint8_t clear_mask) {
    uint8_t value = _read_reg(reg);
    value = (uint8_t)((value | set_mask) & (uint8_t)~clear_mask);
    _write_reg(reg, value);
}

void LSM303Full::_write_reg_field(uint8_t reg, uint8_t value, uint8_t shift, uint8_t mask) {
    uint8_t old = _read_reg(reg);
    uint8_t combined = (uint8_t)((old & (uint8_t)~(uint8_t)(mask << shift)) | ((value & mask) << shift));
    _write_reg(reg, combined);
}

uint8_t LSM303Full::_generator_cfg_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_CFG : REG_INT1_CFG;
}

uint8_t LSM303Full::_generator_src_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_SRC : REG_INT1_SRC;
}

uint8_t LSM303Full::_generator_ths_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_THS : REG_INT1_THS;
}

uint8_t LSM303Full::_generator_dur_reg(uint8_t generator) {
    return generator == GENERATOR_2 ? REG_INT2_DUR : REG_INT1_DUR;
}

void LSM303Full::set_range(uint8_t range_g) {
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

void LSM303Full::set_data_rate(uint16_t rate_hz) {
    uint8_t code;
    switch (rate_hz) {
        case 0:    code = 0x00; break;
        case 1:    code = 0x01; break;
        case 10:   code = 0x02; break;
        case 25:   code = 0x03; break;
        case 50:   code = 0x04; break;
        case 100:  code = 0x05; break;
        case 200:  code = 0x06; break;
        case 400:  code = 0x07; break;
        case 1620: code = 0x08; break;
        case 1344: code = 0x09; break;
        case 5376: code = 0x09; break;
        default:   abort(); break;
    }
    if ((rate_hz == 1620 || rate_hz == 5376) && _mode != MODE_LOW_POWER) abort();
    if (rate_hz == 1344 && _mode == MODE_LOW_POWER) abort();
    _write_reg_field(REG_CTRL_REG1, code, 4, 0x0F);
    _rate_hz = rate_hz;
    if (rate_hz > 0) _delay_ms(_settle_ms());
}

void LSM303Full::set_mode(uint8_t mode) {
    if (mode > MODE_HIGH_RES) abort();
    if (_rate_hz == 1344 && mode == MODE_LOW_POWER) abort();
    if ((_rate_hz == 1620 || _rate_hz == 5376) && mode != MODE_LOW_POWER) abort();
    _write_reg_rmw(REG_CTRL_REG1, mode == MODE_LOW_POWER ? 0x08 : 0x00,
                   mode == MODE_LOW_POWER ? 0x00 : 0x08);
    if (mode == MODE_HIGH_RES)
        _write_reg_rmw(REG_CTRL_REG4, 0x08, 0x00);
    else
        _write_reg_rmw(REG_CTRL_REG4, 0x00, 0x08);
    _mode = mode;
    _delay_ms(_settle_ms());
}

void LSM303Full::set_axes(bool x, bool y, bool z) {
    uint8_t axes = (uint8_t)((x ? 0x01 : 0) | (y ? 0x02 : 0) | (z ? 0x04 : 0));
    _write_reg_field(REG_CTRL_REG1, axes, 0, 0x07);
}

void LSM303Full::power_down() {
    _write_reg_field(REG_CTRL_REG1, 0x00, 4, 0x0F);
    _rate_hz = 0;
}

void LSM303Full::read_acceleration_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _read_burst(REG_OUT_X_L, raw, 6);
    x = _decode_axis(raw[0], raw[1]);
    y = _decode_axis(raw[2], raw[3]);
    z = _decode_axis(raw[4], raw[5]);
}

bool LSM303Full::new_data_available() {
    return (_read_reg(REG_STATUS) & 0x08) != 0;
}

uint8_t LSM303Full::read_status() {
    return _read_reg(REG_STATUS);
}

void LSM303Full::set_high_pass(uint8_t mode, uint8_t cutoff, bool filter_output,
                               bool on_ia1, bool on_ia2, bool on_click) {
    if (mode > 3 || cutoff > 3) abort();
    uint8_t value = (uint8_t)((mode << 6) | (cutoff << 4));
    if (filter_output) value |= 0x08;
    if (on_click)      value |= 0x04;
    if (on_ia2)        value |= 0x02;
    if (on_ia1)        value |= 0x01;
    _write_reg(REG_CTRL_REG2, value);
}

uint8_t LSM303Full::reset_high_pass() {
    return _read_reg(REG_REFERENCE);
}

void LSM303Full::set_reference(uint8_t value) {
    _write_reg(REG_REFERENCE, value);
}

void LSM303Full::set_fifo(uint8_t mode, uint8_t watermark, bool trigger_int2) {
    if (mode > 3 || watermark > 31) abort();
    uint8_t value = (uint8_t)((mode << 6) | (trigger_int2 ? 0x20 : 0) | watermark);
    _write_reg(REG_FIFO_CTRL, value);
    _write_reg_rmw(REG_CTRL_REG5, mode == 0 ? 0x00 : 0x40, mode == 0 ? 0x40 : 0x00);
}

uint8_t LSM303Full::fifo_level() {
    return (uint8_t)(_read_reg(REG_FIFO_SRC) & 0x1F);
}

uint8_t LSM303Full::read_fifo(float* x_buf, float* y_buf, float* z_buf, uint8_t max_samples) {
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

void LSM303Full::read_fifo_status(bool& watermark, bool& overrun, bool& empty, uint8_t& level) {
    uint8_t raw = _read_reg(REG_FIFO_SRC);
    watermark = (raw & 0x80) != 0;
    overrun   = (raw & 0x40) != 0;
    empty     = (raw & 0x20) != 0;
    level     = (uint8_t)(raw & 0x1F);
}

uint8_t LSM303Full::_threshold_code(float threshold_g) {
    float lsb_g = (float)_range_g / 128.0f;
    int code = (int)lroundf(threshold_g / lsb_g);
    if (code < 0) code = 0;
    if (code > 127) code = 127;
    return (uint8_t)code;
}

uint8_t LSM303Full::_duration_code(uint16_t duration_ms, uint8_t limit) {
    if (_rate_hz == 0) return 0;
    int code = (int)lroundf((float)duration_ms * (float)_rate_hz / 1000.0f);
    if (code < 0) code = 0;
    if (code > limit) code = limit;
    return (uint8_t)code;
}

void LSM303Full::set_motion_interrupt(uint8_t generator, uint8_t events, float threshold_g,
                                      uint16_t duration_ms, bool and_mode) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    if (events > 0x3F) abort();
    uint8_t cfg = (uint8_t)((and_mode ? 0x80 : 0x00) | events);
    _write_reg(_generator_cfg_reg(generator), cfg);
    _write_reg(_generator_ths_reg(generator), _threshold_code(threshold_g));
    _write_reg(_generator_dur_reg(generator), _duration_code(duration_ms, 127));
}

void LSM303Full::set_free_fall(uint8_t generator, float threshold_g, uint16_t duration_ms) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    _write_reg(_generator_cfg_reg(generator), 0x95);
    _write_reg(_generator_ths_reg(generator), _threshold_code(threshold_g));
    _write_reg(_generator_dur_reg(generator), _duration_code(duration_ms, 127));
}

void LSM303Full::set_orientation(uint8_t generator, bool position, bool four_d) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    uint8_t axis_zones = four_d ? 0x0F : 0x3F;
    uint8_t cfg = (uint8_t)((position ? 0xC0 : 0x40) | axis_zones);
    _write_reg(_generator_cfg_reg(generator), cfg);
    uint8_t d4d_bit = generator == GENERATOR_2 ? 0x01 : 0x04;
    _write_reg_rmw(REG_CTRL_REG5, four_d ? d4d_bit : 0x00, four_d ? 0x00 : d4d_bit);
}

void LSM303Full::set_latch(uint8_t generator, bool enabled) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    uint8_t bit = generator == GENERATOR_2 ? 0x02 : 0x08;
    _write_reg_rmw(REG_CTRL_REG5, enabled ? bit : 0x00, enabled ? 0x00 : bit);
}

uint8_t LSM303Full::read_interrupt_source(uint8_t generator) {
    if (generator != GENERATOR_1 && generator != GENERATOR_2) abort();
    return _read_reg(_generator_src_reg(generator));
}

void LSM303Full::set_click(uint8_t single, uint8_t double_, float threshold_g,
                           uint16_t time_limit_ms, uint16_t latency_ms,
                           uint16_t window_ms) {
    if (single > 7 || double_ > 7) abort();
    uint8_t cfg = (uint8_t)((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
                            ((double_ & 0x01) << 1) | ((double_ & 0x02) << 2) | ((double_ & 0x04) << 3));
    _write_reg(REG_CLICK_CFG, cfg);
    _write_reg(REG_CLICK_THS, _threshold_code(threshold_g));
    _write_reg(REG_TIME_LIMIT, _duration_code(time_limit_ms, 127));
    _write_reg(REG_TIME_LATENCY, _duration_code(latency_ms, 255));
    _write_reg(REG_TIME_WINDOW, _duration_code(window_ms, 255));
}

uint8_t LSM303Full::read_click_source() {
    return _read_reg(REG_CLICK_SRC);
}

void LSM303Full::set_interrupt_polarity(bool active_low) {
    _write_reg_rmw(REG_CTRL_REG6, active_low ? 0x02 : 0x00, active_low ? 0x00 : 0x02);
}

uint8_t LSM303Full::_source_bit(uint16_t source) {
    uint8_t bit = 0;
    while (source > 1) {
        source >>= 1;
        bit++;
    }
    return bit;
}

void LSM303Full::_route(uint16_t source, uint8_t pin, uint8_t& reg, uint8_t& mask, uint8_t& track) {
    if (pin != 1 && pin != 2) abort();
    switch (source) {
        case SOURCE_DATA_READY: reg = REG_CTRL_REG3; mask = 0x10; track = 1; return;
        case SOURCE_WATERMARK:  reg = REG_CTRL_REG3; mask = 0x04; track = 1; return;
        case SOURCE_OVERRUN:    reg = REG_CTRL_REG3; mask = 0x02; track = 1; return;
        case SOURCE_IA1:        reg = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3; mask = 0x40; track = (uint8_t)pin; return;
        case SOURCE_IA2:        reg = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3; mask = 0x20; track = (uint8_t)pin; return;
        case SOURCE_CLICK:      reg = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3; mask = 0x80; track = (uint8_t)pin; return;
        case SOURCE_BOOT:       reg = REG_CTRL_REG6; mask = 0x10; track = 2; return;
        default: abort(); return;
    }
}

void LSM303Full::enable_interrupt(uint16_t source, uint8_t pin) {
    if (source == SOURCE_MAG_DATA_READY) abort();
    uint8_t reg, mask, track;
    _route(source, pin, reg, mask, track);
    _write_reg_rmw(reg, mask, 0x00);
    if (track == 1) _enabled1 |= (uint8_t)(1u << _source_bit(source));
    else            _enabled2 |= (uint8_t)(1u << _source_bit(source));
}

void LSM303Full::disable_interrupt(uint16_t source, uint8_t pin) {
    if (source == SOURCE_MAG_DATA_READY) abort();
    uint8_t reg, mask, track;
    _route(source, pin, reg, mask, track);
    _write_reg_rmw(reg, 0x00, mask);
    if (track == 1) _enabled1 &= (uint8_t)~(uint8_t)(1u << _source_bit(source));
    else            _enabled2 &= (uint8_t)~(uint8_t)(1u << _source_bit(source));
}

uint16_t LSM303Full::poll_interrupt() {
    uint8_t e1 = _enabled1, e2 = _enabled2;
    uint8_t enabled = (uint8_t)(e1 | e2);
    uint16_t status = 0;
    if ((e1 & 0x01) && (_read_reg(REG_STATUS) & 0x08))
        status |= SOURCE_DATA_READY;
    if ((e1 & 0x06) != 0) {
        uint8_t fifo = _read_reg(REG_FIFO_SRC);
        if ((e1 & 0x02) && (fifo & 0x80)) status |= SOURCE_WATERMARK;
        if ((e1 & 0x04) && (fifo & 0x40)) status |= SOURCE_OVERRUN;
    }
    if ((enabled & 0x08) && (_read_reg(REG_INT1_SRC) & 0x40))
        status |= SOURCE_IA1;
    if ((enabled & 0x10) && (_read_reg(REG_INT2_SRC) & 0x40))
        status |= SOURCE_IA2;
    if ((enabled & 0x20) && (_read_reg(REG_CLICK_SRC) & 0x40))
        status |= SOURCE_CLICK;
    if (_mag_read_reg(MAG_REG_SR) & 0x01)
        status |= SOURCE_MAG_DATA_READY;
    return status;
}

void LSM303Full::on_interrupt(void (*callback)(uint16_t status), uint8_t pin) {
    if (pin == 1) {
        _callback1 = callback;
        _intPin1 = _accel.intPin();
        _activeInstance = this;
        if (_intPin1) _intPin1->onEdge(&_edge_trampoline1, InputPin::kFalling);
    } else if (pin == 2) {
        _callback2 = callback;
        _intPin2 = _accel.intPin();
        _activeInstance = this;
        if (_intPin2) _intPin2->onEdge(&_edge_trampoline2, InputPin::kFalling);
    }
}

void LSM303Full::off_interrupt(uint8_t pin) {
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

void LSM303Full::_edge_trampoline1() {
    if (_activeInstance) _activeInstance->_handle_edge(1);
}

void LSM303Full::_edge_trampoline2() {
    if (_activeInstance) _activeInstance->_handle_edge(2);
}

void LSM303Full::_handle_edge(uint8_t pin) {
    uint16_t status = poll_interrupt();
    if (!status) return;
    if (pin == 2 && _callback2) _callback2(status);
    else if (_callback1)        _callback1(status);
}

void LSM303Full::set_mag_range(uint16_t range_ut) {
    switch (range_ut) {
        case 130: case 190: case 250: case 400:
        case 470: case 560: case 810:
            break;
        default: abort(); break;
    }
    _mag_gain_code = mag_code_for_range(range_ut);
    _mag_write_reg(MAG_REG_CRB, _mag_gain_code);
}

void LSM303Full::set_mag_data_rate(float rate_hz) {
    uint8_t code;
    if (rate_hz == 0.75f)      code = 0x00;
    else if (rate_hz == 1.5f)  code = 0x04;
    else if (rate_hz == 3.0f)  code = 0x08;
    else if (rate_hz == 7.5f)  code = 0x0C;
    else if (rate_hz == 15.0f) code = 0x10;
    else if (rate_hz == 30.0f) code = 0x14;
    else if (rate_hz == 75.0f) code = 0x18;
    else if (rate_hz == 220.0f) code = 0x1C;
    else { abort(); return; }
    _mag_write_reg(MAG_REG_CRA, (uint8_t)((_mag_read_reg(MAG_REG_CRA) & 0xE3) | code));
    _mag_rate_hz = rate_hz;
}

void LSM303Full::set_mag_mode(uint8_t mode) {
    uint8_t code;
    switch (mode) {
        case 0: code = 0x00; break;
        case 1: code = 0x01; break;
        case 2: code = 0x03; break;
        default: abort(); return;
    }
    _mag_write_reg(MAG_REG_MR, code);
    if (mode == 1) _delay_ms((uint32_t)lroundf(1000.0f / _mag_rate_hz));
}

void LSM303Full::read_magnetic_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[6];
    _mag_read_burst(MAG_REG_OUT_X_H, raw, 6);
    x = _decode_mag_axis(raw[0], raw[1]);
    z = _decode_mag_axis(raw[2], raw[3]);
    y = _decode_mag_axis(raw[4], raw[5]);
}

bool LSM303Full::mag_data_ready() {
    return (_mag_read_reg(MAG_REG_SR) & 0x01) != 0;
}

void LSM303Full::read_mag_status(bool& drdy, bool& lock) {
    uint8_t raw = _mag_read_reg(MAG_REG_SR);
    drdy = (raw & 0x01) != 0;
    lock = (raw & 0x02) != 0;
}

void LSM303Full::read_mag_identity(uint8_t out[3]) {
    _mag_read_burst(MAG_REG_IRA, out, 3);
}

void LSM303Full::enable_temperature(bool enabled) {
    uint8_t cra = _mag_read_reg(MAG_REG_CRA);
    if (enabled) cra |= 0x80;
    else         cra &= (uint8_t)~0x80;
    _mag_write_reg(MAG_REG_CRA, cra);
}

float LSM303Full::read_temperature_delta() {
    if (!(_mag_read_reg(MAG_REG_CRA) & 0x80)) {
        enable_temperature(true);
        _delay_ms((uint32_t)lroundf(1000.0f / _mag_rate_hz));
    }
    uint32_t raw16 = ((uint32_t)_mag_read_reg(MAG_REG_TEMP_H) << 8) | _mag_read_reg(MAG_REG_TEMP_L);
    int32_t signed16 = toSigned(raw16, 16);
    return (float)(signed16 >> 4) / 8.0f;
}

float LSM303Full::heading(bool tilt_compensate) {
    float mx, my, mz;
    read_magnetic(mx, my, mz);
    float angle;
    if (tilt_compensate) {
        float ax, ay, az;
        read_acceleration(ax, ay, az);
        float roll = atan2f(ay, az);
        float pitch = atan2f(-ax, ay * sinf(roll) + az * cosf(roll));
        float xh = mx * cosf(pitch) + mz * sinf(pitch);
        float yh = mx * sinf(roll) * sinf(pitch) + my * cosf(roll)
                 - mz * sinf(roll) * cosf(pitch);
        angle = atan2f(yh, xh);
    } else {
        angle = atan2f(my, mx);
    }
    float degrees = angle * 180.0f / 3.14159265358979323846f;
    if (degrees < 0.0f) degrees += 360.0f;
    return degrees;
}

void LSM303Full::reboot() {
    _write_reg_rmw(REG_CTRL_REG5, 0x80, 0x00);
    _delay_ms(5);
    _range_g = 2;
    _mode = MODE_HIGH_RES;
    _rate_hz = 100;
    _mag_gain_code = 0x20;
    _mag_rate_hz = 15.0f;
    _enabled1 = 0;
    _enabled2 = 0;
    _run_init_sequence();
}
