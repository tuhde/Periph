#include "AHT20.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t AHT20Minimal::CMD_TRIGGER[3];
constexpr uint8_t AHT20Minimal::CMD_STATUS;
constexpr uint8_t AHT20Minimal::CMD_CAL_INIT[3][3];
constexpr uint8_t AHT20Minimal::CMD_SOFT_RESET;

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
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

AHT20Minimal::AHT20Minimal(Connection& connection)
    : _connection(connection) {
    periph_delay_ms(100);
    _calibrate();
    periph_delay_ms(10);
}

bool AHT20Minimal::_ready() {
    return (_read_status() & STATUS_READY) == STATUS_READY;
}

// The datasheet tests the status word against 0x18 once after power-on. The
// constructor cannot report failure; is_calibrated() (Full) exposes it.
void AHT20Minimal::_calibrate() {
    if (_ready()) return;
    _connection.write(&CMD_SOFT_RESET, 1);
    periph_delay_ms(20);
    if (_ready()) return;
    for (uint8_t i = 0; i < 3; i++) {
        _connection.write(CMD_CAL_INIT[i], 3);
        periph_delay_ms(10);
    }
}

uint8_t AHT20Minimal::_read_status() {
    uint8_t buf;
    _connection.write(&CMD_STATUS, 1);
    _connection.read(&buf, 1);
    return buf;
}

bool AHT20Minimal::_measure(uint8_t* buf, uint8_t length) {
    _connection.write(CMD_TRIGGER, 3);
    periph_delay_ms(80);  // conversion takes > 80 ms
    for (uint8_t waited = 0; ; waited += 10) {
        _connection.read(buf, length);
        if (!(buf[0] & STATUS_BUSY)) return true;
        if (waited >= 200) return false;
        periph_delay_ms(10);
    }
}

void AHT20Minimal::_raw(const uint8_t* buf, uint32_t& raw_rh, uint32_t& raw_t) {
    raw_rh = ((uint32_t)buf[1] << 12) | ((uint32_t)buf[2] << 4) | (buf[3] >> 4);
    raw_t  = ((uint32_t)(buf[3] & 0x0F) << 16) | ((uint32_t)buf[4] << 8) | buf[5];
}

void AHT20Minimal::_convert(uint32_t raw_rh, uint32_t raw_t, float& temperature_c, float& humidity_pct) {
    humidity_pct  = (raw_rh / 1048576.0f) * 100.0f;
    if (humidity_pct > 100.0f) humidity_pct = 100.0f;
    temperature_c = (raw_t / 1048576.0f) * 200.0f - 50.0f;
}

float AHT20Minimal::temperature() {
    float t, h;
    read(t, h);
    return t;
}

float AHT20Minimal::humidity() {
    float t, h;
    read(t, h);
    return h;
}

bool AHT20Minimal::read(float& temperature_c, float& humidity_pct) {
    uint8_t buf[6];
    if (!_measure(buf)) return false;
    uint32_t rh, t;
    _raw(buf, rh, t);
    _convert(rh, t, temperature_c, humidity_pct);
    return true;
}

// AHT20Full

AHT20Full::AHT20Full(Connection& connection)
    : AHT20Minimal(connection) {}

float AHT20Full::read_temperature() {
    return temperature();
}

float AHT20Full::read_humidity() {
    return humidity();
}

bool AHT20Full::read_raw(uint32_t& raw_humidity, uint32_t& raw_temperature) {
    uint8_t buf[6];
    if (!_measure(buf)) return false;
    _raw(buf, raw_humidity, raw_temperature);
    return true;
}

uint8_t AHT20Full::crc8(const uint8_t* data, uint8_t length) {
    uint8_t crc = 0xFF;
    for (uint8_t i = 0; i < length; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) {
            crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
        }
    }
    return crc;
}

bool AHT20Full::read_with_crc(float& temperature_c, float& humidity_pct, bool& crc_ok) {
    uint8_t buf[7];
    if (!_measure(buf, 7)) return false;
    uint32_t rh, t;
    _raw(buf, rh, t);
    _convert(rh, t, temperature_c, humidity_pct);
    crc_ok = crc8(buf, 6) == buf[6];
    return true;
}

uint8_t AHT20Full::read_status() {
    return _read_status();
}

bool AHT20Full::is_calibrated() {
    return (_read_status() & STATUS_CAL) != 0;
}

bool AHT20Full::is_busy() {
    return (_read_status() & STATUS_BUSY) != 0;
}

void AHT20Full::soft_reset() {
    _connection.write(&CMD_SOFT_RESET, 1);
    periph_delay_ms(20);
    _calibrate();
}

float AHT20Full::dew_point() {
    float t, h;
    if (!read(t, h) || h <= 0.0f) return -50.0f;
    float gamma = logf(h / 100.0f) + 17.62f * t / (243.12f + t);
    return 243.12f * gamma / (17.62f - gamma);
}
