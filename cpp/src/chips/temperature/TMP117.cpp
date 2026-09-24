#include "TMP117.h"
#include <stdlib.h>

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
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

namespace {
// Conversion cycle times in s, indexed by CONV[2:0] (no-averaging column).
const float CYCLES[8] = { 0.0155f, 0.125f, 0.25f, 0.5f, 1.0f, 4.0f, 8.0f, 16.0f };
// Averaging counts, indexed by AVG[1:0].
const uint8_t AVERAGINGS[4] = { 0, 8, 32, 64 };
const float LSB_C = 0.0078125f;

float absf(float x) { return x < 0.0f ? -x : x; }
}  // namespace

// TMP117Minimal

TMP117Minimal::TMP117Minimal(Connection& connection) : _connection(connection) {
    if ((_readReg(REG_DEVICE_ID) & 0x0FFF) != DEVICE_ID) {
        // Identity check failed — wrong chip / wrong address / wiring problem.
        // abort() rather than throwing: exceptions are disabled per platform
        // convention in this repo.
        abort();
    }
}

uint16_t TMP117Minimal::_readReg(uint8_t reg) {
    uint8_t buf[2];
    _connection.write_read(&reg, 1, buf, 2);
    return (uint16_t)((buf[0] << 8) | buf[1]);
}

