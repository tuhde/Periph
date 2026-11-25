#include "DS1881.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr uint8_t DS1881Minimal::I2C_ADDRESS;
constexpr uint8_t DS1881Minimal::CMD_WIPER1;
constexpr uint8_t DS1881Minimal::CMD_CONFIG;
constexpr uint8_t DS1881Minimal::CFG_VOLATILE;
constexpr uint8_t DS1881Minimal::CFG_ZC;
constexpr uint8_t DS1881Minimal::CFG_OPTION2;
constexpr uint8_t DS1881Minimal::CFG_MASK;
constexpr uint8_t DS1881Minimal::CFG_DEFAULT;

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

// DS1881Minimal

DS1881Minimal::DS1881Minimal(Connection& connection)
    : _connection(connection), _config(CFG_DEFAULT), _option(1) {}

void DS1881Minimal::_delayMs(unsigned ms) { periph_delay_ms(ms); }

void DS1881Minimal::init() {
    _delayMs(1);
    uint8_t raw[3] = { 0, 0, 0 };
    _connection.read(raw, 3);
    if ((raw[2] & CFG_MASK) != CFG_DEFAULT) {
        uint8_t cmd = CMD_CONFIG | CFG_DEFAULT;
        _connection.write(&cmd, 1);
        _waitReady(0.05f);
    }
    _config = CFG_DEFAULT;
    _option = 1;
}

bool DS1881Minimal::_waitReady(float timeoutS) {
    unsigned attempts = (unsigned)(timeoutS * 1000.0f) + 1;
    for (unsigned i = 0; i < attempts; i++) {
        uint8_t b = 0;
        _connection.read(&b, 1);
        if (_connection.ok()) return true;
        _delayMs(1);
    }
    return false;
}

void DS1881Minimal::_writeCommands(const uint8_t* data, size_t len) {
    _connection.write(data, len);
    if (!(_config & CFG_VOLATILE)) _waitReady(0.05f);
}

uint8_t DS1881Minimal::_mutePosition() const { return _option == 1 ? 63 : 33; }

float DS1881Minimal::_positionToDb(int position) const {
    if (position >= _mutePosition()) return 80.0f;
    if (_option == 1 || position <= 12) return (float)position;
    if (position <= 24) return (float)(12 + 2 * (position - 12));
    return (float)(36 + 3 * (position - 24));
}

uint8_t DS1881Minimal::_nearestPosition(float db) const {
    if (db <= 0.0f) return 0;
    if (_option == 1) {
        int p = (int)(db + 0.5f);
        return (uint8_t)(p > 62 ? 62 : p);
    }
    int best = 0;
    float bestErr = db;
    for (int p = 1; p <= 32; p++) {
        float err = _positionToDb(p) - db;
        if (err < 0) err = -err;
        if (err < bestErr) { best = p; bestErr = err; }
    }
    return (uint8_t)best;
}

uint8_t DS1881Minimal::_dbToPosition(float db) const { return _nearestPosition(db); }

void DS1881Minimal::_writePosition(uint8_t channel, int position) {
    if (channel > 1) return;
    int mute = _mutePosition();
    if (position < 0) position = 0;
    else if (position > mute) position = mute;
    uint8_t cmd = (uint8_t)((channel << 6) | position);
    _writeCommands(&cmd, 1);
}

void DS1881Minimal::_readAll(uint8_t& position0, uint8_t& position1, uint8_t& config) {
    uint8_t raw[3] = { 0, 0, 0 };
    _connection.read(raw, 3);
    position0 = raw[0] & 0x3F;
    position1 = raw[1] & 0x3F;
    config = raw[2];
}

void DS1881Minimal::setAttenuation(uint8_t channel, float db) {
    if (channel > 1) return;
    _writePosition(channel, _dbToPosition(db));
}

float DS1881Minimal::getAttenuation(uint8_t channel) { return _positionToDb(getPosition(channel)); }

void DS1881Minimal::mute(uint8_t channel) { _writePosition(channel, _mutePosition()); }

void DS1881Minimal::setPosition(uint8_t channel, int position) { _writePosition(channel, position); }

