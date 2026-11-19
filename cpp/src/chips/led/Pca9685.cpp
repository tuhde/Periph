#include "Pca9685.h"

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
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

namespace {
constexpr uint8_t REG_MODE1 = 0x00;
constexpr uint8_t REG_MODE2 = 0x01;
constexpr uint8_t REG_SUBADR1 = 0x02;
constexpr uint8_t REG_ALLCALLADR = 0x05;
constexpr uint8_t REG_LED0 = 0x06;
constexpr uint8_t REG_ALL_LED = 0xFA;
constexpr uint8_t REG_PRE_SCALE = 0xFE;
constexpr uint8_t RESTART = 0x80;
constexpr uint8_t EXTCLK = 0x40;
constexpr uint8_t AI = 0x20;
constexpr uint8_t SLEEP = 0x10;
}

// Out-of-class definitions for ODR-used static constexpr members (AVR builds as C++11).
constexpr uint8_t Pca9685Minimal::CHANNELS;
constexpr uint8_t Pca9685Full::OFF_LOW;
constexpr uint8_t Pca9685Full::OFF_HIGH_OR_HIGHZ;
constexpr uint8_t Pca9685Full::OFF_HIGHZ;

Pca9685Minimal::Pca9685Minimal(RegisterConnection& connection) : _connection(connection) {
    _write1(REG_MODE1, AI | SLEEP);
    _write1(REG_PRE_SCALE, 5);
    _write1(REG_MODE1, AI);
    delay(1);
}

void Pca9685Minimal::_write(uint8_t reg, const uint8_t* data, size_t len) {
    _connection.write(reg, data, len);
}

