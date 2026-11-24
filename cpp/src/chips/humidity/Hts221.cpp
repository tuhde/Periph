#include "Hts221.h"
#include <math.h>
#include <string.h>

#ifdef __linux__
#include <unistd.h>
static void hts221_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void hts221_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void hts221_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void hts221_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void hts221_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void hts221_delay_ms(unsigned ms) { delay(ms); }
#endif

namespace {
const uint8_t CTRL_REG1_DEFAULT = 0x84;   // PD=1, BDU=1, ODR=00 (one-shot)
const unsigned TIMEOUT_MS = 100;

// Index of value in the power-of-two series min, 2*min, ... max; -1 when not part of it.
int log2_index(uint16_t value, uint16_t min, uint16_t max) {
    if (value < min || value > max || (value & (value - 1)) != 0) return -1;
    int idx = 0;
    for (uint16_t v = min; v < value; v = (uint16_t)(v << 1)) idx++;
    return idx;
}

int16_t s16(uint8_t lo, uint8_t hi) {
    return (int16_t)(uint16_t)(((uint16_t)hi << 8) | lo);
}
}  // namespace

// --- Minimal ---

HTS221Minimal::HTS221Minimal(RegisterConnection& connection)
    : _connection(connection) {
    memset(&_cal, 0, sizeof(_cal));
}

bool HTS221Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write((uint32_t)reg, &value, 1);
    return _connection.ok();
}

bool HTS221Minimal::_read_reg(uint8_t reg, uint8_t& value) {
    _connection.read((uint32_t)reg, &value, 1);
    return _connection.ok();
}

bool HTS221Minimal::_read_burst(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.read((uint32_t)(reg | AUTO_INC), buf, len);
    return _connection.ok();
}

bool HTS221Minimal::_load_calibration() {
    uint8_t c[16];
    if (!_read_burst(REG_CALIB, c, sizeof(c))) return false;
    uint16_t t0 = (uint16_t)(((c[5] & 0x03) << 8) | c[2]);
    uint16_t t1 = (uint16_t)(((c[5] & 0x0C) << 6) | c[3]);
    _cal.h0_rh = c[0] / 2.0f;
    _cal.h1_rh = c[1] / 2.0f;
    _cal.t0_degc = t0 / 8.0f;
    _cal.t1_degc = t1 / 8.0f;
    _cal.h0_t0_out = s16(c[6], c[7]);
    _cal.h1_t0_out = s16(c[10], c[11]);
    _cal.t0_out = s16(c[12], c[13]);
    _cal.t1_out = s16(c[14], c[15]);
    return true;
}

bool HTS221Minimal::init() {
    if (!_load_calibration()) return false;
    return _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
}

float HTS221Minimal::_humidity_from_raw(int16_t raw) const {
    float span = (float)_cal.h1_t0_out - (float)_cal.h0_t0_out;
    if (span == 0.0f) return 0.0f;
    float v = ((float)raw - (float)_cal.h0_t0_out) * (_cal.h1_rh - _cal.h0_rh) / span + _cal.h0_rh;
    if (v < 0.0f) return 0.0f;
    if (v > 100.0f) return 100.0f;
    return v;
}

float HTS221Minimal::_temperature_from_raw(int16_t raw) const {
    float span = (float)_cal.t1_out - (float)_cal.t0_out;
    if (span == 0.0f) return 0.0f;
    return ((float)raw - (float)_cal.t0_out) * (_cal.t1_degc - _cal.t0_degc) / span + _cal.t0_degc;
}

bool HTS221Minimal::_read_raw_pair(int16_t& raw_h, int16_t& raw_t) {
    uint8_t d[4];
    if (!_read_burst(REG_HUMIDITY_OUT, d, sizeof(d))) return false;
    raw_h = s16(d[0], d[1]);
    raw_t = s16(d[2], d[3]);
    return true;
}

