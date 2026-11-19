#include "MPL115A2.h"
#include <stdlib.h>
#include <math.h>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay(unsigned long ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

MPL115A2Minimal::MPL115A2Minimal(RegisterConnection& connection)
    : _connection(connection) {
    _load_coefficients();
}

void MPL115A2Minimal::_load_coefficients() {
    uint8_t buf[8];
    _connection.read(REG_COEF, buf, 8);
    _a0  = (int16_t)((buf[0] << 8) | buf[1]) / 8.0f;
    _b1  = (int16_t)((buf[2] << 8) | buf[3]) / 8192.0f;
    _b2  = (int16_t)((buf[4] << 8) | buf[5]) / 16384.0f;
    _c12 = (int16_t)((buf[6] << 8) | buf[7]) / 16777216.0f;
}

void MPL115A2Minimal::_convert(uint16_t& padc, uint16_t& tadc) {
    uint8_t zero = 0x00;
    _connection.write(REG_CONVERT, &zero, 1);
    delay(CONVERSION_MS);
    uint8_t buf[4];
    _connection.read(REG_PADC, buf, 4);
    padc = (uint16_t)(((buf[0] << 8) | buf[1]) >> 6);
    tadc = (uint16_t)(((buf[2] << 8) | buf[3]) >> 6);
}

float MPL115A2Minimal::_compensate(uint16_t padc, uint16_t tadc) const {
    float pcomp = _a0 + (_b1 + _c12 * tadc) * padc + _b2 * tadc;
    return (pcomp * (115.0f - 50.0f) / 1023.0f + 50.0f) * 1000.0f;
}

float MPL115A2Minimal::_temperature_c(uint16_t tadc) {
    return 25.0f + ((float)tadc - 498.0f) / -5.35f;
}

float MPL115A2Minimal::read_pressure() {
    uint16_t padc, tadc;
    _convert(padc, tadc);
    return _compensate(padc, tadc);
}

float MPL115A2Minimal::read_temperature() {
    uint16_t padc, tadc;
    _convert(padc, tadc);
    return _temperature_c(tadc);
}

MPL115A2Full::MPL115A2Full(RegisterConnection& connection)
    : MPL115A2Minimal(connection) {}

void MPL115A2Full::read(float& pressure_pa, float& temperature_c) {
    uint16_t padc, tadc;
    _convert(padc, tadc);
    pressure_pa   = _compensate(padc, tadc);
    temperature_c = _temperature_c(tadc);
}

void MPL115A2Full::read_raw(uint16_t& padc, uint16_t& tadc) {
    _convert(padc, tadc);
}

void MPL115A2Full::coefficients(float& a0, float& b1, float& b2, float& c12) const {
    a0 = _a0;
    b1 = _b1;
    b2 = _b2;
    c12 = _c12;
}

void MPL115A2Full::reload_coefficients() {
    _load_coefficients();
}

float MPL115A2Full::compensate(uint16_t padc, uint16_t tadc) const {
    return _compensate(padc, tadc);
}

float MPL115A2Full::pressure_hpa() {
    return read_pressure() / 100.0f;
}

float MPL115A2Full::altitude(float sea_level_pa) {
    float p = read_pressure();
    return 44330.0f * (1.0f - powf(p / sea_level_pa, 1.0f / 5.255f));
}
