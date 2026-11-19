#include "As7262.h"
#include <math.h>
#include <string.h>

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

namespace {
const uint8_t HW_STATUS = 0x00;
const uint8_t HW_WRITE = 0x01;
const uint8_t HW_READ = 0x02;
const uint8_t TX_VALID = 0x02;
const uint8_t RX_VALID = 0x01;

const uint8_t REG_HW_VERSION = 0x00;
const uint8_t REG_FW_VERSION = 0x02;
const uint8_t REG_CONTROL = 0x04;
const uint8_t REG_INT_T = 0x05;
const uint8_t REG_TEMP = 0x06;
const uint8_t REG_LED = 0x07;
const uint8_t REG_RAW = 0x08;
const uint8_t REG_CAL = 0x14;

const uint8_t CTRL_RST = 0x80;
const uint8_t CTRL_INT = 0x40;
const uint8_t CTRL_DATA_RDY = 0x02;

const uint8_t INT_T_DEFAULT = 0x3B;
const uint8_t GAIN_DEFAULT = 2;
const float STEP_MS = 2.8f;

const float GAINS[4] = {1.0f, 3.7f, 16.0f, 64.0f};
const float DRV_CURRENTS[4] = {12.5f, 25.0f, 50.0f, 100.0f};
const float IND_CURRENTS[4] = {1.0f, 2.0f, 4.0f, 8.0f};

// Nearest option index; -1 if the value is above the largest option.
int nearest(const float* options, float value) {
    if (value > options[3]) return -1;
    int best = 0;
    for (int i = 1; i < 4; i++)
        if (fabsf(options[i] - value) < fabsf(options[best] - value)) best = i;
    return best;
}
}  // namespace

// --- Minimal ---

AS7262Minimal::AS7262Minimal(RegisterConnection& connection)
    : _connection(connection) {}

bool AS7262Minimal::_hw_read(uint8_t reg, uint8_t& value) {
    _connection.read((uint32_t)reg, &value, 1);
    return _connection.ok();
}

bool AS7262Minimal::_wait_status(uint8_t mask, uint8_t want) {
    // Poll quickly first, then once per millisecond for up to about 1 s.
    for (int i = 0; i < 1000 + 20; i++) {
        uint8_t s;
        if (!_hw_read(HW_STATUS, s)) return false;
        if ((s & mask) == want) return true;
        if (i >= 20) periph_delay_ms(1);
    }
    return false;
}

bool AS7262Minimal::read_virtual(uint8_t reg, uint8_t& value) {
    if (!_wait_status(TX_VALID, 0)) return false;
    uint8_t s;
    if (!_hw_read(HW_STATUS, s)) return false;
    if (s & RX_VALID) {  // drain a stale byte
        uint8_t stale;
        if (!_hw_read(HW_READ, stale)) return false;
    }
    uint8_t addr = reg & 0x7F;
    _connection.write((uint32_t)HW_WRITE, &addr, 1);
    if (!_connection.ok()) return false;
    if (!_wait_status(RX_VALID, RX_VALID)) return false;
    return _hw_read(HW_READ, value);
}

bool AS7262Minimal::write_virtual(uint8_t reg, uint8_t value) {
    if (!_wait_status(TX_VALID, 0)) return false;
    uint8_t addr = (reg & 0x7F) | 0x80;
    _connection.write((uint32_t)HW_WRITE, &addr, 1);
    if (!_connection.ok()) return false;
    if (!_wait_status(TX_VALID, 0)) return false;
    _connection.write((uint32_t)HW_WRITE, &value, 1);
    return _connection.ok();
}

bool AS7262Minimal::_wait_boot() {
    for (int i = 0; i < 200; i++) {
        uint8_t v;
        if (read_virtual(REG_HW_VERSION, v) && v == 0x40) return true;
        periph_delay_ms(10);
    }
    return false;
}

bool AS7262Minimal::_reset() {
    if (!write_virtual(REG_CONTROL, CTRL_RST)) return false;
    for (int i = 0; i < 100; i++) {
        periph_delay_ms(10);
        uint8_t v;
        if (read_virtual(REG_CONTROL, v) && !(v & CTRL_RST)) return true;
    }
    return false;
}

