#include "SHT31.h"
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

// Single-shot command codes per repeatability level (0 low, 1 medium, 2 high).
static const uint16_t SINGLE_NO_STRETCH[3] = {0x2416, 0x240B, 0x2400};
static const uint16_t SINGLE_STRETCH[3]    = {0x2C10, 0x2C0D, 0x2C06};
static const uint8_t  SINGLE_WAIT_MS[3]    = {4, 6, 15};

// Periodic command codes: rows 0.5 / 1 / 2 / 4 / 10 mps, columns low / medium / high.
static const uint16_t PERIODIC[5][3] = {
    {0x202F, 0x2024, 0x2032},
    {0x212D, 0x2126, 0x2130},
    {0x222B, 0x2220, 0x2236},
    {0x2329, 0x2322, 0x2334},
    {0x272A, 0x2721, 0x2737},
};

// --- Minimal ---

SHT31Minimal::SHT31Minimal(Connection& connection)
    : _connection(connection) {
    periph_delay_ms(1);
    _command(CMD_SOFT_RESET);
    periph_delay_ms(1);
}

uint8_t SHT31Minimal::_crc8(const uint8_t* data, uint8_t len) {
    uint8_t crc = 0xFF;
    for (uint8_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

void SHT31Minimal::_command(uint16_t code) {
    uint8_t buf[2] = {(uint8_t)(code >> 8), (uint8_t)(code & 0xFF)};
    _connection.write(buf, 2);
}

bool SHT31Minimal::_decode(const uint8_t* b, uint16_t& raw_t, uint16_t& raw_rh) {
    if (_crc8(b, 2) != b[2] || _crc8(b + 3, 2) != b[5]) return false;
    raw_t = (uint16_t)((b[0] << 8) | b[1]);
    raw_rh = (uint16_t)((b[3] << 8) | b[4]);
    return true;
}

void SHT31Minimal::_convert(uint16_t raw_t, uint16_t raw_rh, float& t, float& rh) {
    t = -45.0f + 175.0f * raw_t / 65535.0f;
    rh = 100.0f * raw_rh / 65535.0f;
}

bool SHT31Minimal::_single_shot(uint16_t& raw_t, uint16_t& raw_rh) {
    for (uint8_t attempt = 0; attempt < 2; attempt++) {
        _command(_stretch ? SINGLE_STRETCH[_repeatability] : SINGLE_NO_STRETCH[_repeatability]);
        if (!_stretch) periph_delay_ms(SINGLE_WAIT_MS[_repeatability]);
        uint8_t buf[6] = {0};
        bool got = false;
        for (uint8_t i = 0; i < 21; i++) {
            _connection.read(buf, 6);
            if (_connection.ok()) { got = true; break; }
            periph_delay_ms(1);                        // read header NACKed: not ready yet
        }
        if (!got) return false;
        if (_decode(buf, raw_t, raw_rh)) return true;
    }
    return false;
}

bool SHT31Minimal::read(float& temperature_c, float& humidity_pct) {
    uint16_t raw_t, raw_rh;
    if (!_single_shot(raw_t, raw_rh)) return false;
    _convert(raw_t, raw_rh, temperature_c, humidity_pct);
    return true;
}

// --- Full ---

SHT31Full::SHT31Full(Connection& connection)
    : SHT31Minimal(connection) {}

float SHT31Full::read_temperature() {
    float t, rh;
    return read(t, rh) ? t : NAN;
}

float SHT31Full::read_humidity() {
    float t, rh;
    return read(t, rh) ? rh : NAN;
}

bool SHT31Full::read_raw(uint16_t& raw_temperature, uint16_t& raw_humidity) {
    return _single_shot(raw_temperature, raw_humidity);
}

bool SHT31Full::set_repeatability(uint8_t level) {
    if (level > REPEATABILITY_HIGH) return false;
    _repeatability = level;
    return true;
}

void SHT31Full::set_clock_stretching(bool on) { _stretch = on; }

bool SHT31Full::start_periodic(float rate, uint8_t repeatability, bool art) {
    if (art) { _command(CMD_ART); return true; }
    if (repeatability > REPEATABILITY_HIGH) return false;
    int row = -1;
    if (rate == 0.5f) row = 0;
    else if (rate == 1.0f) row = 1;
    else if (rate == 2.0f) row = 2;
    else if (rate == 4.0f) row = 3;
    else if (rate == 10.0f) row = 4;
    if (row < 0) return false;
    _command(PERIODIC[row][repeatability]);
    return true;
}

bool SHT31Full::fetch(float& temperature_c, float& humidity_pct) {
    _command(CMD_FETCH);
    uint8_t buf[6] = {0};
    _connection.read(buf, 6);
    if (!_connection.ok()) return false;
    uint16_t raw_t, raw_rh;
    if (!_decode(buf, raw_t, raw_rh)) return false;
    _convert(raw_t, raw_rh, temperature_c, humidity_pct);
    return true;
}

void SHT31Full::stop_periodic() {
    _command(CMD_BREAK);
    periph_delay_ms(1);
}

void SHT31Full::set_heater(bool on) {
    if (on) _command(CMD_HEATER_ON);
    else    _command(CMD_HEATER_OFF);
}

bool SHT31Full::read_status(uint16_t& status) {
    _command(CMD_READ_STATUS);
    uint8_t buf[3] = {0};
    _connection.read(buf, 3);
    if (!_connection.ok() || _crc8(buf, 2) != buf[2]) return false;
    status = (uint16_t)((buf[0] << 8) | buf[1]);
    return true;
}

void SHT31Full::clear_status() { _command(CMD_CLEAR_STATUS); }

void SHT31Full::soft_reset() {
    _command(CMD_SOFT_RESET);
    periph_delay_ms(1);
}

float SHT31Full::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh <= 0.0f) rh = 0.01f;
    float gamma = logf(rh / 100.0f) + (17.62f * t) / (243.12f + t);
    return 243.12f * gamma / (17.62f - gamma);
}