uint8_t DS1881Minimal::getPosition(uint8_t channel) {
    if (channel > 1) return 0;
    uint8_t p0, p1, cfg;
    _readAll(p0, p1, cfg);
    return channel == 0 ? p0 : p1;
}

// DS1881Full

DS1881Full::DS1881Full(Connection& connection) : DS1881Minimal(connection) {}

uint8_t DS1881Full::_dbToPosition(float db) const {
    if (db >= 70.0f) return _mutePosition();
    return _nearestPosition(db);
}

void DS1881Full::setBoth(int position0, int position1) {
    int mute = _mutePosition();
    int p[2] = { position0, position1 };
    uint8_t data[2];
    for (int ch = 0; ch < 2; ch++) {
        int v = p[ch];
        if (v < 0) v = 0;
        else if (v > mute) v = mute;
        data[ch] = (uint8_t)((ch << 6) | v);
    }
    _writeCommands(data, 2);
}

void DS1881Full::setAttenuationBoth(float db0, float db1) {
    if (db1 < 0.0f) db1 = db0;
    setBoth(_dbToPosition(db0), _dbToPosition(db1));
}

void DS1881Full::configure(uint8_t option, bool zeroCrossing, bool nonvolatile) {
    if (option != 1 && option != 2) return;
    uint8_t config = 0;
    if (!nonvolatile) config |= CFG_VOLATILE;
    if (zeroCrossing) config |= CFG_ZC;
    if (option == 2) config |= CFG_OPTION2;
    if (config != _config) {
        uint8_t cmd = CMD_CONFIG | config;
        _connection.write(&cmd, 1);
        _waitReady(0.05f);
    }
    _config = config;
    _option = option;
}

DS1881Config DS1881Full::getConfig() {
    uint8_t p0, p1, raw;
    _readAll(p0, p1, raw);
    _config = raw & CFG_MASK;
    _option = (raw & CFG_OPTION2) ? 2 : 1;
    DS1881Config cfg;
    cfg.option = _option;
    cfg.zeroCrossing = (raw & CFG_ZC) != 0;
    cfg.nonvolatile = (raw & CFG_VOLATILE) == 0;
    cfg.raw = raw;
    return cfg;
}

void DS1881Full::setOption(uint8_t option) {
    configure(option, (_config & CFG_ZC) != 0, (_config & CFG_VOLATILE) == 0);
}

void DS1881Full::setZeroCrossing(bool enabled) {
    configure(_option, enabled, (_config & CFG_VOLATILE) == 0);
}

void DS1881Full::setNonvolatile(bool enabled) {
    configure(_option, (_config & CFG_ZC) != 0, enabled);
}

void DS1881Full::store() {
    uint8_t p0, p1, cfg;
    _readAll(p0, p1, cfg);
    bool wasNv = (_config & CFG_VOLATILE) == 0;
    bool zc = (_config & CFG_ZC) != 0;
    uint8_t option = _option;
    if (!wasNv) configure(option, zc, true);
    uint8_t data[2] = { (uint8_t)p0, (uint8_t)(CMD_WIPER1 | p1) };
    _writeCommands(data, 2);
    if (!wasNv) configure(option, zc, false);
}

bool DS1881Full::waitReady(float timeoutS) { return _waitReady(timeoutS); }

void DS1881Full::readAll(uint8_t& position0, uint8_t& position1, uint8_t& config) {
    _readAll(position0, position1, config);
}

float DS1881Full::positionToDb(int position) const { return _positionToDb(position); }

uint8_t DS1881Full::dbToPosition(float db) const { return _dbToPosition(db); }

uint8_t DS1881Full::maxPosition() const { return _mutePosition() - 1; }

void DS1881Full::reset() {
    uint8_t data[3] = { (uint8_t)(CMD_CONFIG | CFG_DEFAULT), 0x3F, (uint8_t)(CMD_WIPER1 | 63) };
    _connection.write(data, 3);
    _config = CFG_DEFAULT;
    _option = 1;
    _waitReady(0.05f);
}
