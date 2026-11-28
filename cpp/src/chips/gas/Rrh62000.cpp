#include "Rrh62000.h"
#include <string.h>

#if defined(__linux__)
#include <time.h>
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
static uint32_t periph_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000);
}
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
static uint32_t periph_now_ms() { return k_uptime_get_32(); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1)
static uint32_t periph_now_ms() { return (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
#define DELAY_MS(ms) HAL_Delay(ms)
static uint32_t periph_now_ms() { return HAL_GetTick(); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
static uint32_t periph_now_ms() { return to_ms_since_boot(get_absolute_time()); }
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
static uint32_t periph_now_ms() { return millis(); }
#endif

static const uint32_t STARTUP_MS = 1000;      // tST: I²C available after power-up / reset / wake-up
static const uint32_t VALID_MS = 3300;        // first valid sample
static const uint32_t DATA_TIMEOUT_MS = 4000; // read(): wait for DATA

// --- Minimal ---

RRH62000Minimal::RRH62000Minimal(RegisterConnection& connection, uint32_t accessIntervalMs)
    : _connection(connection), _interval(accessIntervalMs) {
    _markStartup();
}

uint32_t RRH62000Minimal::_now() { return periph_now_ms(); }

void RRH62000Minimal::_markStartup() {
    uint32_t now = _now();
    // The waits scale with the access interval so a mock (interval 0) does not sleep.
    _lastAccess = now + STARTUP_MS * _interval / 1000 - _interval;
    _validAt = now + VALID_MS * _interval / 1000;
}

void RRH62000Minimal::_waitAccess() {
    int32_t wait = (int32_t)_interval - (int32_t)(_now() - _lastAccess);
    if (wait > 0) DELAY_MS((uint32_t)wait);
}

bool RRH62000Minimal::_read(uint8_t cmd, uint8_t* buf, size_t len) {
    _waitAccess();
    _connection.read(cmd, buf, len);
    _lastAccess = _now();
    if (!_connection.ok()) return _fail(ERR_BUS);
    return true;
}

bool RRH62000Minimal::_write(uint8_t cmd, uint8_t value) {
    _waitAccess();
    _connection.write(cmd, &value, 1);
    _lastAccess = _now();
    if (!_connection.ok()) return _fail(ERR_BUS);
    return true;
}

bool RRH62000Minimal::_readByte(uint8_t cmd, uint8_t& value) {
    return _read(cmd, &value, 1);
}

uint8_t RRH62000Minimal::crc8(const uint8_t* data, size_t len) {
    uint8_t crc = 0xFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

int RRH62000Minimal::decode(const uint8_t* b, RRH62000Data& out) {
    bool allFF = true;
    for (size_t i = 0; i < 36; i++) {
        if (b[i] != 0xFF) { allFF = false; break; }
    }
    if (allFF) return ERR_INVALID;
    if (crc8(b, 36) != b[36]) return ERR_CRC;
    auto word = [b](int i) -> uint16_t { return (uint16_t)((b[i] << 8) | b[i + 1]); };
    uint16_t status = (uint16_t)(b[0] | (b[1] << 8));
    out.status.raw = status;
    out.status.high_concentration = status & 0x01;
    out.status.dust_accumulation = status & 0x02;
    out.status.fan_speed = status & 0x04;
    out.status.fan = status & 0x08;
    out.particles.nc_0_3 = word(2) / 10.0f;
    out.particles.nc_0_5 = word(4) / 10.0f;
    out.particles.nc_1 = word(6) / 10.0f;
    out.particles.nc_2_5 = word(8) / 10.0f;
    out.particles.nc_4 = word(10) / 10.0f;
    out.particles.pm1_ug_m3 = word(12) / 10.0f;
    out.particles.pm2_5_ug_m3 = word(14) / 10.0f;
    out.particles.pm10_ug_m3 = word(16) / 10.0f;
    out.particles.pm1_smoke_ug_m3 = word(18) / 10.0f;
    out.particles.pm2_5_smoke_ug_m3 = word(20) / 10.0f;
    out.particles.pm10_smoke_ug_m3 = word(22) / 10.0f;
    out.temperature_c = (int16_t)word(24) / 100.0f;
    out.humidity_rh = word(26) / 100.0f;
    out.tvoc = (uint32_t)word(28) * 10;
    out.eco2_ppm = word(30);
    out.iaq = word(32) / 100.0f;
    return 0;
}

bool RRH62000Minimal::_readBlock(bool wait, RRH62000Data& out) {
    int32_t valid = (int32_t)(_validAt - _now());
    if (valid > 0) DELAY_MS((uint32_t)valid);
    if (wait) {
        uint32_t start = _now();
        for (;;) {
            uint8_t ready = 0;
            if (!_readByte(CMD_DATA, ready)) return false;
            if (ready == 0x01) break;
            if (_now() - start >= DATA_TIMEOUT_MS) return _fail(ERR_TIMEOUT);
        }
    }
    uint8_t block[BLOCK_LENGTH];
    if (!_read(CMD_READ, block, BLOCK_LENGTH)) return false;
    int err = decode(block, out);
    if (err) return _fail(err);
    _error = 0;
    return true;
}

bool RRH62000Minimal::init() {
    uint8_t fw[2];
    if (!_read(CMD_FWVER, fw, 2)) return false;
    _error = 0;
    return true;
}

bool RRH62000Minimal::read(RRH62000Measurement& out) {
    RRH62000Data d;
    if (!_readBlock(true, d)) return false;
    out.pm1_ug_m3 = d.particles.pm1_ug_m3;
    out.pm2_5_ug_m3 = d.particles.pm2_5_ug_m3;
    out.pm10_ug_m3 = d.particles.pm10_ug_m3;
    out.temperature_c = d.temperature_c;
    out.humidity_rh = d.humidity_rh;
    out.tvoc = d.tvoc;
    out.eco2_ppm = d.eco2_ppm;
    out.iaq = d.iaq;
    return true;
}

// --- Full ---

bool RRH62000Full::read_all(RRH62000Data& out) { return _readBlock(true, out); }

bool RRH62000Full::read_status(RRH62000Status& out) {
    RRH62000Data d;
    if (!_readBlock(false, d)) return false;
    out = d.status;
    return true;
}

bool RRH62000Full::read_particles(RRH62000Particles& out) {
    RRH62000Data d;
    if (!_readBlock(false, d)) return false;
    out = d.particles;
    return true;
}

int RRH62000Full::data_ready() {
    uint8_t v;
    if (!_readByte(CMD_DATA, v)) return _error;
    return v == 0x01 ? 1 : 0;
}

bool RRH62000Full::sleep() { return _write(CMD_SLEEP, 0x00); }

bool RRH62000Full::wake() {
    if (!_write(CMD_SLEEP, 0x80)) return false;
    _markStartup();
    return true;
}

bool RRH62000Full::start_cleaning() { return _write(CMD_CLEAN, 0x01); }

int RRH62000Full::cleaning_done() {
    uint8_t v;
    if (!_readByte(CMD_CSTATUS, v)) return _error;
    return v == 0x01 ? 1 : 0;
}

bool RRH62000Full::soft_reset() {
    _write(CMD_RESET, 0x81);  // the module may not acknowledge while resetting
    _markStartup();
    _error = 0;
    return true;
}

bool RRH62000Full::set_moving_average(uint8_t count) {
    if (count < 1 || count > 60) return _fail(ERR_ARG);
    return _write(CMD_MAVE, count);
}

int RRH62000Full::get_moving_average() {
    uint8_t v;
    if (!_readByte(CMD_MAVE, v)) return _error;
    return v;
}

bool RRH62000Full::set_fan_speed(uint8_t percent) {
    if (percent < 60 || percent > 100) return _fail(ERR_ARG);
    return _write(CMD_SPEEDFAN, percent);
}

int RRH62000Full::get_fan_speed() {
    uint8_t v;
    if (!_readByte(CMD_SPEEDFAN, v)) return _error;
    return v;
}

bool RRH62000Full::set_cleaning_interval(uint32_t seconds) {
    if (seconds > 1814400u || seconds % 30) return _fail(ERR_ARG);
    uint16_t units = (uint16_t)(seconds / 30);
    if (!_write(CMD_TINTC_H, (uint8_t)(units >> 8))) return false;
    return _write(CMD_TINTC_L, (uint8_t)(units & 0xFF));
}

int32_t RRH62000Full::get_cleaning_interval() {
    uint8_t hi, lo;
    if (!_readByte(CMD_TINTC_H, hi)) return _error;
    if (!_readByte(CMD_TINTC_L, lo)) return _error;
    return (int32_t)(((uint16_t)hi << 8) | lo) * 30;
}

bool RRH62000Full::set_cleaning_time(uint8_t seconds) {
    if (seconds > 60) return _fail(ERR_ARG);
    return _write(CMD_TCLEAN, seconds);
}

int RRH62000Full::get_cleaning_time() {
    uint8_t v;
    if (!_readByte(CMD_TCLEAN, v)) return _error;
    return v;
}

bool RRH62000Full::read_mox_resistance(uint32_t& ohms) {
    uint8_t d[4];
    if (!_read(CMD_RMOX, d, 4)) return false;
    ohms = ((uint32_t)d[0] << 24) | ((uint32_t)d[1] << 16) | ((uint32_t)d[2] << 8) | d[3];
    return true;
}

bool RRH62000Full::unique_id(uint8_t uid[6]) { return _read(CMD_UID, uid, 6); }

bool RRH62000Full::algorithm_version(uint8_t& major, uint8_t& minor, uint8_t& patch) {
    uint8_t d[3];
    if (!_read(CMD_ARGVER, d, 3)) return false;
    major = d[0];
    minor = d[1];
    patch = d[2];
    return true;
}

bool RRH62000Full::firmware_version(uint8_t& major, uint8_t& minor) {
    uint8_t d[2];
    if (!_read(CMD_FWVER, d, 2)) return false;
    major = d[0];
    minor = d[1];
    return true;
}
