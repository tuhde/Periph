#include "LTC4151.h"

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

namespace {
    constexpr uint8_t SNAPSHOT_POLL_MS = 2;
    constexpr uint8_t SNAPSHOT_POLLS   = 50;   // 50 × 2 ms = 100 ms timeout
}

// LTC4151Minimal

LTC4151Minimal::LTC4151Minimal(RegisterConnection& connection, float r_sense)
    : _connection(connection), _r_sense(r_sense > 0.0f ? r_sense : 0.02f) {}

float LTC4151Minimal::voltage() {
    uint8_t d[2];
    _connection.read(REG_VIN, d, 2);
    return _code(d[0], d[1]) * LSB_VIN;
}

float LTC4151Minimal::current() {
    uint8_t d[2];
    _connection.read(REG_SENSE, d, 2);
    return _code(d[0], d[1]) * LSB_SENSE / _r_sense;
}

float LTC4151Minimal::power() {
    uint8_t d[4];
    _connection.read(REG_SENSE, d, 4);
    return _code(d[2], d[3]) * LSB_VIN * (_code(d[0], d[1]) * LSB_SENSE / _r_sense);
}

// LTC4151Full

LTC4151Full::LTC4151Full(RegisterConnection& connection, float r_sense)
    : LTC4151Minimal(connection, r_sense), _page_rw(true) {}

void LTC4151Full::_read_block(uint8_t reg, uint8_t* buf, uint8_t len) {
    if (_page_rw || len == 1) {
        _connection.read(reg, buf, len);
        return;
    }
    for (uint8_t i = 0; i < len; i++) _connection.read(reg + i, buf + i, 1);
}

uint8_t LTC4151Full::_read_control_raw() {
    uint8_t v = 0;
    _connection.read(REG_CONTROL, &v, 1);
    return v;
}

void LTC4151Full::_write_control_raw(uint8_t value) {
    _connection.write(REG_CONTROL, &value, 1);
}

float LTC4151Full::sense_voltage() {
    uint8_t d[2];
    _read_block(REG_SENSE, d, 2);
    return _code(d[0], d[1]) * LSB_SENSE;
}

float LTC4151Full::adin_voltage() {
    uint8_t d[2];
    _read_block(REG_ADIN, d, 2);
    return _code(d[0], d[1]) * LSB_ADIN;
}

float LTC4151Full::voltage() {
    uint8_t d[2];
    _read_block(REG_VIN, d, 2);
    return _code(d[0], d[1]) * LSB_VIN;
}

float LTC4151Full::current() {
    return sense_voltage() / _r_sense;
}

float LTC4151Full::power() {
    uint8_t d[4];
    _read_block(REG_SENSE, d, 4);
    return _code(d[2], d[3]) * LSB_VIN * (_code(d[0], d[1]) * LSB_SENSE / _r_sense);
}

void LTC4151Full::read_all(Readings& out) {
    uint8_t d[6];
    _read_block(REG_SENSE, d, 6);
    out.sense_v = _code(d[0], d[1]) * LSB_SENSE;
    out.vin     = _code(d[2], d[3]) * LSB_VIN;
    out.adin    = _code(d[4], d[5]) * LSB_ADIN;
    out.current = out.sense_v / _r_sense;
    out.power   = out.vin * out.current;
}

uint16_t LTC4151Full::read_raw(uint8_t channel) {
    if (channel > ADIN) return 0;
    uint8_t d[2];
    _read_block(channel * 2, d, 2);
    return _code(d[0], d[1]);
}

bool LTC4151Full::set_sense_resistor(float ohms) {
    if (!(ohms > 0.0f)) return false;
    _r_sense = ohms;
    return true;
}

bool LTC4151Full::snapshot(uint8_t channel, float& value) {
    if (channel > ADIN) return false;
    uint8_t base = _read_control_raw() & (CTRL_PAGE_RW | CTRL_STUCK_BUS);
    _write_control_raw(base);
    _write_control_raw(base | CTRL_SNAPSHOT | (uint8_t)(channel << 5));
    uint8_t polls = 0;
    while (is_busy(channel)) {
        if (++polls > SNAPSHOT_POLLS) {
            _write_control_raw(base);
            return false;
        }
        delay(SNAPSHOT_POLL_MS);
    }
    uint16_t code = read_raw(channel);
    _write_control_raw(base);
    static const float lsb[3] = { LSB_SENSE, LSB_VIN, LSB_ADIN };
    value = code * lsb[channel];
    return true;
}

void LTC4151Full::set_continuous() {
    _write_control_raw(_read_control_raw() & (uint8_t)~(CTRL_SNAPSHOT | CTRL_TEST));
}

bool LTC4151Full::is_snapshot_mode() {
    return (_read_control_raw() & CTRL_SNAPSHOT) != 0;
}

bool LTC4151Full::is_busy(uint8_t channel) {
    if (channel > ADIN) return false;
    uint8_t v = 0;
    _connection.read(channel * 2 + 1, &v, 1);
    return (v & 0x08) != 0;
}

void LTC4151Full::set_stuck_bus_timer(bool enabled) {
    uint8_t v = _read_control_raw() & (uint8_t)~CTRL_TEST;
    v = enabled ? (v | CTRL_STUCK_BUS) : (v & (uint8_t)~CTRL_STUCK_BUS);
    _write_control_raw(v);
}

void LTC4151Full::set_page_rw(bool enabled) {
    uint8_t v = _read_control_raw() & (uint8_t)~CTRL_TEST;
    v = enabled ? (v | CTRL_PAGE_RW) : (v & (uint8_t)~CTRL_PAGE_RW);
    _write_control_raw(v);
    _page_rw = enabled;
}

uint8_t LTC4151Full::read_control() {
    return _read_control_raw();
}

void LTC4151Full::write_control(uint8_t value) {
    value &= (uint8_t)~CTRL_TEST;
    _write_control_raw(value);
    _page_rw = (value & CTRL_PAGE_RW) != 0;
}

float LTC4151Full::temperature_from_adin(uint16_t adin_code, uint16_t vin_code) {
    if (vin_code == 0) return 0.0f;
    return 58.82f * ((float)adin_code / (float)vin_code - 0.1066f);
}
