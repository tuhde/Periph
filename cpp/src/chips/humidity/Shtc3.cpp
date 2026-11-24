#include "Shtc3.h"
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

static void delay_ms_fine(unsigned ms) { periph_delay_ms(ms < 1 ? 1 : ms); }

// --- Minimal ---

SHTC3Minimal::SHTC3Minimal(Connection& connection)
    : _connection(connection) {}

uint8_t SHTC3Minimal::_crc8(const uint8_t* data, uint8_t len) {
    uint8_t crc = 0xFF;
    for (uint8_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

void SHTC3Minimal::_command(uint16_t code) {
    uint8_t buf[2] = {(uint8_t)(code >> 8), (uint8_t)(code & 0xFF)};
    _connection.write(buf, 2);
}

void SHTC3Minimal::_wake() {
    if (_asleep) {
        _command(CMD_WAKE);
        delay_ms_fine(1);
        _asleep = false;
    }
}

void SHTC3Minimal::_sleep() {
    _command(CMD_SLEEP);
    _asleep = true;
}

bool SHTC3Minimal::init() {
    uint8_t buf[3] = {0};
    _asleep = true;
    _wake();
    _command(CMD_RESET);
    delay_ms_fine(1);
    _command(CMD_READ_ID);
    if (!_connection.ok()) return false;
    _connection.read(buf, 3);
    bool ok = _connection.ok() && _crc8(buf, 2) == buf[2];
    _sleep();
    if (!ok) return false;
    uint16_t id = (uint16_t)((buf[0] << 8) | buf[1]);
    return (id & 0x083F) == 0x0807 && _connection.ok();
}

bool SHTC3Minimal::_measure(uint16_t& t_raw, uint16_t& rh_raw) {
    static const uint16_t MEASURE[2][2] = {{0x7866, 0x7CA2}, {0x609C, 0x6458}};
    bool ok = false;
    _wake();
    for (uint8_t attempt = 0; attempt < 2 && !ok; attempt++) {
        uint8_t buf[6] = {0};
        _command(MEASURE[_low_power ? 1 : 0][_stretching ? 1 : 0]);
        if (!_connection.ok()) break;
        if (!_stretching) delay_ms_fine(_low_power ? 1 : 13);
        _connection.read(buf, 6);
        if (!_connection.ok()) break;
        if (_crc8(&buf[0], 2) == buf[2] && _crc8(&buf[3], 2) == buf[5]) {
            t_raw  = (uint16_t)((buf[0] << 8) | buf[1]);
            rh_raw = (uint16_t)((buf[3] << 8) | buf[4]);
            ok = true;
        }
    }
    if (_sleep_between) _sleep();
    return ok;
}

float SHTC3Minimal::_to_humidity(uint16_t code) {
    float rh = 100.0f * (float)code / 65536.0f;
    return rh < 0.0f ? 0.0f : (rh > 100.0f ? 100.0f : rh);
}

float SHTC3Minimal::_to_temperature(uint16_t code) {
    return -45.0f + 175.0f * (float)code / 65536.0f;
}

bool SHTC3Minimal::read(float& temperature_c, float& humidity_pct) {
    uint16_t t, rh;
    if (!_measure(t, rh)) return false;
    temperature_c = _to_temperature(t);
    humidity_pct  = _to_humidity(rh);
    return true;
}

// --- Full ---

SHTC3Full::SHTC3Full(Connection& connection)
    : SHTC3Minimal(connection) {}

float SHTC3Full::read_temperature() {
    uint16_t t, rh;
    return _measure(t, rh) ? _to_temperature(t) : NAN;
}

float SHTC3Full::read_humidity() {
    uint16_t t, rh;
    return _measure(t, rh) ? _to_humidity(rh) : NAN;
}

bool SHTC3Full::read_raw(uint16_t& t_raw, uint16_t& rh_raw) {
    return _measure(t_raw, rh_raw);
}

void SHTC3Full::set_low_power(bool on) { _low_power = on; }

void SHTC3Full::set_clock_stretching(bool on) { _stretching = on; }

void SHTC3Full::set_sleep_between(bool on) { _sleep_between = on; }

void SHTC3Full::wake() {
    _command(CMD_WAKE);
    delay_ms_fine(1);
    _asleep = false;
}

void SHTC3Full::sleep() { _sleep(); }

void SHTC3Full::soft_reset() {
    _wake();
    _command(CMD_RESET);
    delay_ms_fine(1);
}

bool SHTC3Full::read_id(uint16_t& id) {
    uint8_t buf[3] = {0};
    bool was_asleep = _asleep;
    _wake();
    _command(CMD_READ_ID);
    _connection.read(buf, 3);
    bool ok = _connection.ok() && _crc8(buf, 2) == buf[2];
    if (was_asleep) _sleep();
    if (!ok) return false;
    id = (uint16_t)((buf[0] << 8) | buf[1]);
    return true;
}

float SHTC3Full::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh <= 0.0f) rh = 0.01f;
    float g = logf(rh / 100.0f) + 17.62f * t / (243.12f + t);
    return 243.12f * g / (17.62f - g);
}
