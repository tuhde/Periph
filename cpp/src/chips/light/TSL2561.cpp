#include "TSL2561.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t TSL2561Minimal::I2C_ADDRESS;
constexpr uint8_t TSL2561Minimal::I2C_ADDRESS_GND;
constexpr uint8_t TSL2561Minimal::I2C_ADDRESS_VDD;
constexpr int TSL2561Minimal::ERR_NONE;
constexpr int TSL2561Minimal::ERR_BUS;
constexpr int TSL2561Minimal::ERR_INIT;
constexpr int TSL2561Minimal::ERR_SATURATED;
constexpr int TSL2561Minimal::ERR_ARG;
constexpr uint8_t TSL2561Minimal::CMD;
constexpr uint8_t TSL2561Minimal::CMD_CLEAR;
constexpr uint8_t TSL2561Minimal::CMD_WORD;
constexpr uint8_t TSL2561Minimal::CMD_DATA_BLOCK;
constexpr uint8_t TSL2561Minimal::REG_CONTROL;
constexpr uint8_t TSL2561Minimal::REG_TIMING;
constexpr uint8_t TSL2561Minimal::REG_THRESH_LOW;
constexpr uint8_t TSL2561Minimal::REG_THRESH_HIGH;
constexpr uint8_t TSL2561Minimal::REG_INTERRUPT;
constexpr uint8_t TSL2561Minimal::REG_ID;
constexpr uint8_t TSL2561Minimal::POWER_ON;
constexpr uint8_t TSL2561Minimal::INTEG_MANUAL;

unsigned TSL2561Minimal::waitPercent = 100;
TSL2561Full* TSL2561Full::_activeInstance = nullptr;

// Per INTEG code 0..2: nominal ms, scale, full-scale count, settle wait (one integration + ±6 % + margin).
static const uint16_t INTEG_MS[3] = { 14, 101, 402 };
static const float INTEG_SCALE[3] = { 0.034f, 0.252f, 1.0f };
static const uint16_t INTEG_FULL_SCALE[3] = { 5047, 37177, 65535 };
static const uint16_t INTEG_SETTLE_MS[3] = { 20, 120, 450 };

// (gain, INTEG) from most to least sensitive, used by autoRange().
static const uint8_t RANGE_GAIN[4] = { 16, 1, 1, 1 };
static const uint8_t RANGE_INTEG[4] = { 2, 2, 1, 0 };
static const int RANGE_COUNT = 4;
static const uint16_t AUTO_MIN_COUNTS = 100;

// Segments: ratio limit, a, b. First segment: a*ch0n - b*ch0n*r^1.4; others: a*ch0n - b*ch1n.
static const float COEFF_TFNCL[4][3] = {
    { 0.50f, 0.0304f, 0.062f }, { 0.61f, 0.0224f, 0.031f }, { 0.80f, 0.0128f, 0.0153f }, { 1.30f, 0.00146f, 0.00112f } };
static const float COEFF_CS[4][3] = {
    { 0.52f, 0.0315f, 0.0593f }, { 0.65f, 0.0229f, 0.0291f }, { 0.80f, 0.0157f, 0.0180f }, { 1.30f, 0.00338f, 0.00260f } };

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

// TSL2561Minimal

void TSL2561Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

void TSL2561Minimal::_wait(unsigned ms) {
    unsigned scaled = (unsigned)((unsigned long)ms * waitPercent / 100);
    if (scaled) _delayMs(scaled);
}

bool TSL2561Minimal::_readByte(uint8_t reg, uint8_t& value) {
    _connection.read(CMD | reg, &value, 1);
    return _connection.ok() ? true : _fail(ERR_BUS);
}

bool TSL2561Minimal::_writeByte(uint8_t reg, uint8_t value) {
    _connection.write(CMD | reg, &value, 1);
    return _connection.ok() ? true : _fail(ERR_BUS);
}

bool TSL2561Minimal::_readWord(uint8_t reg, uint16_t& value) {
    uint8_t buf[2] = { 0, 0 };
    _connection.read(CMD_WORD | reg, buf, 2);
    if (!_connection.ok()) return _fail(ERR_BUS);
    value = (uint16_t)(buf[0] | (buf[1] << 8));
    return true;
}

