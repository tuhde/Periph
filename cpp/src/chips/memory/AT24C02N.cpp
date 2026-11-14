#include "AT24C02N.h"

#if defined(__linux__)
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1)
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
#define DELAY_MS(ms) HAL_Delay(ms)
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

// --- Minimal ---

AT24C02NMinimal::AT24C02NMinimal(RegisterConnection& connection)
    : _connection(connection) {}

bool AT24C02NMinimal::_wait_ready(uint16_t timeout_ms) {
    for (uint16_t t = 0;; t++) {
        static const uint8_t none = 0;
        _connection.write(0, &none, 0);
        if (_connection.ok()) return true;
        if (t >= timeout_ms) return false;
        DELAY_MS(1);
    }
}

uint8_t AT24C02NMinimal::read_byte(uint16_t address) {
    uint8_t val = 0;
    _connection.read(address, &val, 1);
    return val;
}

bool AT24C02NMinimal::write_byte(uint16_t address, uint8_t value) {
    _connection.write(address, &value, 1);
    return _wait_ready(WRITE_TIMEOUT_MS);
}

// --- Full ---

AT24C02NFull::AT24C02NFull(RegisterConnection& connection)
    : AT24C02NMinimal(connection) {}

bool AT24C02NFull::_in_range(uint32_t address, uint32_t length) {
    return address < SIZE && address + length <= SIZE;
}

uint8_t AT24C02NFull::_chunk_len(uint32_t address, uint32_t remaining) const {
    uint32_t room = PAGE - (address % PAGE);
    uint32_t n = remaining < room ? remaining : room;
    return (uint8_t)(n < _max_chunk ? n : _max_chunk);
}

void AT24C02NFull::set_max_chunk(uint8_t value) {
    if (value < 1) value = 1;
    if (value > 32) value = 32;
    _max_chunk = value;
}

bool AT24C02NFull::wait_ready(uint16_t timeout_ms) {
    return _wait_ready(timeout_ms);
}

bool AT24C02NFull::read(uint16_t address, uint8_t* buf, uint32_t length) {
    if (length == 0) return true;
    if (!_in_range(address, length)) return false;
    uint32_t a = address;
    while (length > 0) {
        uint8_t n = length > _max_chunk ? _max_chunk : (uint8_t)length;
        _connection.read(a, buf, n);
        a += n;
        buf += n;
        length -= n;
    }
    return true;
}

bool AT24C02NFull::write(uint16_t address, const uint8_t* data, uint32_t length) {
    if (length == 0) return true;
    if (!_in_range(address, length)) return false;
    uint32_t a = address;
    while (length > 0) {
        uint8_t n = _chunk_len(a, length);
        _connection.write(a, data, n);
        if (!_wait_ready(WRITE_TIMEOUT_MS)) return false;
        a += n;
        data += n;
        length -= n;
    }
    return true;
}

bool AT24C02NFull::write_page(uint16_t address, const uint8_t* data, uint16_t length) {
    if (length == 0) return true;
    if (length > PAGE || (address % PAGE) + length > PAGE) return false;
    return write(address, data, length);
}

bool AT24C02NFull::fill(uint16_t address, uint32_t length, uint8_t value) {
    if (length == 0) return true;
    if (!_in_range(address, length)) return false;
    uint8_t chunk[32];
    for (uint8_t i = 0; i < 32; i++) chunk[i] = value;
    uint32_t a = address;
    while (length > 0) {
        uint8_t n = _chunk_len(a, length);
        _connection.write(a, chunk, n);
        if (!_wait_ready(WRITE_TIMEOUT_MS)) return false;
        a += n;
        length -= n;
    }
    return true;
}
