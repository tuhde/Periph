#include "HDC1080.h"
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

// --- Minimal ---

HDC1080Minimal::HDC1080Minimal(Connection& connection)
    : _connection(connection) {}

void HDC1080Minimal::_write_config() {
    uint8_t b[3] = {REG_CONFIG, (uint8_t)(_config >> 8), (uint8_t)(_config & 0xFF)};
    _connection.write(b, 3);
}

void HDC1080Minimal::init() {
    _config = 0x1000;
    _write_config();
}

bool HDC1080Minimal::_trigger_read(uint8_t pointer, uint8_t* buf, uint8_t n) {
    _connection.write(&pointer, 1);
    for (uint8_t i = 0; i < 50; i++) {
        _connection.read(buf, n);
        if (_connection.ok()) return true;
        periph_delay_ms(1);                            // read header NACKed: not ready yet
    }
    return false;
}

void HDC1080Minimal::_set_mode(bool sequence) {
    uint16_t want = sequence ? (_config | CFG_MODE) : (_config & ~CFG_MODE);
    if (want != _config) {
        _config = want;
        _write_config();
    }
}

bool HDC1080Minimal::_measure_pair(uint16_t& raw_t, uint16_t& raw_h) {
    uint8_t d[4] = {0};
    _set_mode(true);
    if (!_trigger_read(REG_TEMPERATURE, d, 4)) return false;
    raw_t = (uint16_t)((d[0] << 8) | d[1]);
    raw_h = (uint16_t)((d[2] << 8) | d[3]);
    return true;
}

float HDC1080Minimal::_to_temperature(uint16_t raw) { return raw / 65536.0f * 165.0f - 40.0f; }
float HDC1080Minimal::_to_humidity(uint16_t raw) { return raw / 65536.0f * 100.0f; }

bool HDC1080Minimal::read(float& temperature_c, float& humidity_pct) {
    uint16_t t, h;
    if (!_measure_pair(t, h)) return false;
    temperature_c = _to_temperature(t);
    humidity_pct = _to_humidity(h);
    return true;
}

// --- Full ---

HDC1080Full::HDC1080Full(Connection& connection) : HDC1080Minimal(connection) {}

bool HDC1080Full::read_raw(uint16_t& temperature_raw, uint16_t& humidity_raw) {
    return _measure_pair(temperature_raw, humidity_raw);
}

float HDC1080Full::read_temperature() {
    uint8_t d[2] = {0};
    _set_mode(false);
    if (!_trigger_read(REG_TEMPERATURE, d, 2)) return NAN;
    return _to_temperature((uint16_t)((d[0] << 8) | d[1]));
}

float HDC1080Full::read_humidity() {
    uint8_t d[2] = {0};
    _set_mode(false);
    if (!_trigger_read(REG_HUMIDITY, d, 2)) return NAN;
    return _to_humidity((uint16_t)((d[0] << 8) | d[1]));
}

bool HDC1080Full::set_resolution(uint8_t temperature_bits, uint8_t humidity_bits) {
    if (temperature_bits != 14 && temperature_bits != 11) return false;
    if (humidity_bits != 14 && humidity_bits != 11 && humidity_bits != 8) return false;
    uint16_t cfg = _config & ~(CFG_TRES | CFG_HRES);
    if (temperature_bits == 11) cfg |= CFG_TRES;
    if (humidity_bits == 11) cfg |= 0x0100;
    else if (humidity_bits == 8) cfg |= 0x0200;
    _config = cfg;
    _write_config();
    return true;
}

void HDC1080Full::set_heater(bool on) {
    _config = on ? (_config | CFG_HEAT) : (_config & ~CFG_HEAT);
    _write_config();
}

uint16_t HDC1080Full::read_config() {
    uint8_t p = REG_CONFIG;
    uint8_t d[2] = {0};
    _connection.write(&p, 1);
    _connection.read(d, 2);
    return (uint16_t)((d[0] << 8) | d[1]);
}

bool HDC1080Full::is_heater_on() { return (read_config() & CFG_HEAT) != 0; }
bool HDC1080Full::battery_low() { return (read_config() & CFG_BTST) != 0; }

void HDC1080Full::software_reset() {
    uint8_t b[3] = {REG_CONFIG, (uint8_t)(CFG_RST >> 8), 0x00};
    _connection.write(b, 3);
    periph_delay_ms(15);
    _write_config();
}

uint64_t HDC1080Full::serial_id() {
    uint16_t w[3];
    for (uint8_t i = 0; i < 3; i++) {
        uint8_t p = (uint8_t)(REG_SERIAL1 + i);
        uint8_t d[2] = {0};
        _connection.write(&p, 1);
        _connection.read(d, 2);
        w[i] = (uint16_t)((d[0] << 8) | d[1]);
    }
    return ((uint64_t)w[0] << 24) | ((uint64_t)w[1] << 8) | (uint64_t)(w[2] >> 7);
}

float HDC1080Full::conversion_time() {
    float t = (_config & CFG_TRES) ? 0.00365f : 0.00635f;
    uint16_t h = _config & CFG_HRES;
    return t + (h == 0x0100 ? 0.00385f : (h == 0x0200 ? 0.0025f : 0.0065f));
}

static uint16_t hdc1080_read_word(Connection& c, uint8_t reg) {
    uint8_t d[2] = {0};
    c.write(&reg, 1);
    c.read(d, 2);
    return (uint16_t)((d[0] << 8) | d[1]);
}

uint16_t HDC1080Full::manufacturer_id() { return hdc1080_read_word(_connection, 0xFE); }

uint16_t HDC1080Full::device_id() { return hdc1080_read_word(_connection, 0xFF); }
