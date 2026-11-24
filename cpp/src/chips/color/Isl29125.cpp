#include "Isl29125.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t Isl29125Minimal::I2C_ADDRESS;
constexpr uint8_t Isl29125Minimal::DEVICE_ID;
constexpr uint8_t Isl29125Minimal::REG_DEVICE_ID;
constexpr uint8_t Isl29125Minimal::REG_CONFIG1;
constexpr uint8_t Isl29125Minimal::REG_CONFIG2;
constexpr uint8_t Isl29125Minimal::REG_CONFIG3;
constexpr uint8_t Isl29125Minimal::REG_LOW_TH;
constexpr uint8_t Isl29125Minimal::REG_HIGH_TH;
constexpr uint8_t Isl29125Minimal::REG_STATUS;
constexpr uint8_t Isl29125Minimal::REG_GREEN;
constexpr uint8_t Isl29125Minimal::REG_RED;
constexpr uint8_t Isl29125Minimal::REG_BLUE;
constexpr uint8_t Isl29125Minimal::RESET_VALUE;
constexpr uint8_t Isl29125Minimal::CFG1_SYNC;
constexpr uint8_t Isl29125Minimal::CFG1_BITS_12;
constexpr uint8_t Isl29125Minimal::CFG1_RNG_10K;
constexpr uint8_t Isl29125Minimal::CFG1_MODE;
constexpr uint8_t Isl29125Minimal::CFG3_CONVEN;
constexpr uint8_t Isl29125Minimal::ST_BROWNOUT;
constexpr uint8_t Isl29125Minimal::ST_CONVERSION;
constexpr uint8_t Isl29125Minimal::ST_THRESHOLD;
constexpr uint8_t Isl29125Full::SOURCE_THRESHOLD;
constexpr uint8_t Isl29125Full::SOURCE_CONVERSION;
constexpr uint8_t Isl29125Full::STATUS_BROWNOUT;
constexpr uint8_t Isl29125Full::STATUS_CONVERSION;
constexpr uint8_t Isl29125Full::STATUS_THRESHOLD;
constexpr uint8_t Isl29125Full::MODE_POWER_DOWN;
constexpr uint8_t Isl29125Full::MODE_GREEN;
constexpr uint8_t Isl29125Full::MODE_RED;
constexpr uint8_t Isl29125Full::MODE_BLUE;
constexpr uint8_t Isl29125Full::MODE_STANDBY;
constexpr uint8_t Isl29125Full::MODE_RGB;
constexpr uint8_t Isl29125Full::MODE_GREEN_RED;
constexpr uint8_t Isl29125Full::MODE_GREEN_BLUE;
constexpr uint8_t Isl29125Full::CHANNEL_NONE;
constexpr uint8_t Isl29125Full::CHANNEL_GREEN;
constexpr uint8_t Isl29125Full::CHANNEL_RED;
constexpr uint8_t Isl29125Full::CHANNEL_BLUE;

unsigned Isl29125Minimal::pollStepMs = 10;
Isl29125Full* Isl29125Full::_activeInstance = nullptr;

static const uint8_t PERSISTENCE[4] = { 1, 2, 4, 8 };
static const unsigned WAIT_LIMIT_MS = 400;

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

// Isl29125Minimal

Isl29125Minimal::Isl29125Minimal(RegisterConnection& connection) : _connection(connection) {
    if (_readReg(REG_DEVICE_ID) != DEVICE_ID) return;
    _valid = true;
    _writeReg(REG_STATUS, 0x00);
    _writeReg(REG_CONFIG3, 0x00);
    _writeReg(REG_CONFIG2, 0xBF);
    _writeReg(REG_CONFIG1, 0x0D);
}

void Isl29125Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

uint8_t Isl29125Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void Isl29125Minimal::_writeReg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

void Isl29125Minimal::_updateReg(uint8_t reg, uint8_t setBits, uint8_t clearBits) {
    uint8_t value = _readReg(reg);
    _writeReg(reg, (uint8_t)((value & ~clearBits) | setBits));
}

uint8_t Isl29125Minimal::_readStatus() {
    uint8_t value = _readReg(REG_STATUS);
    _pending |= value & (ST_BROWNOUT | ST_CONVERSION | ST_THRESHOLD);
    return value;
}

void Isl29125Minimal::_waitData() {
    unsigned step = pollStepMs ? pollStepMs : 1;
    for (unsigned waited = 0; waited <= WAIT_LIMIT_MS; waited += step) {
        if (_readStatus() & ST_CONVERSION) return;
        if (pollStepMs) _delayMs(pollStepMs);
    }
}

void Isl29125Minimal::_readBlock(Isl29125Counts& counts) {
    uint8_t raw[6] = { 0 };
    _connection.read(REG_GREEN, raw, 6);
    counts.green = (uint16_t)(raw[0] | (raw[1] << 8));
    counts.red = (uint16_t)(raw[2] | (raw[3] << 8));
    counts.blue = (uint16_t)(raw[4] | (raw[5] << 8));
}