void Pca9685Minimal::_write1(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t Pca9685Minimal::_read1(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

bool Pca9685Minimal::_duty_bytes(float duty, uint8_t out[4]) {
    if (!(duty >= 0.0f && duty <= 1.0f)) return false;
    uint32_t off = (uint32_t)(duty * 4096.0f + 0.5f);
    out[0] = 0;
    if (off == 0) {
        out[1] = 0; out[2] = 0; out[3] = 0x10;
    } else if (off >= 4096) {
        out[1] = 0x10; out[2] = 0; out[3] = 0;
    } else {
        out[1] = 0; out[2] = (uint8_t)(off & 0xFF); out[3] = (uint8_t)(off >> 8);
    }
    return true;
}

bool Pca9685Minimal::set_duty(uint8_t channel, float duty) {
    uint8_t b[4];
    if (channel > 15 || !_duty_bytes(duty, b)) return false;
    _write(REG_LED0 + 4 * channel, b, 4);
    return true;
}

Pca9685Full::Pca9685Full(RegisterConnection& connection) : Pca9685Minimal(connection) {}

bool Pca9685Full::_pwm_bytes(uint16_t on, uint16_t off, uint8_t out[4]) {
    if (on > 4095 || off > 4095 || on == off) return false;
    out[0] = on & 0xFF; out[1] = on >> 8; out[2] = off & 0xFF; out[3] = off >> 8;
    return true;
}

bool Pca9685Full::set_pwm(uint8_t channel, uint16_t on, uint16_t off) {
    uint8_t b[4];
    if (channel > 15 || !_pwm_bytes(on, off, b)) return false;
    _write(REG_LED0 + 4 * channel, b, 4);
    return true;
}

bool Pca9685Full::get_pwm(uint8_t channel, uint16_t& on, uint16_t& off) {
    if (channel > 15) return false;
    uint8_t d[4];
    _connection.read(REG_LED0 + 4 * channel, d, 4);
    on = (uint16_t)(((d[1] & 0x0F) << 8) | d[0]);
    off = (uint16_t)(((d[3] & 0x0F) << 8) | d[2]);
    return true;
}

bool Pca9685Full::set_full_on(uint8_t channel) {
    if (channel > 15) return false;
    const uint8_t b[4] = {0, 0x10, 0, 0};
    _write(REG_LED0 + 4 * channel, b, 4);
    return true;
}

bool Pca9685Full::set_full_off(uint8_t channel) {
    if (channel > 15) return false;
    const uint8_t b[4] = {0, 0, 0, 0x10};
    _write(REG_LED0 + 4 * channel, b, 4);
    return true;
}

bool Pca9685Full::set_all_duty(float duty) {
    uint8_t b[4];
    if (!_duty_bytes(duty, b)) return false;
    _write(REG_ALL_LED, b, 4);
    return true;
}

bool Pca9685Full::set_all_pwm(uint16_t on, uint16_t off) {
    uint8_t b[4];
    if (!_pwm_bytes(on, off, b)) return false;
    _write(REG_ALL_LED, b, 4);
    return true;
}

bool Pca9685Full::set_frequency(float hz, float osc_hz) {
    if (!(hz > 0.0f) || !(osc_hz > 0.0f)) return false;
    float p = osc_hz / (4096.0f * hz) + 0.5f;
    int32_t prescale = p > 1000.0f ? 1000 : (int32_t)p;
    prescale -= 1;
    if (prescale < 3) prescale = 3;
    if (prescale > 255) prescale = 255;
    uint8_t old = _read1(REG_MODE1);
    uint8_t awake = old & (uint8_t)~(RESTART | SLEEP);
    _write1(REG_MODE1, (old & 0x7F) | SLEEP);
    _write1(REG_PRE_SCALE, (uint8_t)prescale);
    _write1(REG_MODE1, awake);
    delay(1);
    if (old & RESTART) _write1(REG_MODE1, awake | RESTART);
    return true;
}

float Pca9685Full::get_frequency(float osc_hz) {
    return osc_hz / (4096.0f * ((float)_read1(REG_PRE_SCALE) + 1.0f));
}

bool Pca9685Full::set_pulse_width(uint8_t channel, float seconds) {
    if (channel > 15 || !(seconds >= 0.0f)) return false;
    float duty = seconds * get_frequency();
    return set_duty(channel, duty > 1.0f ? 1.0f : duty);
}

void Pca9685Full::sleep() {
    _write1(REG_MODE1, (_read1(REG_MODE1) & 0x7F) | SLEEP);
}

void Pca9685Full::wake() {
    uint8_t old = _read1(REG_MODE1);
    _write1(REG_MODE1, old & (uint8_t)~(RESTART | SLEEP));
    delay(1);
    if (old & RESTART) _write1(REG_MODE1, (old & (uint8_t)~SLEEP) | RESTART);
}

void Pca9685Full::restart() {
    uint8_t old = _read1(REG_MODE1);
    _write1(REG_MODE1, old & (uint8_t)~(RESTART | SLEEP));
    delay(1);
    _write1(REG_MODE1, (old & (uint8_t)~SLEEP) | RESTART);
}

bool Pca9685Full::set_output_mode(bool open_drain, bool invert, uint8_t off_state) {
    if (off_state > 2) return false;
    uint8_t mode2 = off_state | (invert ? 0x10 : 0) | (open_drain ? 0 : 0x04);
    uint8_t old = _read1(REG_MODE2);
    _write1(REG_MODE2, (old & 0x08) | mode2);
    return true;
}

void Pca9685Full::set_update_on_ack(bool enabled) {
    uint8_t old = _read1(REG_MODE2);
    _write1(REG_MODE2, enabled ? (old | 0x08) : (old & (uint8_t)~0x08));
}

void Pca9685Full::use_external_clock() {
    uint8_t sleeping = (_read1(REG_MODE1) & 0x7F) | SLEEP;
    _write1(REG_MODE1, sleeping);
    _write1(REG_MODE1, sleeping | EXTCLK);
    _write1(REG_MODE1, (sleeping | EXTCLK) & (uint8_t)~SLEEP);
    delay(1);
}

bool Pca9685Full::set_allcall(bool enabled, uint8_t address) {
    if (address > 0x7F) return false;
    _write1(REG_ALLCALLADR, (uint8_t)(address << 1));
    uint8_t old = _read1(REG_MODE1);
    _write1(REG_MODE1, (old & 0x7E) | (enabled ? 1 : 0));
    return true;
}

bool Pca9685Full::set_subaddress(uint8_t index, uint8_t address, bool enabled) {
    if (index < 1 || index > 3 || address > 0x7F) return false;
    _write1(REG_SUBADR1 + index - 1, (uint8_t)(address << 1));
    uint8_t mask = (uint8_t)(1 << (4 - index));
    uint8_t old = _read1(REG_MODE1) & 0x7F;
    _write1(REG_MODE1, enabled ? (old | mask) : (old & (uint8_t)~mask));
    return true;
}

void Pca9685Full::reset() {
    _write1(REG_MODE1, 0x10);
    _write1(REG_PRE_SCALE, 0x1E);
    _write1(REG_MODE2, 0x04);
    const uint8_t off[4] = {0, 0, 0, 0x10};
    _write(REG_ALL_LED, off, 4);
    const uint8_t addrs[4] = {0xE2, 0xE4, 0xE8, 0xE0};
    _write(REG_SUBADR1, addrs, 4);
    _write1(REG_MODE1, 0x11);
}

void Pca9685Full::software_reset(Connection& general_call) {
    const uint8_t b = 0x06;
    general_call.write(&b, 1);
}

void Pca9685Full::read_mode(uint8_t& mode1, uint8_t& mode2) {
    uint8_t d[2];
    _connection.read(REG_MODE1, d, 2);
    mode1 = d[0];
    mode2 = d[1];
}
