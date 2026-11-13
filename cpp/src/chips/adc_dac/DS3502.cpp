#include "DS3502.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t DS3502Minimal::I2C_ADDRESS;
constexpr uint8_t DS3502Minimal::REG_WR;
constexpr uint8_t DS3502Minimal::REG_CR;
constexpr uint8_t DS3502Minimal::CR_MODE_SRAM;
constexpr uint8_t DS3502Minimal::CR_MODE_EEPROM;
constexpr uint8_t DS3502Minimal::MAX_CODE;

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) ? pdMS_TO_TICKS(ms) : 1); }
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

// DS3502Minimal

DS3502Minimal::DS3502Minimal(RegisterConnection& connection, float vHigh, float vLow)
    : _connection(connection), _vHigh(vHigh), _vLow(vLow), _code(0) {
    _writeReg(REG_CR, CR_MODE_SRAM);
    if (!_connection.ok()) {        // still in the power-up recall (tD <= 3 ms): retry once
        _delayMs(3);
        _writeReg(REG_CR, CR_MODE_SRAM);
    }
    _code = _readReg(REG_WR) & MAX_CODE;
}

void DS3502Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

uint8_t DS3502Minimal::_clampCode(int code) {
    if (code < 0) return 0;
    if (code > MAX_CODE) return MAX_CODE;
    return (uint8_t)code;
}

uint8_t DS3502Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void DS3502Minimal::_writeReg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

void DS3502Minimal::_writeCode(int code) {
    uint8_t c = _clampCode(code);
    _writeReg(REG_WR, c);
    _code = c;
}

uint8_t DS3502Minimal::_readCode() {
    _code = _readReg(REG_WR) & MAX_CODE;
    return _code;
}

void DS3502Minimal::setWiper(int code) { _writeCode(code); }

uint8_t DS3502Minimal::getWiper() { return _readCode(); }

void DS3502Minimal::setVoltage(float volts) {
    float span = _vHigh - _vLow;
    if (span == 0.0f) { _writeCode(0); return; }
    _writeCode((int)((volts - _vLow) / span * (float)MAX_CODE + 0.5f));
}

float DS3502Minimal::getVoltage() {
    return _vLow + (_vHigh - _vLow) * (float)_readCode() / (float)MAX_CODE;
}

// DS3502Full

DS3502Full::DS3502Full(RegisterConnection& connection, float vHigh, float vLow)
    : DS3502Minimal(connection, vHigh, vLow) {}

void DS3502Full::setRatio(float ratio) {
    _writeCode((int)(ratio * (float)MAX_CODE + 0.5f));
}

float DS3502Full::getRatio() { return (float)_readCode() / (float)MAX_CODE; }

float DS3502Full::getResistance(char terminal) {
    if (terminal != 'L' && terminal != 'H') return -1.0f;
    float code = (float)_readCode();
    if (terminal == 'L') return code / (float)MAX_CODE * 10000.0f;
    return ((float)MAX_CODE - code) / (float)MAX_CODE * 10000.0f;
}

uint8_t DS3502Full::increment(int steps) {
    uint8_t code = _clampCode((int)_readCode() + steps);
    _writeCode(code);
    return code;
}

bool DS3502Full::storeWiper(int code) {
    uint8_t c = code < 0 ? _readCode() : _clampCode(code);
    _writeReg(REG_CR, CR_MODE_EEPROM);
    _writeReg(REG_WR, c);
    _code = c;
    bool ok = waitReady();
    _writeReg(REG_CR, CR_MODE_SRAM);
    return ok;
}

void DS3502Full::setMode(bool eeprom) {
    _writeReg(REG_CR, eeprom ? CR_MODE_EEPROM : CR_MODE_SRAM);
}

bool DS3502Full::getMode() { return (_readReg(REG_CR) & CR_MODE_SRAM) == 0; }

bool DS3502Full::waitReady(float timeoutMs) {
    unsigned attempts = (unsigned)timeoutMs + 1;
    for (unsigned i = 0; i < attempts; i++) {
        _readReg(REG_CR);
        if (_connection.ok()) return true;
        _delayMs(1);
    }
    return false;
}

void DS3502Full::setEndpoints(float vHigh, float vLow) {
    _vHigh = vHigh;
    _vLow = vLow;
}