uint16_t Isl29125Minimal::_readChannel(uint8_t reg) {
    uint8_t raw[2] = { 0 };
    _connection.read(reg, raw, 2);
    return (uint16_t)(raw[0] | (raw[1] << 8));
}

bool Isl29125Minimal::read(Isl29125Counts& counts) {
    if (!_valid) return false;
    _waitData();
    _readBlock(counts);
    return true;
}

// Isl29125Full

Isl29125Full::Isl29125Full(RegisterConnection& connection) : Isl29125Minimal(connection) {}

uint16_t Isl29125Full::readRed() { return _readChannel(REG_RED); }
uint16_t Isl29125Full::readGreen() { return _readChannel(REG_GREEN); }
uint16_t Isl29125Full::readBlue() { return _readChannel(REG_BLUE); }

void Isl29125Full::readRgb(Isl29125Counts& counts) { _readBlock(counts); }

bool Isl29125Full::dataReady() { return (_readStatus() & ST_CONVERSION) != 0; }

bool Isl29125Full::setMode(uint8_t mode) {
    if (mode > MODE_GREEN_BLUE) return false;
    _updateReg(REG_CONFIG1, mode, CFG1_MODE);
    return true;
}

bool Isl29125Full::setRange(uint16_t lux) {
    if (lux == 375) _updateReg(REG_CONFIG1, 0, CFG1_RNG_10K);
    else if (lux == 10000) _updateReg(REG_CONFIG1, CFG1_RNG_10K, 0);
    else return false;
    return true;
}

bool Isl29125Full::setResolution(uint8_t bits) {
    if (bits == 16) _updateReg(REG_CONFIG1, 0, CFG1_BITS_12);
    else if (bits == 12) _updateReg(REG_CONFIG1, CFG1_BITS_12, 0);
    else return false;
    return true;
}

void Isl29125Full::setSync(bool external) {
    _updateReg(REG_CONFIG1, external ? CFG1_SYNC : 0, CFG1_SYNC);
}

bool Isl29125Full::setIrCompensation(bool offset, uint8_t adjust) {
    if (adjust > 63) return false;
    _writeReg(REG_CONFIG2, (uint8_t)((offset ? 0x80 : 0x00) | adjust));
    return true;
}

uint8_t Isl29125Full::deviceId() { return _readReg(REG_DEVICE_ID); }

uint8_t Isl29125Full::status() {
    uint8_t value = _readStatus();
    value |= _pending;
    _pending = 0;
    return value;
}

void Isl29125Full::softReset() {
    _writeReg(REG_DEVICE_ID, RESET_VALUE);
    if (pollStepMs) _delayMs(10);
    _pending = 0;
}

bool Isl29125Full::setThresholds(uint16_t low, uint16_t high) {
    if (low > high) return false;
    uint8_t lo[2] = { (uint8_t)(low & 0xFF), (uint8_t)(low >> 8) };
    uint8_t hi[2] = { (uint8_t)(high & 0xFF), (uint8_t)(high >> 8) };
    _connection.write(REG_LOW_TH, lo, 2);
    _connection.write(REG_HIGH_TH, hi, 2);
    return true;
}

void Isl29125Full::getThresholds(uint16_t& low, uint16_t& high) {
    uint8_t lo[2] = { 0 };
    uint8_t hi[2] = { 0 };
    _connection.read(REG_LOW_TH, lo, 2);
    _connection.read(REG_HIGH_TH, hi, 2);
    low = (uint16_t)(lo[0] | (lo[1] << 8));
    high = (uint16_t)(hi[0] | (hi[1] << 8));
}

bool Isl29125Full::setInterruptChannel(uint8_t channel) {
    if (channel > CHANNEL_BLUE) return false;
    _updateReg(REG_CONFIG3, channel, 0x03);
    return true;
}

bool Isl29125Full::setPersistence(uint8_t count) {
    for (uint8_t code = 0; code < 4; code++) {
        if (PERSISTENCE[code] == count) {
            _updateReg(REG_CONFIG3, (uint8_t)(code << 2), 0x0C);
            return true;
        }
    }
    return false;
}

void Isl29125Full::enableInterrupt(uint8_t source) {
    if (source == SOURCE_CONVERSION) {
        _updateReg(REG_CONFIG3, CFG3_CONVEN, 0);
    } else if ((_readReg(REG_CONFIG3) & 0x03) == 0) {
        _updateReg(REG_CONFIG3, CHANNEL_GREEN, 0);
    }
}

void Isl29125Full::disableInterrupt(uint8_t source) {
    if (source == SOURCE_CONVERSION) _updateReg(REG_CONFIG3, 0, CFG3_CONVEN);
    else _updateReg(REG_CONFIG3, 0, 0x03);
}

uint8_t Isl29125Full::pollInterrupt() { return status(); }

void Isl29125Full::onInterrupt(void (*callback)(uint8_t), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&Isl29125Full::_edgeTrampoline, InputPin::kFalling);
}

void Isl29125Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&Isl29125Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void Isl29125Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->service();
}

void Isl29125Full::service() {
    uint8_t value = pollInterrupt();
    if ((value & (STATUS_THRESHOLD | STATUS_CONVERSION)) && _callback) _callback(value);
}