bool AS7262Minimal::begin() {
    if (!_wait_boot()) return false;
    if (!_reset()) return false;
    return write_virtual(REG_INT_T, INT_T_DEFAULT)
        && write_virtual(REG_LED, 0x00)
        && write_virtual(REG_CONTROL, (uint8_t)(GAIN_DEFAULT << 4));
}

bool AS7262Minimal::_start_one_shot() {
    uint8_t ctrl;
    if (!read_virtual(REG_CONTROL, ctrl)) return false;
    return write_virtual(REG_CONTROL, (uint8_t)((ctrl & 0x70) | (3 << 2)));
}

bool AS7262Minimal::_wait_data() {
    uint8_t int_t;
    if (!read_virtual(REG_INT_T, int_t)) return false;
    unsigned budget_ms = (unsigned)(2.0f * int_t * STEP_MS) + 1000;
    for (unsigned waited = 0;; waited += 5) {
        uint8_t v;
        if (!read_virtual(REG_CONTROL, v)) return false;
        if (v & CTRL_DATA_RDY) return true;
        if (waited >= budget_ms) return false;
        periph_delay_ms(5);
    }
}

bool AS7262Minimal::_read_float(uint8_t first_reg, float& value) {
    uint8_t b[4];
    for (uint8_t i = 0; i < 4; i++)
        if (!read_virtual((uint8_t)(first_reg + i), b[i])) return false;
    uint32_t bits = ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) | ((uint32_t)b[2] << 8) | b[3];
    memcpy(&value, &bits, sizeof(value));
    return true;
}

bool AS7262Minimal::_read_calibrated(AS7262Channels& out) {
    float* f = &out.violet;
    for (uint8_t ch = 0; ch < 6; ch++)
        if (!_read_float((uint8_t)(REG_CAL + 4 * ch), f[ch])) return false;
    return true;
}

bool AS7262Minimal::read(AS7262Channels& out) {
    if (!_start_one_shot() || !_wait_data()) return false;
    return _read_calibrated(out);
}

float AS7262Minimal::temperature() {
    uint8_t v;
    if (!read_virtual(REG_TEMP, v)) return NAN;
    return (float)v;
}

bool AS7262Minimal::_update_control(uint8_t mask, uint8_t bits) {
    uint8_t ctrl;
    if (!read_virtual(REG_CONTROL, ctrl)) return false;
    return write_virtual(REG_CONTROL, (uint8_t)((ctrl & 0x7C & ~mask) | bits));
}

// --- Full ---

AS7262Full* AS7262Full::_active_instance = nullptr;

AS7262Full::AS7262Full(RegisterConnection& connection)
    : AS7262Minimal(connection) {}

bool AS7262Full::_read_raw_regs(AS7262RawChannels& out) {
    uint16_t* w = &out.violet;
    for (uint8_t ch = 0; ch < 6; ch++) {
        uint8_t hi, lo;
        if (!read_virtual((uint8_t)(REG_RAW + 2 * ch), hi)) return false;
        if (!read_virtual((uint8_t)(REG_RAW + 2 * ch + 1), lo)) return false;
        w[ch] = (uint16_t)((hi << 8) | lo);
    }
    return true;
}

bool AS7262Full::read_raw(AS7262RawChannels& out) {
    if (!_start_one_shot() || !_wait_data()) return false;
    return _read_raw_regs(out);
}

bool AS7262Full::measure() { return _start_one_shot(); }

bool AS7262Full::data_ready() {
    uint8_t v;
    return read_virtual(REG_CONTROL, v) && (v & CTRL_DATA_RDY);
}

bool AS7262Full::read_calibrated(AS7262Channels& out) { return _read_calibrated(out); }

bool AS7262Full::read_raw_last(AS7262RawChannels& out) { return _read_raw_regs(out); }

float AS7262Full::read_channel(uint8_t index) {
    float v;
    if (index > 5 || !_read_float((uint8_t)(REG_CAL + 4 * index), v)) return NAN;
    return v;
}

bool AS7262Full::read_channel_raw(uint8_t index, uint16_t& value) {
    if (index > 5) return false;
    uint8_t hi, lo;
    if (!read_virtual((uint8_t)(REG_RAW + 2 * index), hi)) return false;
    if (!read_virtual((uint8_t)(REG_RAW + 2 * index + 1), lo)) return false;
    value = (uint16_t)((hi << 8) | lo);
    return true;
}

