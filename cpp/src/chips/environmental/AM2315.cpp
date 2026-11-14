#include "AM2315.h"
#include <math.h>

#ifdef __linux__
#include <unistd.h>
#include <time.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
static uint32_t periph_millis() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
static uint32_t periph_millis() { return k_uptime_get_32(); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
static uint32_t periph_millis() { return (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
static uint32_t periph_millis() { return HAL_GetTick(); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
static uint32_t periph_millis() { return to_ms_since_boot(get_absolute_time()); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
static uint32_t periph_millis() { return millis(); }
#endif

// --- Minimal ---

AM2315Minimal::AM2315Minimal(Connection& connection)
    : _connection(connection), _last(periph_millis()) {}

uint16_t AM2315Minimal::_crc16(const uint8_t* data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) crc = (crc & 1) ? (uint16_t)((crc >> 1) ^ 0xA001) : (uint16_t)(crc >> 1);
    }
    return crc;
}

void AM2315Minimal::_wait_interval() {
    if (_interval_ms == 0) return;
    uint32_t elapsed = periph_millis() - _last;
    if (elapsed < _interval_ms) periph_delay_ms(_interval_ms - elapsed);
}

void AM2315Minimal::_wake() {
    uint8_t dummy = 0x00;
    _connection.write(&dummy, 1);                      // the sleeping sensor NACKs this; ignored
    periph_delay_ms(1);
}

bool AM2315Minimal::_exchange(const uint8_t* frame, size_t frame_len, uint8_t* resp, size_t resp_len) {
    _wait_interval();
    _wake();
    _connection.write(frame, frame_len);
    bool ok = _connection.ok();
    if (ok) {
        periph_delay_ms(2);
        _connection.read(resp, resp_len);
        ok = _connection.ok();
    }
    _last = periph_millis();
    return ok;
}

bool AM2315Minimal::_check(const uint8_t* resp, uint8_t function, size_t resp_len) {
    _last_error = 0;
    if (resp[0] & 0x80) { _last_error = resp[0]; return false; }
    if (resp[0] != function) return false;
    uint16_t crc = (uint16_t)(resp[resp_len - 2] | (resp[resp_len - 1] << 8));
    return _crc16(resp, resp_len - 2) == crc;
}

bool AM2315Minimal::_read_registers(uint8_t start, uint8_t count, uint8_t* out) {
    uint8_t frame[3] = {FUNC_READ, start, count};
    uint8_t resp[14];
    size_t resp_len = (size_t)count + 4;
    for (uint8_t attempt = 0; attempt < 2; attempt++) {
        if (_exchange(frame, 3, resp, resp_len) && _check(resp, FUNC_READ, resp_len) && resp[1] == count) {
            for (uint8_t i = 0; i < count; i++) out[i] = resp[2 + i];
            return true;
        }
        if (_last_error) return false;
    }
    return false;
}

float AM2315Minimal::_to_temperature(uint16_t raw) {
    float t = (raw & 0x7FFF) / 10.0f;
    return (raw & 0x8000) ? -t : t;
}

bool AM2315Minimal::read(float& temperature_c, float& humidity_pct) {
    uint8_t d[4];
    if (!_read_registers(0x00, 4, d)) return false;
    humidity_pct = ((d[0] << 8) | d[1]) / 10.0f;
    temperature_c = _to_temperature((uint16_t)((d[2] << 8) | d[3]));
    return true;
}

// --- Full ---

AM2315Full::AM2315Full(Connection& connection)
    : AM2315Minimal(connection) {}

float AM2315Full::read_temperature() {
    uint8_t d[2];
    return _read_registers(0x02, 2, d) ? _to_temperature((uint16_t)((d[0] << 8) | d[1])) : NAN;
}

float AM2315Full::read_humidity() {
    uint8_t d[2];
    return _read_registers(0x00, 2, d) ? ((d[0] << 8) | d[1]) / 10.0f : NAN;
}

bool AM2315Full::read_raw(uint16_t& humidity_raw, uint16_t& temperature_raw) {
    uint8_t d[4];
    if (!_read_registers(0x00, 4, d)) return false;
    humidity_raw = (uint16_t)((d[0] << 8) | d[1]);
    temperature_raw = (uint16_t)((d[2] << 8) | d[3]);
    return true;
}

bool AM2315Full::read_fresh(float& temperature_c, float& humidity_pct) {
    return read(temperature_c, humidity_pct) && read(temperature_c, humidity_pct);
}

bool AM2315Full::read_registers(uint8_t start, uint8_t count, uint8_t* out) {
    if (count < 1 || count > 10 || start > 0x1F || (unsigned)start + count > 0x20) return false;
    return _read_registers(start, count, out);
}

bool AM2315Full::write_registers(uint8_t start, const uint8_t* data, uint8_t len) {
    if (len < 1 || len > 4 || start < 0x10 || (unsigned)start + len > 0x14) return false;
    uint8_t frame[9];
    frame[0] = FUNC_WRITE;
    frame[1] = start;
    frame[2] = len;
    for (uint8_t i = 0; i < len; i++) frame[3 + i] = data[i];
    uint16_t crc = _crc16(frame, (size_t)3 + len);
    frame[3 + len] = (uint8_t)(crc & 0xFF);
    frame[4 + len] = (uint8_t)(crc >> 8);
    uint8_t resp[5];
    for (uint8_t attempt = 0; attempt < 2; attempt++) {
        if (_exchange(frame, (size_t)5 + len, resp, 5) && _check(resp, FUNC_WRITE, 5)
            && resp[1] == start && resp[2] == len) return true;
        if (_last_error) return false;
    }
    return false;
}

bool AM2315Full::read_device_info(uint16_t& model, uint8_t& version, uint32_t& device_id) {
    uint8_t d[7];
    if (!_read_registers(0x08, 7, d)) return false;
    model = (uint16_t)((d[0] << 8) | d[1]);
    version = d[2];
    device_id = ((uint32_t)d[3] << 24) | ((uint32_t)d[4] << 16) | ((uint32_t)d[5] << 8) | d[6];
    return true;
}

bool AM2315Full::read_user_registers(uint16_t& user1, uint16_t& user2) {
    uint8_t d[4];
    if (!_read_registers(0x10, 4, d)) return false;
    user1 = (uint16_t)((d[0] << 8) | d[1]);
    user2 = (uint16_t)((d[2] << 8) | d[3]);
    return true;
}

bool AM2315Full::write_user_registers(uint16_t user1, uint16_t user2) {
    uint8_t d[4] = {(uint8_t)(user1 >> 8), (uint8_t)user1, (uint8_t)(user2 >> 8), (uint8_t)user2};
    return write_registers(0x10, d, 4);
}

void AM2315Full::wake() { _wake(); }