bool HTS221Minimal::_one_shot_raw(int16_t& raw_h, int16_t& raw_t) {
    uint8_t ctrl2;
    if (!_read_reg(REG_CTRL_REG2, ctrl2)) return false;
    if (!_write_reg(REG_CTRL_REG2, (uint8_t)(ctrl2 | CTRL2_ONE_SHOT))) return false;
    for (unsigned i = 0; i < TIMEOUT_MS; i++) {
        uint8_t status;
        if (!_read_reg(REG_STATUS, status)) return false;
        if ((status & STATUS_BOTH) == STATUS_BOTH) return _read_raw_pair(raw_h, raw_t);
        hts221_delay_ms(1);
    }
    return false;
}

bool HTS221Minimal::read(float& temperature_c, float& humidity_pct) {
    int16_t h, t;
    if (!_one_shot_raw(h, t)) return false;
    temperature_c = _temperature_from_raw(t);
    humidity_pct = _humidity_from_raw(h);
    return true;
}

// --- Full ---

HTS221Full* HTS221Full::_active_instance = nullptr;

HTS221Full::HTS221Full(RegisterConnection& connection)
    : HTS221Minimal(connection) {}

float HTS221Full::read_humidity() {
    int16_t h, t;
    if (!_one_shot_raw(h, t)) return NAN;
    return _humidity_from_raw(h);
}

float HTS221Full::read_temperature() {
    int16_t h, t;
    if (!_one_shot_raw(h, t)) return NAN;
    return _temperature_from_raw(t);
}

bool HTS221Full::read_raw_humidity(int16_t& raw) {
    int16_t h, t;
    if (!_one_shot_raw(h, t)) return false;
    raw = h;
    return true;
}

bool HTS221Full::read_raw_temperature(int16_t& raw) {
    int16_t h, t;
    if (!_one_shot_raw(h, t)) return false;
    raw = t;
    return true;
}

bool HTS221Full::read_continuous(float& temperature_c, float& humidity_pct) {
    int16_t h, t;
    if (!_read_raw_pair(h, t)) return false;
    temperature_c = _temperature_from_raw(t);
    humidity_pct = _humidity_from_raw(h);
    return true;
}

float HTS221Full::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh < 0.01f) rh = 0.01f;
    float g = logf(rh / 100.0f) + 17.62f * t / (243.12f + t);
    return 243.12f * g / (17.62f - g);
}

bool HTS221Full::set_output_data_rate(float hz) {
    uint8_t code;
    if (hz == 0.0f) code = 0;
    else if (hz == 1.0f) code = 1;
    else if (hz == 7.0f) code = 2;
    else if (hz == 12.5f) code = 3;
    else return false;
    uint8_t ctrl1;
    if (!_read_reg(REG_CTRL_REG1, ctrl1)) return false;
    if (!_write_reg(REG_CTRL_REG1, (uint8_t)((ctrl1 & ~CTRL1_ODR_MASK) | code))) return false;
    // Clear flags left over from continuous sampling so the next one-shot wait does not return early.
    int16_t h, t;
    return _read_raw_pair(h, t);
}

bool HTS221Full::get_output_data_rate(float& hz) {
    static const float RATES[4] = {0.0f, 1.0f, 7.0f, 12.5f};
    uint8_t ctrl1;
    if (!_read_reg(REG_CTRL_REG1, ctrl1)) return false;
    hz = RATES[ctrl1 & CTRL1_ODR_MASK];
    return true;
}

bool HTS221Full::set_averaging(uint16_t temperature_samples, uint16_t humidity_samples) {
    int t = log2_index(temperature_samples, 2, 256);
    int h = log2_index(humidity_samples, 4, 512);
    if (t < 0 || h < 0) return false;
    return _write_reg(REG_AV_CONF, (uint8_t)((t << 3) | h));
}

bool HTS221Full::get_averaging(uint16_t& temperature_samples, uint16_t& humidity_samples) {
    uint8_t v;
    if (!_read_reg(REG_AV_CONF, v)) return false;
    temperature_samples = (uint16_t)(2u << ((v >> 3) & 0x07));
    humidity_samples = (uint16_t)(4u << (v & 0x07));
    return true;
}