bool AS7262Full::set_gain(float gain) {
    int index = -1;
    for (int i = 0; i < 4; i++) if (GAINS[i] == gain) index = i;
    if (index < 0 && (gain == 0.0f || gain == 2.0f || gain == 3.0f)) index = (int)gain;
    if (index < 0) return false;
    return _update_control(0x30, (uint8_t)(index << 4));
}

float AS7262Full::get_gain() {
    uint8_t v;
    if (!read_virtual(REG_CONTROL, v)) return 0.0f;
    return GAINS[(v >> 4) & 3];
}

bool AS7262Full::set_integration_time(float ms) {
    int n = (int)(ms / STEP_MS + 0.5f);
    if (n < 1 || n > 255) return false;
    return write_virtual(REG_INT_T, (uint8_t)n);
}

float AS7262Full::get_integration_time() {
    uint8_t v;
    if (!read_virtual(REG_INT_T, v)) return 0.0f;
    return v * STEP_MS;
}

bool AS7262Full::set_mode(uint8_t mode) {
    if (mode > 3) return false;
    return _update_control(0x0C, (uint8_t)(mode << 2));
}

uint8_t AS7262Full::get_mode() {
    uint8_t v;
    if (!read_virtual(REG_CONTROL, v)) return 0xFF;
    return (v >> 2) & 3;
}

bool AS7262Full::set_continuous(uint8_t mode) {
    if (mode > 2) return false;
    return set_mode(mode);
}

bool AS7262Full::set_interrupt(bool enabled) {
    return _update_control(CTRL_INT, enabled ? CTRL_INT : 0);
}

bool AS7262Full::set_led(bool on, float current_ma) {
    int index = nearest(DRV_CURRENTS, current_ma);
    if (index < 0) return false;
    uint8_t led;
    if (!read_virtual(REG_LED, led)) return false;
    return write_virtual(REG_LED, (uint8_t)((led & 0x07) | (index << 4) | (on ? 0x08 : 0)));
}

bool AS7262Full::set_indicator_led(bool on, float current_ma) {
    int index = nearest(IND_CURRENTS, current_ma);
    if (index < 0) return false;
    uint8_t led;
    if (!read_virtual(REG_LED, led)) return false;
    return write_virtual(REG_LED, (uint8_t)((led & 0x38) | (index << 1) | (on ? 0x01 : 0)));
}

bool AS7262Full::soft_reset() { return _reset(); }

bool AS7262Full::hardware_version(uint8_t& type, uint8_t& version) {
    return read_virtual(REG_HW_VERSION, type) && read_virtual(REG_HW_VERSION + 1, version);
}

bool AS7262Full::firmware_version(uint8_t& major, uint8_t& minor, uint8_t& sub) {
    uint8_t r2, r3;
    if (!read_virtual(REG_FW_VERSION, r2) || !read_virtual(REG_FW_VERSION + 1, r3)) return false;
    major = r3 >> 4;
    minor = (uint8_t)(((r3 & 0x0F) << 2) | (r2 >> 6));
    sub = r2 & 0x3F;
    return true;
}

uint8_t AS7262Full::device_type() {
    uint8_t v;
    return read_virtual(REG_HW_VERSION, v) ? v : 0;
}

uint8_t AS7262Full::poll_interrupt() {
    uint8_t v;
    if (!read_virtual(REG_CONTROL, v)) return 0;
    return (v & CTRL_DATA_RDY) ? 1 : 0;
}

void AS7262Full::_edge_trampoline() {
    AS7262Full* self = _active_instance;
    if (self && self->_callback) {
        uint8_t status = self->poll_interrupt();
        if (status) self->_callback(status);
    }
}

void AS7262Full::on_interrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    InputPin* pin = intPin ? intPin : _connection.intPin();
    if (!pin) return;
    _callback = callback;
    _int_pin = pin;
    _active_instance = this;
    set_interrupt(true);
    pin->onEdge(&AS7262Full::_edge_trampoline, InputPin::kFalling);
}

void AS7262Full::off_interrupt() {
    if (_int_pin) _int_pin->offEdge(&AS7262Full::_edge_trampoline);
    _int_pin = nullptr;
    _callback = nullptr;
    set_interrupt(false);
}
