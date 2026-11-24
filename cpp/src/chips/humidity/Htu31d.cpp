#include "Htu31d.h"
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

static const float T_RH_S[4] = {0.00111f, 0.00214f, 0.00421f, 0.00834f};
static const float T_T_S[4]  = {0.00157f, 0.00306f, 0.00603f, 0.01198f};

// --- Minimal ---

HTU31DMinimal::HTU31DMinimal(Connection& connection)
    : _connection(connection) {}

uint8_t HTU31DMinimal::_crc8(const uint8_t* data, uint8_t len) {
    uint8_t crc = 0x00;
    for (uint8_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

void HTU31DMinimal::_command(uint8_t code) {
    _connection.write(&code, 1);
}

void HTU31DMinimal::init() {
    _command(CMD_RESET);
    periph_delay_ms(15);
}

void HTU31DMinimal::_start() {
    _command((uint8_t)(CMD_CONVERT | (_osr_rh << 3) | (_osr_t << 1)));
}

unsigned HTU31DMinimal::_wait_ms() const {
    if (_osr_rh == 3 && _osr_t == 3) return 25;
    return (unsigned)ceilf(1.25f * (T_RH_S[_osr_rh] + T_T_S[_osr_t]) * 1000.0f);
}

bool HTU31DMinimal::_read_words(uint8_t command, uint8_t count, uint16_t* words) {
    uint8_t buf[6] = {0};
    _command(command);
    if (!_connection.ok()) return false;
    _connection.read(buf, (size_t)3 * count);
    if (!_connection.ok()) return false;
    for (uint8_t i = 0; i < count; i++) {
        if (_crc8(&buf[3 * i], 2) != buf[3 * i + 2]) return false;
        words[i] = (uint16_t)((buf[3 * i] << 8) | buf[3 * i + 1]);
    }
    return true;
}

bool HTU31DMinimal::_measure(uint8_t command, uint8_t count, uint16_t* words) {
    _start();
    if (!_connection.ok()) return false;
    periph_delay_ms(_wait_ms());
    return _read_words(command, count, words);
}

float HTU31DMinimal::_to_humidity(uint16_t code) {
    float rh = 100.0f * (float)code / 65535.0f;
    return rh < 0.0f ? 0.0f : (rh > 100.0f ? 100.0f : rh);
}

float HTU31DMinimal::_to_temperature(uint16_t code) {
    return -40.0f + 165.0f * (float)code / 65535.0f;
}

bool HTU31DMinimal::read(float& temperature_c, float& humidity_pct) {
    uint16_t w[2];
    if (!_measure(CMD_READ_TRH, 2, w)) return false;
    temperature_c = _to_temperature(w[0]);
    humidity_pct  = _to_humidity(w[1]);
    return true;
}

// --- Full ---

HTU31DFull::HTU31DFull(Connection& connection)
    : HTU31DMinimal(connection) {}

float HTU31DFull::read_humidity() {
    uint16_t w[1];
    return _measure(CMD_READ_RH, 1, w) ? _to_humidity(w[0]) : NAN;
}

float HTU31DFull::read_temperature() {
    uint16_t w[1];
    return _measure(CMD_READ_TRH, 1, w) ? _to_temperature(w[0]) : NAN;
}

bool HTU31DFull::read_raw(uint16_t& t_raw, uint16_t& rh_raw) {
    uint16_t w[2];
    if (!_measure(CMD_READ_TRH, 2, w)) return false;
    t_raw  = w[0];
    rh_raw = w[1];
    return true;
}

bool HTU31DFull::set_resolution(uint8_t osr_rh, uint8_t osr_t) {
    if (osr_rh > 3 || osr_t > 3) return false;
    _osr_rh = osr_rh;
    _osr_t  = osr_t;
    return true;
}

void HTU31DFull::get_resolution(uint8_t& osr_rh, uint8_t& osr_t) {
    osr_rh = _osr_rh;
    osr_t  = _osr_t;
}

float HTU31DFull::conversion_time() {
    return T_RH_S[_osr_rh] + T_T_S[_osr_t];
}

void HTU31DFull::start_conversion() {
    _start();
}

bool HTU31DFull::read_result(float& temperature_c, float& humidity_pct) {
    uint16_t w[2];
    if (!_read_words(CMD_READ_TRH, 2, w)) return false;
    temperature_c = _to_temperature(w[0]);
    humidity_pct  = _to_humidity(w[1]);
    return true;
}

void HTU31DFull::set_heater(bool on) {
    _command(on ? CMD_HEATER_ON : CMD_HEATER_OFF);
}

uint8_t HTU31DFull::diagnostic() {
    uint8_t value = 0;
    _command(CMD_READ_DIAG);
    _connection.read(&value, 1);
    return value;
}

bool HTU31DFull::is_heater_on() {
    return (diagnostic() & DIAG_HEATER_ON) != 0;
}

bool HTU31DFull::serial_number(uint32_t& serial) {
    uint8_t buf[4] = {0};
    _command(CMD_READ_SERIAL);
    _connection.read(buf, 4);
    if (!_connection.ok() || _crc8(buf, 3) != buf[3]) return false;
    serial = ((uint32_t)buf[0] << 16) | ((uint32_t)buf[1] << 8) | buf[2];
    return true;
}

void HTU31DFull::software_reset() {
    _command(CMD_RESET);
    periph_delay_ms(15);
    _osr_rh = 3;
    _osr_t  = 3;
}

float HTU31DFull::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh <= 0.0f) rh = 0.01f;
    float g = logf(rh / 100.0f) + 17.62f * t / (243.12f + t);
    return 243.12f * g / (17.62f - g);
}
