#include "Apds9250.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t Apds9250Minimal::I2C_ADDRESS;
constexpr uint8_t Apds9250Minimal::REG_MAIN_CTRL;
constexpr uint8_t Apds9250Minimal::REG_LS_MEAS_RATE;
constexpr uint8_t Apds9250Minimal::REG_LS_GAIN;
constexpr uint8_t Apds9250Minimal::REG_PART_ID;
constexpr uint8_t Apds9250Minimal::REG_MAIN_STATUS;
constexpr uint8_t Apds9250Minimal::REG_DATA_IR;
constexpr uint8_t Apds9250Minimal::REG_DATA_GREEN;
constexpr uint8_t Apds9250Minimal::REG_DATA_BLUE;
constexpr uint8_t Apds9250Minimal::REG_DATA_RED;
constexpr uint8_t Apds9250Minimal::REG_INT_CFG;
constexpr uint8_t Apds9250Minimal::REG_INT_PERSISTENCE;
constexpr uint8_t Apds9250Minimal::REG_THRES_UP;
constexpr uint8_t Apds9250Minimal::REG_THRES_LOW;
constexpr uint8_t Apds9250Minimal::REG_THRES_VAR;
constexpr uint8_t Apds9250Minimal::CTRL_SW_RESET;
constexpr uint8_t Apds9250Minimal::CTRL_CS_MODE;
constexpr uint8_t Apds9250Minimal::CTRL_LS_EN;
constexpr uint8_t Apds9250Minimal::INT_EN;
constexpr uint8_t Apds9250Minimal::INT_VAR_MODE;
constexpr uint8_t Apds9250Minimal::INT_SEL_MASK;
constexpr uint8_t Apds9250Minimal::ST_POWER_ON;
constexpr uint8_t Apds9250Minimal::ST_INTERRUPT;
constexpr uint8_t Apds9250Minimal::ST_DATA_READY;
constexpr uint8_t Apds9250Full::SOURCE_THRESHOLD;
constexpr uint8_t Apds9250Full::SOURCE_VARIATION;
constexpr uint8_t Apds9250Full::STATUS_POWER_ON;
constexpr uint8_t Apds9250Full::STATUS_INTERRUPT;
constexpr uint8_t Apds9250Full::STATUS_DATA_READY;
constexpr uint8_t Apds9250Full::CHANNEL_IR;
constexpr uint8_t Apds9250Full::CHANNEL_GREEN;
constexpr uint8_t Apds9250Full::CHANNEL_RED;
constexpr uint8_t Apds9250Full::CHANNEL_BLUE;

unsigned Apds9250Minimal::startWaitMs = 5;
unsigned Apds9250Minimal::pollStepMs = 5;
Apds9250Full* Apds9250Full::_activeInstance = nullptr;

static const uint8_t RESOLUTION_BITS[6] = { 20, 19, 18, 17, 16, 13 };
static const float INTEGRATION_S[6] = { 0.4f, 0.2f, 0.1f, 0.05f, 0.025f, 0.003125f };
static const uint16_t RATE_MS[8] = { 25, 50, 100, 200, 500, 1000, 2000, 2000 };
static const uint8_t GAINS[5] = { 1, 3, 6, 9, 18 };

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

// Apds9250Minimal

Apds9250Minimal::Apds9250Minimal(RegisterConnection& connection) : _connection(connection) {
    uint8_t part = _readReg(REG_PART_ID);
    if ((part & 0xF0) != 0xB0) return;
    _valid = true;
    _writeReg(REG_LS_MEAS_RATE, 0x22);
    _writeReg(REG_LS_GAIN, 0x01);
    _writeReg(REG_INT_CFG, 0x10);
    _writeReg(REG_MAIN_CTRL, CTRL_LS_EN | CTRL_CS_MODE);
    if (startWaitMs) _delayMs(startWaitMs);
}

void Apds9250Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

uint8_t Apds9250Minimal::_readReg(uint8_t reg) {
    uint8_t buf[1] = { 0 };
    _connection.read(reg, buf, 1);
    return buf[0];
}

void Apds9250Minimal::_writeReg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

