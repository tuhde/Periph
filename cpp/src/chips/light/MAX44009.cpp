#include "MAX44009.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t MAX44009Minimal::I2C_ADDRESS;
constexpr uint8_t MAX44009Minimal::I2C_ADDRESS_A0_HIGH;
constexpr uint8_t MAX44009Minimal::REG_INT_STATUS;
constexpr uint8_t MAX44009Minimal::REG_INT_ENABLE;
constexpr uint8_t MAX44009Minimal::REG_CONFIG;
constexpr uint8_t MAX44009Minimal::REG_LUX_HIGH;
constexpr uint8_t MAX44009Minimal::REG_LUX_LOW;
constexpr uint8_t MAX44009Minimal::REG_UPPER;
constexpr uint8_t MAX44009Minimal::REG_LOWER;
constexpr uint8_t MAX44009Minimal::REG_TIMER;
constexpr uint8_t MAX44009Minimal::CFG_CONT;
constexpr uint8_t MAX44009Minimal::CFG_MANUAL;
constexpr uint8_t MAX44009Minimal::CFG_CDR;
constexpr uint8_t MAX44009Minimal::CFG_TIM_MASK;
constexpr uint8_t MAX44009Full::SOURCE_THRESHOLD;

unsigned MAX44009Minimal::initWaitMs = 900;
MAX44009Full* MAX44009Full::_activeInstance = nullptr;

static const float LUX_PER_LSB = 0.045f;
static const float MAX_LUX = 188006.4f;
static const float INTEGRATION_MS[8] = { 800.0f, 400.0f, 200.0f, 100.0f, 50.0f, 25.0f, 12.5f, 6.25f };

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

// MAX44009Minimal

MAX44009Minimal::MAX44009Minimal(RegisterConnection& connection) : _connection(connection) {
    _writeReg(REG_INT_ENABLE, 0x00);
    _writeReg(REG_CONFIG, 0x03);
    _writeReg(REG_UPPER, 0xFF);
    _writeReg(REG_LOWER, 0x00);
    _writeReg(REG_TIMER, 0xFF);
    _readReg(REG_INT_STATUS);
    if (initWaitMs) _delayMs(initWaitMs);
}

void MAX44009Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

uint8_t MAX44009Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void MAX44009Minimal::_writeReg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

void MAX44009Minimal::_readLuxRegs(uint8_t& exponent, uint8_t& mantissa) {
    uint8_t high = _readReg(REG_LUX_HIGH);
    uint8_t low = _readReg(REG_LUX_LOW);
    uint8_t high2 = _readReg(REG_LUX_HIGH);
    if (high2 != high) {            // a new conversion arrived: read once more
        high = high2;
        low = _readReg(REG_LUX_LOW);
    }
    exponent = high >> 4;
    mantissa = (uint8_t)(((high & 0x0F) << 4) | (low & 0x0F));
    _lastExponent = exponent;
}

float MAX44009Minimal::_luxFrom(uint8_t exponent, uint8_t mantissa) {
    if (exponent >= 15) return MAX_LUX;
    return (float)(1u << exponent) * (float)mantissa * LUX_PER_LSB;
}

float MAX44009Minimal::lux() {
    uint8_t e, m;
    _readLuxRegs(e, m);
    return _luxFrom(e, m);
}

// MAX44009Full

MAX44009Full::MAX44009Full(RegisterConnection& connection) : MAX44009Minimal(connection) {}

bool MAX44009Full::readRaw(uint8_t& exponent, uint8_t& mantissa) {
    _readLuxRegs(exponent, mantissa);
    return exponent >= 15;
}

float MAX44009Full::resolution() const {
    uint8_t e = _lastExponent > 14 ? 14 : _lastExponent;
    return (float)(1u << e) * LUX_PER_LSB;
}

void MAX44009Full::_updateConfig(uint8_t setBits, uint8_t clearBits) {
    uint8_t cfg = _readReg(REG_CONFIG);
    _writeReg(REG_CONFIG, (uint8_t)((cfg & ~clearBits) | setBits));
}

