#include "AD5251.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t AD5251Minimal::I2C_ADDRESS;
constexpr uint8_t AD5251Full::BOTH;

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


static const uint8_t RDAC[2]     = { 0x01, 0x03 };
static const uint8_t EEMEM_FLAG  = 0x20;
static const uint8_t TOL_INT[2]  = { 0x3A, 0x3E };

static const uint8_t Q_NOP          = 0x80;
static const uint8_t Q_RESTORE      = 0x88;
static const uint8_t Q_STORE        = 0x90;
static const uint8_t Q_HALVE        = 0x98;
static const uint8_t Q_DEC_STEP     = 0xA8;
static const uint8_t Q_INC_6DB      = 0xC0;
static const uint8_t Q_INC_STEP     = 0xD0;
static const uint8_t Q_DEC_6DB_ALL  = 0xA0;
static const uint8_t Q_DEC_STEP_ALL = 0xB0;
static const uint8_t Q_RESET        = 0xB8;
static const uint8_t Q_INC_6DB_ALL  = 0xC8;
static const uint8_t Q_INC_STEP_ALL = 0xD8;

// AD5251Minimal

AD5251Minimal::AD5251Minimal(RegisterConnection& connection, float rAb, float rWiper)
    : _connection(connection), _rAb(rAb), _rW(rWiper) {}

uint8_t AD5251Minimal::_clampCode(int code) {
    if (code < 0) return 0;
    if (code > 63) return 63;
    return (uint8_t)code;
}

bool AD5251Minimal::_writeCode(uint8_t channel, int code) {
    if (channel > 1) return false;
    uint8_t c = _clampCode(code);
    _connection.write(RDAC[channel], &c, 1);
    return true;
}

uint8_t AD5251Minimal::_readCode(uint8_t channel) {
    if (channel > 1) return 0;
    uint8_t buf[1] = { 0 };
    _connection.read(RDAC[channel], buf, 1);
    return buf[0];
}

bool AD5251Minimal::setWiper(uint8_t channel, int code) { return _writeCode(channel, code); }

uint8_t AD5251Minimal::getWiper(uint8_t channel) { return _readCode(channel); }

bool AD5251Minimal::setResistance(uint8_t channel, float ohms) {
    return _writeCode(channel, (int)((ohms - _rW) / _rAb * 64.0f + 0.5f));
}

float AD5251Minimal::getResistance(uint8_t channel) {
    return (float)_readCode(channel) / 64.0f * _rAb + _rW;
}

// AD5251Full

AD5251Full::AD5251Full(RegisterConnection& connection, float rAb, float rWiper)
    : AD5251Minimal(connection, rAb, rWiper) {}

void AD5251Full::_delayMs(unsigned ms) { periph_delay_ms(ms); }

void AD5251Full::_quickByte(uint8_t b) { _connection.write(&b, 1); }

bool AD5251Full::_quick(uint8_t base, uint8_t baseAll, uint8_t channel) {
    if (channel == BOTH) {
        _quickByte(baseAll);
        return true;
    }
    if (channel > 1) return false;
    _quickByte((uint8_t)(base | RDAC[channel]));
    return true;
}

bool AD5251Full::_waitReady() {
    for (unsigned i = 0; i < 51; i++) {
        _quickByte(Q_NOP);
        if (_connection.ok()) return true;
        _delayMs(2);
    }
    return false;
}

void AD5251Full::setBoth(int code0, int code1) {
    uint8_t d[2] = { _clampCode(code0), _clampCode(code1) };
    _connection.write(RDAC[0], d, 2);
}

bool AD5251Full::setRatio(uint8_t channel, float ratio) {
    return _writeCode(channel, (int)(ratio * 64.0f + 0.5f));
}

float AD5251Full::getRatio(uint8_t channel) { return (float)_readCode(channel) / 64.0f; }

bool AD5251Full::setResistanceWa(uint8_t channel, float ohms) {
    int steps = (int)((ohms - _rW) / _rAb * 64.0f + 0.5f);
    return _writeCode(channel, 64 - steps);
}

float AD5251Full::getResistanceWa(uint8_t channel) {
    return (float)(64 - _readCode(channel)) / 64.0f * _rAb + _rW;
}

bool AD5251Full::increment(uint8_t channel) { return _quick(Q_INC_STEP, Q_INC_STEP_ALL, channel); }
bool AD5251Full::decrement(uint8_t channel) { return _quick(Q_DEC_STEP, Q_DEC_STEP_ALL, channel); }
bool AD5251Full::doubleCode(uint8_t channel) { return _quick(Q_INC_6DB, Q_INC_6DB_ALL, channel); }
bool AD5251Full::halve(uint8_t channel) { return _quick(Q_HALVE, Q_DEC_6DB_ALL, channel); }

bool AD5251Full::store(uint8_t channel) {
    if (channel == BOTH) {
        _quick(Q_STORE, 0, 0);
        if (!_waitReady()) return false;
        _quick(Q_STORE, 0, 1);
        return _waitReady();
    }
    if (!_quick(Q_STORE, 0, channel)) return false;
    return _waitReady();
}

bool AD5251Full::restore(uint8_t channel) {
    if (channel == BOTH) {
        _quick(Q_RESTORE, 0, 0);
        _quick(Q_RESTORE, 0, 1);
    } else if (!_quick(Q_RESTORE, 0, channel)) {
        return false;
    }
    nop();
    return true;
}

bool AD5251Full::reset() {
    _quickByte(Q_RESET);
    return _waitReady();
}

uint8_t AD5251Full::readEemem(uint8_t index) {
    if (index < 4 || index > 15) return 0;
    uint8_t b = 0;
    _connection.read((uint32_t)(EEMEM_FLAG | index), &b, 1);
    return b;
}

bool AD5251Full::writeEemem(uint8_t index, uint8_t value) {
    if (index < 4 || index > 15) return false;
    _connection.write((uint32_t)(EEMEM_FLAG | index), &value, 1);
    return _waitReady();
}

float AD5251Full::readTolerance(uint8_t channel) {
    if (channel > 1) return 0.0f;
    uint8_t raw[2] = { 0, 0 };
    _connection.read(TOL_INT[channel], raw, 2);
    float v = (float)(raw[0] & 0x7F) + (float)raw[1] / 256.0f;
    return (raw[0] & 0x80) ? -v : v;
}

float AD5251Full::rabActual(uint8_t channel) {
    return _rAb * (1.0f + readTolerance(channel) / 100.0f);
}

void AD5251Full::nop() { _quickByte(Q_NOP); }

void AD5251Full::setRab(float rAb, float rWiper) {
    _rAb = rAb;
    if (rWiper >= 0.0f) _rW = rWiper;
}