void Apds9250Minimal::_updateReg(uint8_t reg, uint8_t setBits, uint8_t clearBits) {
    uint8_t value = _readReg(reg);
    _writeReg(reg, (uint8_t)((value & ~clearBits) | setBits));
}

uint8_t Apds9250Minimal::_readStatus() {
    uint8_t value = _readReg(REG_MAIN_STATUS);
    _pending |= value & (ST_POWER_ON | ST_INTERRUPT);
    return value;
}

void Apds9250Minimal::_waitData() {
    unsigned limitMs = (unsigned)((_integrationS + 0.05f) * 1000.0f);
    unsigned step = pollStepMs ? pollStepMs : 1;
    for (unsigned waited = 0; waited <= limitMs; waited += step) {
        if (_readStatus() & ST_DATA_READY) return;
        if (pollStepMs) _delayMs(pollStepMs);
    }
}

uint32_t Apds9250Minimal::_count(const uint8_t* b) {
    return (uint32_t)b[0] | ((uint32_t)b[1] << 8) | ((uint32_t)(b[2] & 0x0F) << 16);
}

void Apds9250Minimal::_readBlock(Apds9250Counts& counts) {
    uint8_t raw[12] = { 0 };
    _connection.read(REG_DATA_IR, raw, 12);
    counts.ir = _count(raw);
    counts.green = _count(raw + 3);
    counts.blue = _count(raw + 6);
    counts.red = _count(raw + 9);
}

uint32_t Apds9250Minimal::_readChannel(uint8_t reg) {
    uint8_t raw[3] = { 0 };
    _connection.read(reg, raw, 3);
    return _count(raw);
}

bool Apds9250Minimal::read(Apds9250Counts& counts) {
    if (!_valid) return false;
    _waitData();
    _readBlock(counts);
    return true;
}

// Apds9250Full

Apds9250Full::Apds9250Full(RegisterConnection& connection) : Apds9250Minimal(connection) {}

uint32_t Apds9250Full::readRed() { return _readChannel(REG_DATA_RED); }
uint32_t Apds9250Full::readGreen() { return _readChannel(REG_DATA_GREEN); }
uint32_t Apds9250Full::readBlue() { return _readChannel(REG_DATA_BLUE); }
uint32_t Apds9250Full::readIr() { return _readChannel(REG_DATA_IR); }

void Apds9250Full::readChannels(Apds9250Counts& counts) { _readBlock(counts); }

bool Apds9250Full::dataReady() { return (_readStatus() & ST_DATA_READY) != 0; }

void Apds9250Full::setMode(bool colour) {
    _updateReg(REG_MAIN_CTRL, colour ? CTRL_CS_MODE : 0, CTRL_CS_MODE);
}

void Apds9250Full::enable() { _updateReg(REG_MAIN_CTRL, CTRL_LS_EN, 0); }
void Apds9250Full::disable() { _updateReg(REG_MAIN_CTRL, 0, CTRL_LS_EN); }

bool Apds9250Full::setResolution(uint8_t bits) {
    for (uint8_t code = 0; code < 6; code++) {
        if (RESOLUTION_BITS[code] == bits) {
            _updateReg(REG_LS_MEAS_RATE, (uint8_t)(code << 4), 0x70);
            _integrationS = INTEGRATION_S[code];
            return true;
        }
    }
    return false;
}

bool Apds9250Full::setMeasurementRate(uint16_t milliseconds) {
    for (uint8_t code = 0; code < 7; code++) {
        if (RATE_MS[code] == milliseconds) {
            _updateReg(REG_LS_MEAS_RATE, code, 0x07);
            return true;
        }
    }
    return false;
}

bool Apds9250Full::setGain(uint8_t gain) {
    for (uint8_t code = 0; code < 5; code++) {
        if (GAINS[code] == gain) {
            _writeReg(REG_LS_GAIN, code);
            return true;
        }
    }
    return false;
}

uint8_t Apds9250Full::getResolution() {
    uint8_t code = (_readReg(REG_LS_MEAS_RATE) >> 4) & 0x07;
    return code < 6 ? RESOLUTION_BITS[code] : 18;
}

