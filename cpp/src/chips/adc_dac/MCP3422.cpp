#include "MCP3422.h"
#include <math.h>

constexpr uint8_t MCP3422Minimal::I2C_ADDRESS;
constexpr uint8_t MCP3422Minimal::CFG_RDY;
constexpr uint8_t MCP3422Minimal::CFG_CONT;

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

// MCP3422Minimal

MCP3422Minimal::MCP3422Minimal(Connection& connection) : _connection(connection) {
    uint8_t cfg = _config(false);
    _connection.write(&cfg, 1);
}

void MCP3422Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

uint8_t MCP3422Minimal::_config(bool rdy) const {
    uint8_t s = _bits == 12 ? 0 : (_bits == 14 ? 1 : (_bits == 16 ? 2 : 3));
    uint8_t g = _gain == 1 ? 0 : (_gain == 2 ? 1 : (_gain == 4 ? 2 : 3));
    uint8_t c = (uint8_t)((_channel << 5) | (s << 2) | g);
    if (rdy) c |= CFG_RDY;
    if (_continuous) c |= CFG_CONT;
    return c;
}

float MCP3422Minimal::_convSeconds() const {
    return _bits == 12 ? 1.0f / 240 : (_bits == 14 ? 1.0f / 60 : (_bits == 16 ? 1.0f / 15 : 1.0f / 3.75f));
}

int32_t MCP3422Minimal::_decode(const uint8_t* data) const {
    int32_t raw = 0;
    for (uint8_t i = 0; i < _nbytes(); i++) raw = (raw << 8) | data[i];
    raw &= (int32_t)((1UL << _bits) - 1);
    if (raw & (1L << (_bits - 1))) raw -= (int32_t)(1UL << _bits);
    return raw;
}

int32_t MCP3422Minimal::_waitReady() {
    float conv = _convSeconds();
    unsigned polls = (unsigned)((2 * conv + 0.010f) / 0.005f) + 1;
    uint8_t data[4] = { 0, 0, 0, 0 };
    for (unsigned i = 0; i < polls; i++) {
        _connection.read(data, _nbytes() + 1u);
        if (!(data[_nbytes()] & CFG_RDY)) { _lastOk = true; return _decode(data); }
        _delayMs(5);
    }
    _lastOk = false;
    return 0;
}

int32_t MCP3422Minimal::_convert(int channel) {
    _channel = channel < 1 ? 0 : 1;
    _continuous = false;
    _discard = false;
    uint8_t cfg = _config(true);
    _connection.write(&cfg, 1);
    _delayMs((unsigned)(_convSeconds() * 1100.0f) + 1);
    return _waitReady();
}

int32_t MCP3422Minimal::readRaw(int channel) { return _convert(channel); }

float MCP3422Minimal::lsb() const { return 2.0f * 2.048f / (float)(1UL << _bits); }

float MCP3422Minimal::readVoltage(int channel) {
    int32_t raw = _convert(channel);
    return _lastOk ? (float)raw * lsb() / (float)_gain : 0.0f;
}

bool MCP3422Minimal::readAll(float* out) {
    out[0] = readVoltage(0);
    bool ok = _lastOk;
    out[1] = readVoltage(1);
    return ok && _lastOk;
}

// MCP3422Full

MCP3422Full::MCP3422Full(Connection& connection) : MCP3422Minimal(connection) {}

void MCP3422Full::_apply() {
    if (_continuous) {
        uint8_t cfg = _config(false);
        _connection.write(&cfg, 1);
        _discard = true;
    }
}

bool MCP3422Full::setResolution(int bits) {
    if (bits != 12 && bits != 14 && bits != 16 && bits != 18) return false;
    _bits = (uint8_t)bits;
    _apply();
    return true;
}

bool MCP3422Full::setGain(int gain) {
    if (gain != 1 && gain != 2 && gain != 4 && gain != 8) return false;
    _gain = (uint8_t)gain;
    _apply();
    return true;
}

void MCP3422Full::setChannel(int channel) {
    _channel = channel < 1 ? 0 : 1;
    _apply();
}

void MCP3422Full::startContinuous(int channel) {
    if (channel >= 0) _channel = channel < 1 ? 0 : 1;
    _continuous = true;
    _discard = true;
    uint8_t cfg = _config(false);
    _connection.write(&cfg, 1);
}

void MCP3422Full::stopContinuous() {
    _continuous = false;
    _discard = false;
    uint8_t cfg = _config(false);
    _connection.write(&cfg, 1);
}

int32_t MCP3422Full::readContinuousRaw() {
    if (!_continuous) { _lastOk = false; return 0; }
    if (_discard) {
        _waitReady();
        _discard = false;
    }
    return _waitReady();
}

float MCP3422Full::readContinuous() {
    int32_t raw = readContinuousRaw();
    return _lastOk ? (float)raw * lsb() / (float)_gain : 0.0f;
}

uint8_t MCP3422Full::readConfig() {
    uint8_t data[4] = { 0, 0, 0, 0 };
    _connection.read(data, _nbytes() + 1u);
    return data[_nbytes()];
}

bool MCP3422Full::isReady() { return !(readConfig() & CFG_RDY); }

bool MCP3422Full::readScan(const uint8_t* channels, size_t count, float* out) {
    bool ok = true;
    for (size_t i = 0; i < count; i++) {
        out[i] = readVoltage(channels[i]);
        ok = ok && _lastOk;
    }
    return ok;
}

void MCP3422Full::generalCallReset() {
    const uint8_t gc[2] = { 0x00, 0x06 };
    _connection.write(gc, 2);
    _bits = 12;
    _gain = 1;
    _channel = 0;
    _continuous = true;
    _discard = true;
}

void MCP3422Full::generalCallConvert() {
    const uint8_t gc[2] = { 0x00, 0x08 };
    _connection.write(gc, 2);
    _continuous = false;
}

int32_t MCP3422Full::readPendingRaw() { return _waitReady(); }

float MCP3422Full::fullScale() const { return 2.048f / (float)_gain; }
