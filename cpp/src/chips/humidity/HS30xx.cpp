#include "HS30xx.h"
#include <math.h>

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

static const uint8_t NVM_OK = 0x81;
static const uint16_t RES_MASK = 0x0C00;
static const uint8_t BITS[4] = {8, 10, 12, 14};

static int bits_code(uint8_t bits) {
    for (int i = 0; i < 4; i++) if (BITS[i] == bits) return i;
    return -1;
}

// --- Minimal ---

HS30xxMinimal::HS30xxMinimal(Connection& connection)
    : _connection(connection) {}

uint16_t HS30xxMinimal::_raw_humidity(const uint8_t* d) { return (uint16_t)(((d[0] & 0x3F) << 8) | d[1]); }
uint16_t HS30xxMinimal::_raw_temperature(const uint8_t* d) { return (uint16_t)((d[2] << 6) | (d[3] >> 2)); }
float HS30xxMinimal::_to_humidity(uint16_t raw) { return raw / 16383.0f * 100.0f; }
float HS30xxMinimal::_to_temperature(uint16_t raw) { return raw / 16383.0f * 165.0f - 40.0f; }

bool HS30xxMinimal::_fetch_raw(uint8_t* buf, uint8_t len) {
    // The measurement request is an address-only write (no data byte).
    uint8_t none = 0;
    _connection.write(&none, 0);
    if (!_connection.ok()) return false;
    periph_delay_ms(_wait_ms);
    _connection.read(buf, len);
    return _connection.ok();
}

bool HS30xxMinimal::_fetch(uint8_t* buf, uint8_t len) {
    if (!_fetch_raw(buf, len)) return false;
    if ((buf[0] >> 6) != 0) {
        periph_delay_ms(10);
        _connection.read(buf, len);
        if (!_connection.ok() || (buf[0] >> 6) != 0) return false;
    }
    return true;
}

bool HS30xxMinimal::read(float& temperature_c, float& humidity_pct) {
    uint8_t d[4];
    if (!_fetch(d, 4)) return false;
    temperature_c = _to_temperature(_raw_temperature(d));
    humidity_pct = _to_humidity(_raw_humidity(d));
    return true;
}

// --- Full ---

HS30xxFull::HS30xxFull(Connection& connection)
    : HS30xxMinimal(connection) {}

float HS30xxFull::read_temperature() {
    uint8_t d[4];
    return _fetch(d, 4) ? _to_temperature(_raw_temperature(d)) : NAN;
}

float HS30xxFull::read_humidity() {
    uint8_t d[4];
    return _fetch(d, 2) ? _to_humidity(_raw_humidity(d)) : NAN;
}

bool HS30xxFull::read_raw(uint16_t& humidity_raw, uint16_t& temperature_raw, uint8_t& status) {
    uint8_t d[4];
    if (!_fetch_raw(d, 4)) return false;
    humidity_raw = _raw_humidity(d);
    temperature_raw = _raw_temperature(d);
    status = d[0] >> 6;
    return true;
}

void HS30xxFull::set_measurement_wait(unsigned ms) {
    _wait_ms = ms > 0 ? ms : 1;
}

void HS30xxFull::enter_programming_mode() {
    const uint8_t cmd[3] = {0xA0, 0x00, 0x00};
    _connection.write(cmd, 3);
    periph_delay_ms(1);
}

void HS30xxFull::exit_programming_mode() {
    const uint8_t cmd[3] = {0x80, 0x00, 0x00};
    _connection.write(cmd, 3);
}

bool HS30xxFull::read_nvm(uint8_t reg, uint16_t& value) {
    const uint8_t cmd[3] = {reg, 0x00, 0x00};
    _connection.write(cmd, 3);
    if (!_connection.ok()) return false;
    periph_delay_ms(1);
    uint8_t d[3];
    _connection.read(d, 3);
    if (!_connection.ok() || d[0] != NVM_OK) return false;
    value = (uint16_t)((d[1] << 8) | d[2]);
    return true;
}

void HS30xxFull::_write_nvm(uint8_t reg, uint16_t value) {
    const uint8_t cmd[3] = {reg, (uint8_t)(value >> 8), (uint8_t)value};
    _connection.write(cmd, 3);
    periph_delay_ms(14);
}

bool HS30xxFull::_enter_programming(void (*power_cycle)()) {
    if (power_cycle == nullptr) return false;
    power_cycle();
    enter_programming_mode();
    return true;
}

bool HS30xxFull::get_resolution(uint8_t& humidity_bits, uint8_t& temperature_bits, void (*power_cycle)()) {
    if (!_enter_programming(power_cycle)) return false;
    uint16_t rh = 0, t = 0;
    bool ok = read_nvm(0x06, rh) && read_nvm(0x11, t);
    exit_programming_mode();
    if (!ok) return false;
    humidity_bits = BITS[(rh >> 10) & 3];
    temperature_bits = BITS[(t >> 10) & 3];
    return true;
}

bool HS30xxFull::set_resolution(uint8_t humidity_bits, uint8_t temperature_bits, void (*power_cycle)()) {
    int rh_code = humidity_bits ? bits_code(humidity_bits) : -2;
    int t_code = temperature_bits ? bits_code(temperature_bits) : -2;
    if (rh_code == -1 || t_code == -1) return false;
    if (!_enter_programming(power_cycle)) return false;
    bool ok = true;
    uint16_t value = 0;
    if (rh_code >= 0) {
        ok = read_nvm(0x06, value);
        if (ok) _write_nvm(0x46, (uint16_t)((value & ~RES_MASK) | (rh_code << 10)));
    }
    if (ok && t_code >= 0) {
        ok = read_nvm(0x11, value);
        if (ok) _write_nvm(0x51, (uint16_t)((value & ~RES_MASK) | (t_code << 10)));
    }
    exit_programming_mode();
    return ok;
}

bool HS30xxFull::read_id(uint32_t& id, void (*power_cycle)()) {
    if (!_enter_programming(power_cycle)) return false;
    uint16_t high = 0, low = 0;
    bool ok = read_nvm(0x1E, high) && read_nvm(0x1F, low);
    exit_programming_mode();
    if (!ok) return false;
    id = ((uint32_t)high << 16) | low;
    return true;
}

float HS30xxFull::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh <= 0.0f) return -273.15f;
    float gamma = logf(rh / 100.0f) + 17.62f * t / (243.12f + t);
    return 243.12f * gamma / (17.62f - gamma);
}