uint16_t Apds9250Full::getMeasurementRate() { return RATE_MS[_readReg(REG_LS_MEAS_RATE) & 0x07]; }

uint8_t Apds9250Full::getGain() {
    uint8_t code = _readReg(REG_LS_GAIN) & 0x07;
    return code < 5 ? GAINS[code] : 18;
}

float Apds9250Full::integrationTime() {
    uint8_t code = (_readReg(REG_LS_MEAS_RATE) >> 4) & 0x07;
    return code < 6 ? INTEGRATION_S[code] : 0.1f;
}

float Apds9250Full::normalized(uint32_t counts) {
    return (float)counts / ((float)getGain() * integrationTime());
}

void Apds9250Full::partId(uint8_t& part, uint8_t& revision) {
    uint8_t value = _readReg(REG_PART_ID);
    part = value >> 4;
    revision = value & 0x0F;
}

uint8_t Apds9250Full::status() {
    uint8_t value = _readStatus();
    value |= _pending;
    _pending = 0;
    return value;
}

void Apds9250Full::softReset() {
    _writeReg(REG_MAIN_CTRL, CTRL_SW_RESET);
    if (startWaitMs) _delayMs(10);
    _pending = 0;
    _integrationS = 0.1f;
}

bool Apds9250Full::setThresholds(uint32_t low, uint32_t high) {
    if (low > 0xFFFFF || high > 0xFFFFF || low > high) return false;
    uint8_t lo[3] = { (uint8_t)(low & 0xFF), (uint8_t)((low >> 8) & 0xFF), (uint8_t)((low >> 16) & 0x0F) };
    uint8_t hi[3] = { (uint8_t)(high & 0xFF), (uint8_t)((high >> 8) & 0xFF), (uint8_t)((high >> 16) & 0x0F) };
    _connection.write(REG_THRES_LOW, lo, 3);
    _connection.write(REG_THRES_UP, hi, 3);
    return true;
}

void Apds9250Full::getThresholds(uint32_t& low, uint32_t& high) {
    uint8_t lo[3] = { 0 };
    uint8_t hi[3] = { 0 };
    _connection.read(REG_THRES_LOW, lo, 3);
    _connection.read(REG_THRES_UP, hi, 3);
    low = _count(lo);
    high = _count(hi);
}

bool Apds9250Full::setInterruptChannel(uint8_t channel) {
    if (channel > CHANNEL_BLUE) return false;
    _updateReg(REG_INT_CFG, (uint8_t)(channel << 4), INT_SEL_MASK);
    return true;
}

bool Apds9250Full::setPersistence(uint8_t count) {
    if (count < 1 || count > 16) return false;
    _writeReg(REG_INT_PERSISTENCE, (uint8_t)((count - 1) << 4));
    return true;
}

bool Apds9250Full::setVariance(uint16_t counts) {
    for (uint8_t n = 0; n < 8; n++) {
        if (counts == (uint16_t)(8u << n)) {
            _writeReg(REG_THRES_VAR, n);
            return true;
        }
    }
    return false;
}

void Apds9250Full::enableInterrupt(uint8_t source) {
    uint8_t mode = (source == SOURCE_VARIATION) ? INT_VAR_MODE : 0;
    _updateReg(REG_INT_CFG, (uint8_t)(INT_EN | mode), (uint8_t)(INT_EN | INT_VAR_MODE));
}

void Apds9250Full::disableInterrupt() { _updateReg(REG_INT_CFG, 0, INT_EN); }

uint8_t Apds9250Full::pollInterrupt() { return status(); }

void Apds9250Full::onInterrupt(void (*callback)(uint8_t), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _intPinUsed = pin;
    if (!pin) return;
    _activeInstance = this;
    pin->onEdge(&Apds9250Full::_edgeTrampoline, InputPin::kFalling);
}

void Apds9250Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&Apds9250Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void Apds9250Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->service();
}

void Apds9250Full::service() {
    uint8_t value = pollInterrupt();
    if ((value & STATUS_INTERRUPT) && _callback) _callback(value);
}
