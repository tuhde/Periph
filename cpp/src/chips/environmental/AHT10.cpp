#include "AHT10.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t AHT10Minimal::CMD_TRIGGER[3];
constexpr uint8_t AHT10Minimal::CMD_INIT[3];
constexpr uint8_t AHT10Minimal::CMD_SOFT_RESET;

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

AHT10Minimal::AHT10Minimal(Connection& connection)
    : _connection(connection) {
    periph_delay_ms(40);
    _initialize();
    if (!(_read_status() & STATUS_CAL)) {
        // Not calibrated: soft reset and retry the init command once. The
        // constructor cannot report failure; is_calibrated() (Full) exposes it.
        _connection.write(&CMD_SOFT_RESET, 1);
        periph_delay_ms(20);
        _initialize();
    }
}

void AHT10Minimal::_initialize() {
    _connection.write(CMD_INIT, 3);
    periph_delay_ms(10);
}

uint8_t AHT10Minimal::_read_status() {
    uint8_t buf;
    _connection.read(&buf, 1);
    return buf;
}

bool AHT10Minimal::_measure(uint8_t* buf) {
    _connection.write(CMD_TRIGGER, 3);
    periph_delay_ms(80);  // conversion takes > 75 ms
    for (uint8_t waited = 0; ; waited += 10) {
        _connection.read(buf, 6);
        if (!(buf[0] & STATUS_BUSY)) return true;
        if (waited >= 200) return false;
        periph_delay_ms(10);
    }
}

void AHT10Minimal::_raw(const uint8_t* buf, uint32_t& raw_rh, uint32_t& raw_t) {
    raw_rh = ((uint32_t)buf[1] << 12) | ((uint32_t)buf[2] << 4) | (buf[3] >> 4);
    raw_t  = ((uint32_t)(buf[3] & 0x0F) << 16) | ((uint32_t)buf[4] << 8) | buf[5];
}

void AHT10Minimal::_convert(uint32_t raw_rh, uint32_t raw_t, float& temperature_c, float& humidity_pct) {
    humidity_pct  = (raw_rh / 1048576.0f) * 100.0f;
    if (humidity_pct > 100.0f) humidity_pct = 100.0f;
    temperature_c = (raw_t / 1048576.0f) * 200.0f - 50.0f;
}

float AHT10Minimal::temperature() {
    float t, h;
    read(t, h);
    return t;
}

float AHT10Minimal::humidity() {
    float t, h;
    read(t, h);
    return h;
}

bool AHT10Minimal::read(float& temperature_c, float& humidity_pct) {
    uint8_t buf[6];
    if (!_measure(buf)) return false;
    uint32_t rh, t;
    _raw(buf, rh, t);
    _convert(rh, t, temperature_c, humidity_pct);
    return true;
}

// AHT10Full

AHT10Full::AHT10Full(Connection& connection)
    : AHT10Minimal(connection) {}

float AHT10Full::read_temperature() {
    return temperature();
}

float AHT10Full::read_humidity() {
    return humidity();
}

bool AHT10Full::read_raw(uint32_t& raw_humidity, uint32_t& raw_temperature) {
    uint8_t buf[6];
    if (!_measure(buf)) return false;
    _raw(buf, raw_humidity, raw_temperature);
    return true;
}

uint8_t AHT10Full::read_status() {
    return _read_status();
}

bool AHT10Full::is_calibrated() {
    return (_read_status() & STATUS_CAL) != 0;
}

bool AHT10Full::is_busy() {
    return (_read_status() & STATUS_BUSY) != 0;
}

void AHT10Full::soft_reset() {
    _connection.write(&CMD_SOFT_RESET, 1);
    periph_delay_ms(20);
    _initialize();
}

float AHT10Full::dew_point() {
    float t, h;
    if (!read(t, h) || h <= 0.0f) return -50.0f;
    float gamma = logf(h / 100.0f) + 17.62f * t / (243.12f + t);
    return 243.12f * gamma / (17.62f - gamma);
}
