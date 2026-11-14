#include "TMP102.h"

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

namespace {
const float LSB_C = 0.0625f;
const float LIMIT_MIN_C = -55.0f;
const float LIMIT_MAX_C = 150.0f;
const float RATES[4] = { 0.25f, 1.0f, 4.0f, 8.0f };
const uint8_t FAULTS[4] = { 1, 2, 4, 6 };

int16_t signExtend(uint16_t value, uint8_t bits) {
    uint16_t sign = (uint16_t)1u << (bits - 1);
    return (int16_t)((value ^ sign) - sign);
}
}  // namespace

// TMP102Minimal

TMP102Minimal::TMP102Minimal(RegisterConnection& connection) : _connection(connection) {}

uint16_t TMP102Minimal::_readReg(uint8_t reg) {
    uint8_t buf[2];
    _connection.read(reg, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void TMP102Minimal::_writeReg(uint8_t reg, uint16_t value) {
    uint8_t buf[2] = { (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _connection.write(reg, buf, 2);
}

int16_t TMP102Minimal::_decodeRaw(uint16_t raw16) {
    if (raw16 & 0x0001) return signExtend((uint16_t)(raw16 >> 3), 13);
    return signExtend((uint16_t)(raw16 >> 4), 12);
}

float TMP102Minimal::readTemperature() {
    return (float)_decodeRaw(_readReg(REG_TEMPERATURE)) * LSB_C;
}

// TMP102Full

TMP102Full* TMP102Full::_activeInstance = nullptr;

TMP102Full::TMP102Full(RegisterConnection& connection) : TMP102Minimal(connection) {}

int16_t TMP102Full::readRaw() { return _decodeRaw(_readReg(REG_TEMPERATURE)); }

uint16_t TMP102Full::readConfig() { return _readReg(REG_CONFIG); }

void TMP102Full::writeConfig(uint16_t value) {
    _writeReg(REG_CONFIG, (uint16_t)((value & CFG_WRITABLE) | CFG_RESERVED));
}

void TMP102Full::_updateConfig(uint16_t clear, uint16_t set) {
    writeConfig((uint16_t)((readConfig() & ~clear) | set));
}

bool TMP102Full::setConversionRate(float hz) {
    for (uint8_t i = 0; i < 4; i++) {
        if (hz == RATES[i]) {
            _updateConfig(CFG_CR_MASK, (uint16_t)(i << CFG_CR_SHIFT));
            return true;
        }
    }
    return false;
}

void TMP102Full::setExtendedMode(bool on) { _updateConfig(CFG_EM, on ? CFG_EM : 0); }

void TMP102Full::_setLimit(uint8_t reg, float celsius) {
    bool extended = (readConfig() & CFG_EM) != 0;
    if (celsius < LIMIT_MIN_C) celsius = LIMIT_MIN_C;
    if (celsius > LIMIT_MAX_C) celsius = LIMIT_MAX_C;
    float scaled = celsius / LSB_C;
    int32_t counts = (int32_t)(scaled >= 0 ? scaled + 0.5f : scaled - 0.5f);
    if (extended) {
        _writeReg(reg, (uint16_t)((counts << 3) & 0xFFF8));
    } else {
        if (counts > 2047) counts = 2047;
        _writeReg(reg, (uint16_t)((counts << 4) & 0xFFF0));
    }
}

float TMP102Full::_getLimit(uint8_t reg) {
    bool extended = (readConfig() & CFG_EM) != 0;
    uint16_t raw16 = _readReg(reg);
    int16_t counts = extended ? signExtend((uint16_t)(raw16 >> 3), 13)
                              : signExtend((uint16_t)(raw16 >> 4), 12);
    return (float)counts * LSB_C;
}

void TMP102Full::setTHigh(float celsius) { _setLimit(REG_T_HIGH, celsius); }
float TMP102Full::getTHigh() { return _getLimit(REG_T_HIGH); }
void TMP102Full::setTLow(float celsius) { _setLimit(REG_T_LOW, celsius); }
float TMP102Full::getTLow() { return _getLimit(REG_T_LOW); }

bool TMP102Full::setFaultQueue(uint8_t count) {
    for (uint8_t i = 0; i < 4; i++) {
        if (count == FAULTS[i]) {
            _updateConfig(CFG_F_MASK, (uint16_t)(i << CFG_F_SHIFT));
            return true;
        }
    }
    return false;
}

void TMP102Full::setPolarity(bool activeHigh) { _updateConfig(CFG_POL, activeHigh ? CFG_POL : 0); }

void TMP102Full::setThermostatMode(bool interrupt) { _updateConfig(CFG_TM, interrupt ? CFG_TM : 0); }

void TMP102Full::shutdown() { _updateConfig(0, CFG_SD); }

void TMP102Full::wake() { _updateConfig(CFG_SD, 0); }

bool TMP102Full::oneShot(float& celsius) {
    uint16_t config = readConfig();
    bool wasRunning = (config & CFG_SD) == 0;
    if (wasRunning) {
        config |= CFG_SD;
        writeConfig(config);
    }
    writeConfig((uint16_t)(config | CFG_OS));
    bool done = false;
    for (uint8_t i = 0; i < 30; i++) {
        periph_delay_ms(1);
        if (readConfig() & CFG_OS) { done = true; break; }
    }
    celsius = readTemperature();
    if (wasRunning) wake();
    return done;
}

bool TMP102Full::alertState() {
    uint16_t config = readConfig();
    bool al = (config & CFG_AL) != 0;
    // AL reads 1 while inactive with POL = 0 (inverted); POL = 1 flips it.
    return (config & CFG_POL) ? al : !al;
}

void TMP102Full::reset() {
    const uint8_t gc[2] = { 0x00, 0x06 };
    _connection.write(gc, 2);
}

bool TMP102Full::pollInterrupt() {
    bool alerting = alertState();
    _readReg(REG_TEMPERATURE);
    return alerting;
}

void TMP102Full::onInterrupt(void (*callback)(float temperatureC), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    bool activeHigh = (readConfig() & CFG_POL) != 0;
    pin->onEdge(&TMP102Full::_edgeTrampoline, activeHigh ? InputPin::kRising : InputPin::kFalling);
}

void TMP102Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&TMP102Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void TMP102Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void TMP102Full::_handleEdge() {
    float t = readTemperature();
    if (_callback) _callback(t);
}