bool TSL2561Minimal::_writeWord(uint8_t reg, uint16_t value) {
    uint8_t buf[2] = { (uint8_t)(value & 0xFF), (uint8_t)(value >> 8) };
    _connection.write(CMD_WORD | reg, buf, 2);
    return _connection.ok() ? true : _fail(ERR_BUS);
}

bool TSL2561Minimal::_writeTiming(bool manual) {
    uint8_t value = (uint8_t)((_gain == 16 ? 0x10 : 0x00) | _integ | (manual ? 0x08 : 0x00));
    return _writeByte(REG_TIMING, value);
}

bool TSL2561Minimal::_readChannels(uint16_t& ch0, uint16_t& ch1) {
    uint8_t buf[4] = { 0, 0, 0, 0 };
    _connection.read(CMD_DATA_BLOCK, buf, 4);
    if (!_connection.ok()) return _fail(ERR_BUS);
    ch0 = (uint16_t)(buf[0] | (buf[1] << 8));
    ch1 = (uint16_t)(buf[2] | (buf[3] << 8));
    return true;
}

uint16_t TSL2561Minimal::_fullScale() const { return _integ < 3 ? INTEG_FULL_SCALE[_integ] : 65535; }

uint16_t TSL2561Minimal::_integrationMs() const { return _integ < 3 ? INTEG_MS[_integ] : 0; }

unsigned TSL2561Minimal::_settleMs() const { return _integ < 3 ? INTEG_SETTLE_MS[_integ] : 0; }

float TSL2561Minimal::calculateLux(uint16_t ch0, uint16_t ch1, uint8_t gain, uint16_t integrationMs, Package package) {
    int code = integrationMs == 14 ? 0 : integrationMs == 101 ? 1 : 2;
    float factor = (16.0f / (gain == 16 ? 16.0f : 1.0f)) / INTEG_SCALE[code];
    float ch0n = ch0 * factor;
    float ch1n = ch1 * factor;
    if (ch0n <= 0.0f) return 0.0f;
    float r = ch1n / ch0n;
    const float (*c)[3] = package == PACKAGE_CS ? COEFF_CS : COEFF_TFNCL;
    float lux;
    if (r <= c[0][0]) lux = c[0][1] * ch0n - c[0][2] * ch0n * powf(r, 1.4f);
    else if (r <= c[1][0]) lux = c[1][1] * ch0n - c[1][2] * ch1n;
    else if (r <= c[2][0]) lux = c[2][1] * ch0n - c[2][2] * ch1n;
    else if (r <= c[3][0]) lux = c[3][1] * ch0n - c[3][2] * ch1n;
    else lux = 0.0f;
    return lux > 0.0f ? lux : 0.0f;
}

float TSL2561Minimal::_luxFrom(uint16_t ch0, uint16_t ch1) {
    uint16_t limit = _fullScale();
    if (ch0 >= limit || ch1 >= limit) {
        _error = ERR_SATURATED;
        return NAN;
    }
    _error = ERR_NONE;
    return calculateLux(ch0, ch1, _gain, _integrationMs(), _package);
}

bool TSL2561Minimal::init() {
    _gain = 1;
    _integ = 2;
    if (!_writeByte(REG_CONTROL, POWER_ON)) return false;
    uint8_t control = 0;
    if (!_readByte(REG_CONTROL, control)) return false;
    if ((control & 0x03) != POWER_ON) return _fail(ERR_INIT);
    if (!_writeTiming()) return false;
    _wait(_settleMs());
    _error = ERR_NONE;
    return true;
}

float TSL2561Minimal::readLux() {
    uint16_t ch0, ch1;
    if (!_readChannels(ch0, ch1)) return NAN;
    return _luxFrom(ch0, ch1);
}

// TSL2561Full

bool TSL2561Full::readRaw(uint16_t& ch0, uint16_t& ch1) {
    if (!_readChannels(ch0, ch1)) return false;
    _error = ERR_NONE;
    return true;
}

bool TSL2561Full::setGain(uint8_t gain) {
    if (gain != 1 && gain != 16) return _fail(ERR_ARG);
    _gain = gain;
    return _writeTiming();
}