void MAX44009Full::setContinuous(bool continuous) {
    _updateConfig(continuous ? CFG_CONT : 0, CFG_CONT);
}

void MAX44009Full::setAutomatic() { _updateConfig(0, CFG_MANUAL); }

void MAX44009Full::setManual(float integrationMs, bool divideBy8) {
    uint8_t best = 0;
    float bestErr = fabsf(INTEGRATION_MS[0] - integrationMs);
    for (uint8_t tim = 1; tim < 8; ++tim) {
        float err = fabsf(INTEGRATION_MS[tim] - integrationMs);
        if (err < bestErr) { best = tim; bestErr = err; }
    }
    uint8_t cfg = _readReg(REG_CONFIG);
    cfg = (uint8_t)((cfg & CFG_CONT) | CFG_MANUAL | (divideBy8 ? CFG_CDR : 0) | best);
    _writeReg(REG_CONFIG, cfg);
}

MAX44009Full::Config MAX44009Full::getConfig() {
    uint8_t cfg = _readReg(REG_CONFIG);
    Config c;
    c.continuous = (cfg & CFG_CONT) != 0;
    c.manual = (cfg & CFG_MANUAL) != 0;
    c.divideBy8 = (cfg & CFG_CDR) != 0;
    c.integrationMs = INTEGRATION_MS[cfg & CFG_TIM_MASK];
    return c;
}

uint8_t MAX44009Full::_encodeThreshold(float lux) {
    if (lux < 11.5f) {
        int m = (int)(lux / (16.0f * LUX_PER_LSB) + 0.5f);
        return (uint8_t)(m > 15 ? 15 : (m < 0 ? 0 : m));
    }
    uint8_t exponent = 1;
    float mantissa = 0;
    for (exponent = 1; exponent <= 14; ++exponent) {
        mantissa = lux / ((float)(1u << exponent) * LUX_PER_LSB);
        if (mantissa < 256.0f) break;
    }
    if (exponent > 14) exponent = 14;
    int m = (int)mantissa;
    if (m < 128) m = 128;
    if (m > 255) m = 255;
    uint8_t code = (uint8_t)((exponent << 4) | (m >> 4));
    return code > 0xEF ? 0xEF : code;
}

float MAX44009Full::_decodeThreshold(uint8_t code, bool upper) {
    uint8_t exponent = code >> 4;
    uint8_t nibble = code & 0x0F;
    uint8_t lowBits = upper ? 0x0F : 0x00;
    return (float)(1u << exponent) * (float)((nibble << 4) | lowBits) * LUX_PER_LSB;
}

bool MAX44009Full::setThresholds(float lowLux, float highLux) {
    if (lowLux > highLux) return false;
    _writeReg(REG_LOWER, _encodeThreshold(lowLux));
    _writeReg(REG_UPPER, _encodeThreshold(highLux));
    return true;
}

void MAX44009Full::getThresholds(float& lowLux, float& highLux) {
    uint8_t low = _readReg(REG_LOWER);
    uint8_t high = _readReg(REG_UPPER);
    lowLux = _decodeThreshold(low, false);
    highLux = _decodeThreshold(high, true);
}

void MAX44009Full::setThresholdDelay(float seconds) {
    int v = (int)(seconds / 0.1f + 0.5f);
    _writeReg(REG_TIMER, (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v)));
}

float MAX44009Full::getThresholdDelay() { return (float)_readReg(REG_TIMER) * 0.1f; }

void MAX44009Full::enableInterrupt() { _writeReg(REG_INT_ENABLE, 0x01); }

void MAX44009Full::disableInterrupt() { _writeReg(REG_INT_ENABLE, 0x00); }

uint8_t MAX44009Full::pollInterrupt() { return _readReg(REG_INT_STATUS) & 0x01; }

void MAX44009Full::onInterrupt(void (*callback)(uint8_t), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&MAX44009Full::_edgeTrampoline, InputPin::kFalling);
}

void MAX44009Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&MAX44009Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void MAX44009Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->service();
}

void MAX44009Full::service() {
    uint8_t status = pollInterrupt();
    if (status && _callback) _callback(status);
}
