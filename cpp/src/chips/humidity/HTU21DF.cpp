#include "HTU21DF.h"
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

static const uint8_t USER_HEATER   = 0x04;
static const uint8_t USER_VDD_LOW  = 0x40;
static const uint8_t USER_RES_MASK = 0x81;

// --- Minimal ---

HTU21DFMinimal::HTU21DFMinimal(Connection& connection)
    : _connection(connection) {}

uint8_t HTU21DFMinimal::_crc8(const uint8_t* data, uint8_t len) {
    uint8_t crc = 0x00;
    for (uint8_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

void HTU21DFMinimal::_command(uint8_t code) {
    _connection.write(&code, 1);
}

void HTU21DFMinimal::init() {
    periph_delay_ms(15);
    _command(CMD_RESET);
    periph_delay_ms(15);
}

bool HTU21DFMinimal::_measure_once(uint8_t hold_cmd, uint8_t poll_cmd, uint8_t* buf) {
    if (_hold) {
        _command(hold_cmd);
        _connection.read(buf, 3);
        return _connection.ok();
    }
    _command(poll_cmd);
    for (uint8_t i = 0; i < 100; i++) {
        _connection.read(buf, 3);
        if (_connection.ok()) return true;
        periph_delay_ms(1);                            // read header NACKed: not done yet
    }
    return false;
}

bool HTU21DFMinimal::_measure(uint8_t hold_cmd, uint8_t poll_cmd, uint16_t& code) {
    uint8_t buf[3] = {0};
    for (uint8_t attempt = 0; attempt < 2; attempt++) {
        if (!_measure_once(hold_cmd, poll_cmd, buf)) return false;
        if (_crc8(buf, 2) == buf[2]) {
            code = (uint16_t)(((buf[0] << 8) | buf[1]) & 0xFFFC);
            return true;
        }
    }
    return false;
}

float HTU21DFMinimal::_to_humidity(uint16_t code) {
    return -6.0f + 125.0f * code / 65536.0f;
}

float HTU21DFMinimal::_to_temperature(uint16_t code) {
    return -46.85f + 175.72f * code / 65536.0f;
}

bool HTU21DFMinimal::read(float& temperature_c, float& humidity_pct) {
    uint16_t t, rh;
    if (!_measure(CMD_T_HOLD, CMD_T_POLL, t)) return false;
    if (!_measure(CMD_RH_HOLD, CMD_RH_POLL, rh)) return false;
    temperature_c = _to_temperature(t);
    humidity_pct = _to_humidity(rh);
    return true;
}

// --- Full ---

HTU21DFFull::HTU21DFFull(Connection& connection)
    : HTU21DFMinimal(connection) {}

float HTU21DFFull::read_temperature() {
    uint16_t code;
    return _measure(CMD_T_HOLD, CMD_T_POLL, code) ? _to_temperature(code) : NAN;
}

float HTU21DFFull::read_humidity() {
    uint16_t code;
    return _measure(CMD_RH_HOLD, CMD_RH_POLL, code) ? _to_humidity(code) : NAN;
}

float HTU21DFFull::read_humidity_compensated() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    return rh + (25.0f - t) * (-0.15f);
}

bool HTU21DFFull::read_raw(uint16_t& temperature_raw, uint16_t& humidity_raw) {
    return _measure(CMD_T_HOLD, CMD_T_POLL, temperature_raw) && _measure(CMD_RH_HOLD, CMD_RH_POLL, humidity_raw);
}

uint8_t HTU21DFFull::read_user_register() {
    uint8_t cmd = CMD_READ_USER, value = 0;
    _connection.write_read(&cmd, 1, &value, 1);
    return value;
}

bool HTU21DFFull::set_resolution(uint8_t res) {
    if (res > 3) return false;
    uint8_t bits = (uint8_t)(((res >> 1) << 7) | (res & 1));
    uint8_t buf[2] = {CMD_WRITE_USER, (uint8_t)((read_user_register() & ~USER_RES_MASK) | bits)};
    _connection.write(buf, 2);
    return true;
}

uint8_t HTU21DFFull::get_resolution() {
    uint8_t reg = read_user_register();
    return (uint8_t)((((reg >> 7) & 1) << 1) | (reg & 1));
}

void HTU21DFFull::set_heater(bool on) {
    uint8_t reg = read_user_register();
    uint8_t buf[2] = {CMD_WRITE_USER, on ? (uint8_t)(reg | USER_HEATER) : (uint8_t)(reg & ~USER_HEATER)};
    _connection.write(buf, 2);
}

bool HTU21DFFull::heater() { return (read_user_register() & USER_HEATER) != 0; }

bool HTU21DFFull::battery_low() { return (read_user_register() & USER_VDD_LOW) != 0; }

void HTU21DFFull::soft_reset() {
    _command(CMD_RESET);
    periph_delay_ms(15);
}

void HTU21DFFull::set_hold_master(bool on) { _hold = on; }

float HTU21DFFull::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh <= 0.0f) rh = 0.01f;
    float pp = powf(10.0f, 8.1332f - 1762.39f / (t + 235.66f));
    return -1762.39f / (log10f(rh * pp / 100.0f) - 8.1332f) - 235.66f;
}