void TMP117Minimal::_writeReg(uint8_t reg, uint16_t value) {
    uint8_t buf[3] = { reg, (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _connection.write(buf, 3);
}

float TMP117Minimal::_decodeTemperature(uint16_t raw) {
    return (float)(int16_t)raw * LSB_C;
}

uint16_t TMP117Minimal::_encodeTemperature(float celsius) {
    // Round half away from zero, then clamp to the 16-bit two's-complement range.
    float steps = celsius / LSB_C;
    int32_t value = steps >= 0.0f ? (int32_t)(steps + 0.5f) : -(int32_t)(-steps + 0.5f);
    if (value < -32768) value = -32768;
    if (value > 32767) value = 32767;
    return (uint16_t)(value & 0xFFFF);
}

float TMP117Minimal::readTemperature() { return _decodeTemperature(_readReg(REG_TEMP_RESULT)); }

// TMP117Full

TMP117Full* TMP117Full::_activeInstance = nullptr;

TMP117Full::TMP117Full(Connection& connection) : TMP117Minimal(connection) {}

uint16_t TMP117Full::_readConfig() { return _readReg(REG_CONFIG) & CFG_WRITE_MASK; }

void TMP117Full::_writeConfig(uint16_t value) { _writeReg(REG_CONFIG, value & CFG_WRITE_MASK); }

bool TMP117Full::configure(Mode mode, uint8_t averaging, float cycleSeconds) {
    int avg = -1;
    for (int i = 0; i < 4; i++) {
        if (AVERAGINGS[i] == averaging) avg = i;
    }
    if (avg < 0) return false;
    uint16_t conv = 0;
    for (uint16_t code = 1; code < 8; code++) {
        if (absf(CYCLES[code] - cycleSeconds) < absf(CYCLES[conv] - cycleSeconds)) conv = code;
    }
    uint16_t config = _readConfig() & (uint16_t)~(CFG_MOD_MASK | CFG_CONV_MASK | CFG_AVG_MASK);
    config |= (uint16_t)((uint16_t)mode << CFG_MOD_SHIFT) | (uint16_t)(conv << CFG_CONV_SHIFT) |
              (uint16_t)(avg << CFG_AVG_SHIFT);
    _writeConfig(config);
    return true;
}

TMP117Full::Config TMP117Full::getConfig() {
    uint16_t config = _readReg(REG_CONFIG);
    uint8_t mod = (uint8_t)((config & CFG_MOD_MASK) >> CFG_MOD_SHIFT);
    Config result;
    // MOD = 10 reads back as continuous conversion.
    result.mode = mod == 1 ? Mode::Shutdown : mod == 3 ? Mode::OneShot : Mode::Continuous;
    result.averaging = AVERAGINGS[(config & CFG_AVG_MASK) >> CFG_AVG_SHIFT];
    result.cycleSeconds = CYCLES[(config & CFG_CONV_MASK) >> CFG_CONV_SHIFT];
    return result;
}

bool TMP117Full::isShutdown() {
    return ((_readReg(REG_CONFIG) & CFG_MOD_MASK) >> CFG_MOD_SHIFT) == 1;
}

void TMP117Full::triggerOneShot() {
    uint16_t config = _readConfig() & (uint16_t)~CFG_MOD_MASK;
    _writeConfig(config | (uint16_t)((uint16_t)Mode::OneShot << CFG_MOD_SHIFT));
}

bool TMP117Full::isDataReady() { return (_readReg(REG_CONFIG) & CFG_DATA_READY) != 0; }

float TMP117Full::getHighLimit() { return _decodeTemperature(_readReg(REG_THIGH)); }
void TMP117Full::setHighLimit(float celsius) { _writeReg(REG_THIGH, _encodeTemperature(celsius)); }

float TMP117Full::getLowLimit() { return _decodeTemperature(_readReg(REG_TLOW)); }
void TMP117Full::setLowLimit(float celsius) { _writeReg(REG_TLOW, _encodeTemperature(celsius)); }

float TMP117Full::getTemperatureOffset() { return _decodeTemperature(_readReg(REG_TEMP_OFFSET)); }
void TMP117Full::setTemperatureOffset(float celsius) {
    _writeReg(REG_TEMP_OFFSET, _encodeTemperature(celsius));
}

void TMP117Full::reset() {
    _writeReg(REG_CONFIG, CFG_SOFT_RESET);
    periph_delay_ms(2);
}

void TMP117Full::unlockEeprom() { _writeReg(REG_EEPROM_UL, EUN); }

void TMP117Full::lockEeprom() { _writeReg(REG_EEPROM_UL, 0x0000); }

bool TMP117Full::isEepromBusy() { return (_readReg(REG_EEPROM_UL) & EEPROM_BUSY) != 0; }

bool TMP117Full::readEepromScratch(uint8_t slot, uint16_t& value) {
    uint8_t reg;
    switch (slot) {
        case 1: reg = REG_EEPROM1; break;
        case 2: reg = REG_EEPROM2; break;
        case 3: reg = REG_EEPROM3; break;
        default: return false;
    }
    value = _readReg(reg);
    return true;
}

bool TMP117Full::writeEepromScratch(uint8_t slot, uint16_t value) {
    // EEPROM1/EEPROM3 hold factory NIST-traceability data — never written.
    if (slot != 2) return false;
    _writeReg(REG_EEPROM2, value);
    return true;
}

void TMP117Full::configureAlert(AlertMode mode, AlertPolarity polarity, AlertPinFunction pinFunction) {
    uint16_t config = _readConfig() & (uint16_t)~(CFG_TNA | CFG_POL | CFG_DR_ALERT);
    if (mode == AlertMode::Therm) config |= CFG_TNA;
    if (polarity == AlertPolarity::ActiveHigh) config |= CFG_POL;
    if (pinFunction == AlertPinFunction::DataReady) config |= CFG_DR_ALERT;
    _writeConfig(config);
}

uint8_t TMP117Full::pollInterrupt() {
    uint16_t config = _readReg(REG_CONFIG);
    uint8_t status = 0;
    if (config & CFG_HIGH_ALERT) status |= SOURCE_HIGH;
    if (config & CFG_LOW_ALERT) status |= SOURCE_LOW;
    return status;
}

void TMP117Full::onInterrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    bool activeHigh = (_readReg(REG_CONFIG) & CFG_POL) != 0;
    pin->onEdge(&TMP117Full::_edgeTrampoline, activeHigh ? InputPin::kRising : InputPin::kFalling);
}

void TMP117Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&TMP117Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void TMP117Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void TMP117Full::_handleEdge() {
    uint8_t status = pollInterrupt();
    if (_callback) _callback(status);
}
