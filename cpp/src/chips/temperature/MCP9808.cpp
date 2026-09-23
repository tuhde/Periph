#include "MCP9808.h"
#include <stdlib.h>

namespace {
const float RESOLUTIONS[4] = { 0.5f, 0.25f, 0.125f, 0.0625f };
const float HYSTERESES[4]  = { 0.0f, 1.5f, 3.0f, 6.0f };

bool nearlyEqual(float a, float b) {
    float d = a - b;
    return d < 1e-4f && d > -1e-4f;
}
}  // namespace

// MCP9808Minimal

MCP9808Minimal::MCP9808Minimal(Connection& connection) : _connection(connection) {
    if (_readReg(REG_MFR_ID) != MANUFACTURER_ID ||
        (uint8_t)(_readReg(REG_DEVICE_ID) >> 8) != DEVICE_ID) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        // abort() rather than throwing: exceptions are disabled per platform
        // convention in this repo.
        abort();
    }
}

uint16_t MCP9808Minimal::_readReg(uint8_t reg) {
    uint8_t buf[2];
    _connection.write_read(&reg, 1, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void MCP9808Minimal::_writeReg(uint8_t reg, uint16_t value) {
    uint8_t buf[3] = { reg, (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _connection.write(buf, 3);
}

float MCP9808Minimal::readTemperature() {
    int16_t raw = (int16_t)(_readReg(REG_TA) & 0x1FFF);
    if (raw & 0x1000) raw -= 0x2000;
    return (float)raw / 16.0f;
}

// MCP9808Full

MCP9808Full* MCP9808Full::_activeInstance = nullptr;

MCP9808Full::MCP9808Full(Connection& connection) : MCP9808Minimal(connection) {}

uint16_t MCP9808Full::_readConfig() { return _readReg(REG_CONFIG) & CFG_WRITE_MASK; }

void MCP9808Full::_writeConfig(uint16_t value) { _writeReg(REG_CONFIG, value & CFG_WRITE_MASK); }

float MCP9808Full::_decodeLimit(uint16_t raw) {
    int16_t value = (int16_t)((raw >> 2) & 0x3FF);
    if (raw & 0x1000) value -= 1024;
    return (float)value / 4.0f;
}

uint16_t MCP9808Full::_encodeLimit(float celsius) {
    // Round half away from zero, then clamp to the 11-bit two's-complement range.
    int32_t quarters = celsius >= 0.0f ? (int32_t)(celsius * 4.0f + 0.5f)
                                       : -(int32_t)(-celsius * 4.0f + 0.5f);
    if (quarters < -1024) quarters = -1024;
    if (quarters > 1023) quarters = 1023;
    return (uint16_t)((quarters & 0x7FF) << 2);
}

bool MCP9808Full::setResolution(float celsius) {
    for (uint8_t code = 0; code < 4; code++) {
        if (nearlyEqual(celsius, RESOLUTIONS[code])) {
            uint8_t buf[2] = { REG_RESOLUTION, code };
            _connection.write(buf, 2);
            return true;
        }
    }
    return false;
}

float MCP9808Full::getResolution() {
    uint8_t reg = REG_RESOLUTION;
    uint8_t code = 0;
    _connection.write_read(&reg, 1, &code, 1);
    return RESOLUTIONS[code & 0x03];
}

void MCP9808Full::shutdown() {
    uint16_t config = _readConfig();
    if (config & CFG_LOCKS) return;
    _writeConfig(config | CFG_SHDN);
}

void MCP9808Full::wake() { _writeConfig(_readConfig() & (uint16_t)~CFG_SHDN); }

bool MCP9808Full::isShutdown() { return (_readReg(REG_CONFIG) & CFG_SHDN) != 0; }

float MCP9808Full::getUpperLimit() { return _decodeLimit(_readReg(REG_TUPPER)); }
void MCP9808Full::setUpperLimit(float celsius) { _writeReg(REG_TUPPER, _encodeLimit(celsius)); }

float MCP9808Full::getLowerLimit() { return _decodeLimit(_readReg(REG_TLOWER)); }
void MCP9808Full::setLowerLimit(float celsius) { _writeReg(REG_TLOWER, _encodeLimit(celsius)); }

float MCP9808Full::getCriticalLimit() { return _decodeLimit(_readReg(REG_TCRIT)); }
void MCP9808Full::setCriticalLimit(float celsius) { _writeReg(REG_TCRIT, _encodeLimit(celsius)); }

bool MCP9808Full::setHysteresis(float celsius) {
    for (uint16_t code = 0; code < 4; code++) {
        if (nearlyEqual(celsius, HYSTERESES[code])) {
            uint16_t config = _readConfig() & (uint16_t)~CFG_THYST_MASK;
            _writeConfig(config | (uint16_t)(code << CFG_THYST_SHIFT));
            return true;
        }
    }
    return false;
}

float MCP9808Full::getHysteresis() {
    return HYSTERESES[(_readReg(REG_CONFIG) & CFG_THYST_MASK) >> CFG_THYST_SHIFT];
}

void MCP9808Full::lockCriticalLimit() { _writeConfig(_readConfig() | CFG_CRIT_LOCK); }

void MCP9808Full::lockWindowLimits() { _writeConfig(_readConfig() | CFG_WIN_LOCK); }

bool MCP9808Full::isCriticalLimitLocked() { return (_readReg(REG_CONFIG) & CFG_CRIT_LOCK) != 0; }

bool MCP9808Full::isWindowLimitsLocked() { return (_readReg(REG_CONFIG) & CFG_WIN_LOCK) != 0; }

bool MCP9808Full::configureAlert(AlertMode mode, AlertOutput output, AlertPolarity polarity) {
    uint16_t config = _readConfig();
    if (config & CFG_LOCKS) return false;
    config &= (uint16_t)~(CFG_ALERT_SEL | CFG_ALERT_POL | CFG_ALERT_MOD);
    if (mode == AlertMode::CriticalOnly) config |= CFG_ALERT_SEL;
    if (polarity == AlertPolarity::ActiveHigh) config |= CFG_ALERT_POL;
    if (output == AlertOutput::Interrupt) config |= CFG_ALERT_MOD;
    _writeConfig(config);
    return true;
}

void MCP9808Full::enableAlert() { _writeConfig(_readConfig() | CFG_ALERT_CNT); }

void MCP9808Full::disableAlert() { _writeConfig(_readConfig() & (uint16_t)~CFG_ALERT_CNT); }

bool MCP9808Full::isAlertAsserted() { return (_readReg(REG_CONFIG) & CFG_ALERT_STAT) != 0; }

void MCP9808Full::clearInterrupt() { _writeReg(REG_CONFIG, _readConfig() | CFG_INT_CLEAR); }

uint8_t MCP9808Full::pollInterrupt() { return (uint8_t)((_readReg(REG_TA) >> 13) & 0x07); }

void MCP9808Full::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    bool activeHigh = (_readReg(REG_CONFIG) & CFG_ALERT_POL) != 0;
    pin->onEdge(&MCP9808Full::_edgeTrampoline, activeHigh ? InputPin::kRising : InputPin::kFalling);
}

void MCP9808Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&MCP9808Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void MCP9808Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void MCP9808Full::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (_callback) _callback(status);
}