bool HTS221Full::set_block_data_update(bool enabled) {
    uint8_t ctrl1;
    if (!_read_reg(REG_CTRL_REG1, ctrl1)) return false;
    ctrl1 = enabled ? (uint8_t)(ctrl1 | CTRL1_BDU) : (uint8_t)(ctrl1 & ~CTRL1_BDU);
    return _write_reg(REG_CTRL_REG1, ctrl1);
}

bool HTS221Full::power_down() {
    uint8_t ctrl1;
    if (!_read_reg(REG_CTRL_REG1, ctrl1)) return false;
    return _write_reg(REG_CTRL_REG1, (uint8_t)(ctrl1 & ~CTRL1_PD));
}

bool HTS221Full::power_up() {
    uint8_t ctrl1;
    if (!_read_reg(REG_CTRL_REG1, ctrl1)) return false;
    return _write_reg(REG_CTRL_REG1, (uint8_t)(ctrl1 | CTRL1_PD));
}

bool HTS221Full::set_heater(bool on) {
    uint8_t ctrl2;
    if (!_read_reg(REG_CTRL_REG2, ctrl2)) return false;
    ctrl2 = on ? (uint8_t)(ctrl2 | CTRL2_HEATER) : (uint8_t)(ctrl2 & ~CTRL2_HEATER);
    return _write_reg(REG_CTRL_REG2, ctrl2);
}

bool HTS221Full::is_heater_on() {
    uint8_t ctrl2;
    if (!_read_reg(REG_CTRL_REG2, ctrl2)) return false;
    return (ctrl2 & CTRL2_HEATER) != 0;
}

bool HTS221Full::reboot() {
    uint8_t ctrl2;
    if (!_read_reg(REG_CTRL_REG2, ctrl2)) return false;
    if (!_write_reg(REG_CTRL_REG2, (uint8_t)(ctrl2 | CTRL2_BOOT))) return false;
    for (unsigned i = 0; i < TIMEOUT_MS; i++) {
        uint8_t v;
        if (!_read_reg(REG_CTRL_REG2, v)) return false;
        if ((v & CTRL2_BOOT) == 0) return _load_calibration();
        hts221_delay_ms(1);
    }
    return false;
}

uint8_t HTS221Full::who_am_i() {
    uint8_t v;
    return _read_reg(REG_WHO_AM_I, v) ? v : 0;
}

uint8_t HTS221Full::status() {
    uint8_t v;
    return _read_reg(REG_STATUS, v) ? v : 0;
}

bool HTS221Full::data_ready() {
    return (status() & STATUS_BOTH) == STATUS_BOTH;
}

void HTS221Full::read_calibration(HTS221Calibration& out) const {
    out = _cal;
}

bool HTS221Full::configure_drdy(bool enabled, bool active_high, bool open_drain) {
    uint8_t v = 0;
    if (!active_high) v |= 0x80;
    if (open_drain) v |= 0x40;
    if (enabled) v |= 0x04;
    return _write_reg(REG_CTRL_REG3, v);
}

uint8_t HTS221Full::poll_interrupt() {
    return status();
}

void HTS221Full::_edge_trampoline() {
    HTS221Full* self = _active_instance;
    if (self && self->_callback) {
        self->_callback(self->poll_interrupt());
    }
}

bool HTS221Full::on_interrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    InputPin* pin = intPin ? intPin : _connection.intPin();
    if (!pin) return false;
    off_interrupt();
    _callback = callback;
    _int_pin = pin;
    _active_instance = this;
    configure_drdy(true);
    return pin->onEdge(&HTS221Full::_edge_trampoline, InputPin::kRising);
}

void HTS221Full::off_interrupt() {
    if (_int_pin) _int_pin->offEdge(&HTS221Full::_edge_trampoline);
    bool had = _callback != nullptr;
    _int_pin = nullptr;
    _callback = nullptr;
    if (had) configure_drdy(false);
}