bool TSL2561Full::setIntegrationTime(uint16_t ms) {
    if (ms == 14) _integ = 0;
    else if (ms == 101) _integ = 1;
    else if (ms == 402) _integ = 2;
    else return _fail(ERR_ARG);
    return _writeTiming();
}

bool TSL2561Full::readLuxRaw(float& lux, uint16_t& ch0, uint16_t& ch1) {
    if (!_readChannels(ch0, ch1)) return false;
    lux = _luxFrom(ch0, ch1);
    return _error == ERR_NONE;
}

bool TSL2561Full::_applyRange(int index) {
    unsigned oldWait = _settleMs();
    _gain = RANGE_GAIN[index];
    _integ = RANGE_INTEG[index];
    if (!_writeTiming()) return false;
    _wait(oldWait + _settleMs());
    return true;
}

float TSL2561Full::autoRange() {
    int index = -1;
    for (int i = 0; i < RANGE_COUNT; i++)
        if (RANGE_GAIN[i] == _gain && RANGE_INTEG[i] == _integ) index = i;
    if (index < 0) {
        index = 1;
        if (!_applyRange(index)) return NAN;
    }
    uint16_t ch0 = 0, ch1 = 0;
    for (int step = 0; step < 2 * RANGE_COUNT; step++) {
        if (!_readChannels(ch0, ch1)) return NAN;
        uint16_t limit = _fullScale();
        bool saturated = ch0 >= limit || ch1 >= limit;
        if (saturated && index < RANGE_COUNT - 1) index++;
        else if (!saturated && ch0 < AUTO_MIN_COUNTS && index > 0) index--;
        else return _luxFrom(ch0, ch1);
        if (!_applyRange(index)) return NAN;
    }
    if (!_readChannels(ch0, ch1)) return NAN;
    return _luxFrom(ch0, ch1);
}

bool TSL2561Full::powerUp() { return _writeByte(REG_CONTROL, POWER_ON); }

bool TSL2561Full::powerDown() { return _writeByte(REG_CONTROL, 0x00); }

bool TSL2561Full::isPowered() {
    uint8_t control = 0;
    if (!_readByte(REG_CONTROL, control)) return false;
    return (control & 0x03) == POWER_ON;
}

bool TSL2561Full::startManual() {
    _integ = INTEG_MANUAL;
    return _writeTiming(true);
}

bool TSL2561Full::stopManual() {
    _integ = INTEG_MANUAL;
    return _writeTiming(false);
}

bool TSL2561Full::setThresholds(uint16_t low, uint16_t high) {
    if (!_writeWord(REG_THRESH_LOW, low)) return false;
    return _writeWord(REG_THRESH_HIGH, high);
}

bool TSL2561Full::getThresholds(uint16_t& low, uint16_t& high) {
    if (!_readWord(REG_THRESH_LOW, low)) return false;
    return _readWord(REG_THRESH_HIGH, high);
}

bool TSL2561Full::configureInterrupt(uint8_t mode, uint8_t persist) {
    if (mode > 2 || persist > 15) return _fail(ERR_ARG);
    return _writeByte(REG_INTERRUPT, (uint8_t)((mode << 4) | persist));
}

int TSL2561Full::readId() {
    uint8_t id = 0;
    if (!_readByte(REG_ID, id)) return -1;
    return id;
}

bool TSL2561Full::pollInterrupt() {
    uint16_t ch0, ch1, low, high;
    if (!_readChannels(ch0, ch1)) return false;
    if (!getThresholds(low, high)) return false;
    return ch0 <= low || ch0 > high;
}

bool TSL2561Full::clearInterrupt() {
    uint8_t none = 0;
    _connection.write(CMD_CLEAR, &none, 0);
    return _connection.ok() ? true : _fail(ERR_BUS);
}

void TSL2561Full::onInterrupt(void (*callback)(uint16_t), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&TSL2561Full::_edgeTrampoline, InputPin::kFalling);
}

void TSL2561Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&TSL2561Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void TSL2561Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void TSL2561Full::_handleEdge() {
    uint16_t ch0, ch1;
    if (!_readChannels(ch0, ch1)) return;
    if (_callback) _callback(ch0);
    clearInterrupt();
}

void TSL2561Full::service() {
    if (!_callback || !pollInterrupt()) return;
    uint16_t ch0, ch1;
    if (_readChannels(ch0, ch1)) _callback(ch0);
}
