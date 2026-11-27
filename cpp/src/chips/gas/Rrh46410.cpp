#include "Rrh46410.h"
#include <string.h>

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
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

// --- Minimal ---

RRH46410Minimal::RRH46410Minimal(Connection& connection) : _connection(connection) {}

uint8_t RRH46410Minimal::_checksum(const uint8_t* data, size_t len) {
    uint8_t sum = 0;
    for (size_t i = 0; i < len; i++) sum += data[i];
    return (uint8_t)~sum;
}

float RRH46410Minimal::_period(uint8_t mode) {
    switch (mode) {
        case MODE_IAQ_2ND_GEN: return 3.0f;
        case MODE_ULP: return 90.0f;
        case MODE_PBAQ: return 5.0f;
        default: return 0.0f;
    }
}

uint8_t RRH46410Minimal::_warmup(uint8_t mode) {
    switch (mode) {
        case MODE_IAQ_2ND_GEN: return 100;
        case MODE_ULP: return 10;
        case MODE_PBAQ: return 60;
        default: return 0;
    }
}

bool RRH46410Minimal::_transact(uint8_t command, const uint8_t* params, size_t len, uint8_t* out,
                                size_t responseLen, bool check) {
    if (len > MAX_RESPONSE || responseLen > MAX_RESPONSE) {
        _lastError = ERR_ARG;
        return false;
    }
    uint8_t req[MAX_RESPONSE + 2];
    req[0] = command;
    if (len) memcpy(req + 1, params, len);
    req[len + 1] = _checksum(req, len + 1);
    _connection.write(req, len + 2);
    if (!_connection.ok()) {
        _lastError = ERR_BUS;
        return false;
    }
    uint8_t resp[MAX_RESPONSE + 2];
    _connection.read(resp, responseLen + 2);
    if (!_connection.ok()) {
        _lastError = ERR_BUS;
        return false;
    }
    if (_checksum(resp, responseLen + 1) != resp[responseLen + 1]) {
        _lastError = ERR_CHECKSUM;
        return false;
    }
    _lastStatus = resp[0];
    if (check && resp[0]) {
        _lastError = resp[0];
        return false;
    }
    if (out && responseLen) memcpy(out, resp + 1, responseLen);
    _lastError = 0;
    return true;
}

bool RRH46410Minimal::_waitProductId(uint8_t* data) {
    for (unsigned attempt = 0; attempt < 21; attempt++) {
        if (_transact(CMD_GET_PRODUCT_ID, nullptr, 0, data, 5)) {
            if ((uint16_t)((data[0] << 8) | data[1]) != PRODUCT_ID) {
                _lastError = ERR_PRODUCT_ID;
                return false;
            }
            return true;
        }
        periph_delay_ms(50);
    }
    _lastError = ERR_TIMEOUT;
    return false;
}

bool RRH46410Minimal::_setMode(uint8_t mode) {
    if (!_transact(CMD_SET_OPERATION_MODE, &mode, 1, nullptr, 0)) return false;
    _mode = mode;
    _haveCounter = false;
    _samples = 0;
    return true;
}

bool RRH46410Minimal::init() {
    uint8_t data[5];
    if (!_waitProductId(data)) return false;
    return _setMode(MODE_IAQ_2ND_GEN);
}

int RRH46410Minimal::_fetch(uint8_t* data) {
    size_t n = _mode == MODE_PBAQ ? 5 : 9;
    if (!_transact(CMD_GET_MEASUREMENT_RESULTS, nullptr, 0, data, n, false)) return -1;
    if (_lastStatus == STATUS_NOT_COMPLETE) {
        _lastError = ERR_NOT_READY;
        return 0;
    }
    if (_lastStatus) {
        _lastError = _lastStatus;
        return -1;
    }
    return 1;
}

void RRH46410Minimal::_decode(const uint8_t* d, RRH46410Result& r) {
    uint8_t counter = d[0];
    if (_haveCounter) _samples += (uint8_t)(counter - _lastCounter);
    else _samples = counter;
    _haveCounter = true;
    _lastCounter = counter;
    r = {};
    r.sample = counter;
    r.warmed_up = _samples >= _warmup(_mode);
    if (_mode == MODE_PBAQ) {
        r.pbaq = true;
        r.tvoc_mg_m3 = ((d[1] << 8) | d[2]) / 1000.0f;
        r.etoh_ppm = ((d[3] << 8) | d[4]) / 1000.0f;
        return;
    }
    r.iaq = d[1] / 10.0f;
    r.tvoc_mg_m3 = ((d[2] << 8) | d[3]) / 100.0f;
    r.etoh_ppm = ((d[4] << 8) | d[5]) / 100.0f;
    r.eco2_ppm = (uint16_t)((d[6] << 8) | d[7]);
    r.relative_iaq = (uint16_t)(d[8] * 10);
}

bool RRH46410Minimal::_waitSample(uint32_t timeoutMs) {
    InputPin* pin = _connection.intPin();
    bool had = _haveCounter;
    uint8_t last = _lastCounter;
    uint32_t polls = timeoutMs / POLL_MS + 1;
    for (uint32_t i = 0; i < polls; i++) {
        if (!pin || pin->isHigh()) {
            uint8_t data[9];
            int got = _fetch(data);
            if (got < 0) return false;
            if (got > 0 && (!had || data[0] != last)) {
                _decode(data, _last);
                return true;
            }
        }
        if (i + 1 < polls) periph_delay_ms(POLL_MS);
    }
    _lastError = ERR_TIMEOUT;
    return false;
}

bool RRH46410Minimal::read(RRH46410Result& result) {
    if (!_waitSample((uint32_t)(_period(_mode) * 1000) + 2000)) return false;
    result = _last;
    return true;
}

// --- Full ---

RRH46410Full* RRH46410Full::_activeInstance = nullptr;

bool RRH46410Full::product_id(uint16_t& productId, uint8_t firmware[3]) {
    uint8_t d[5];
    if (!_transact(CMD_GET_PRODUCT_ID, nullptr, 0, d, 5)) return false;
    productId = (uint16_t)((d[0] << 8) | d[1]);
    memcpy(firmware, d + 2, 3);
    return true;
}

bool RRH46410Full::tracking_number(uint8_t out[6]) {
    return _transact(CMD_GET_TRACKING_NUMBER, nullptr, 0, out, 6);
}

int RRH46410Full::get_mode() {
    uint8_t m;
    if (!_transact(CMD_GET_OPERATION_MODE, nullptr, 0, &m, 1)) return -1;
    _mode = m;
    return m;
}

bool RRH46410Full::set_mode(uint8_t mode) {
    if (mode != MODE_SUSPEND && mode != MODE_IAQ_2ND_GEN && mode != MODE_ULP && mode != MODE_PBAQ) {
        _lastError = ERR_ARG;
        return false;
    }
    return _setMode(mode);
}

bool RRH46410Full::suspend() { return _setMode(MODE_SUSPEND); }

bool RRH46410Full::read_results(RRH46410Result& result) {
    uint8_t data[9];
    if (_fetch(data) <= 0) return false;
    _decode(data, _last);
    result = _last;
    return true;
}

bool RRH46410Full::wait_for_sample(uint32_t timeoutMs) { return _waitSample(timeoutMs); }

bool RRH46410Full::set_humidity(float percent) {
    if (!(percent >= 0.0f && percent <= 100.0f)) {
        _lastError = ERR_ARG;
        return false;
    }
    uint8_t code = (uint8_t)(percent / 100.0f * 255.0f + 0.5f);
    return _transact(CMD_SET_HUMIDITY, &code, 1, nullptr, 0);
}

float RRH46410Full::sample_period(int mode) const { return _period(mode < 0 ? _mode : (uint8_t)mode); }

uint8_t RRH46410Full::warmup_samples(int mode) const { return _warmup(mode < 0 ? _mode : (uint8_t)mode); }

const char* RRH46410Full::air_quality_label(float iaq) {
    if (iaq < 2.0f) return "very good";
    if (iaq < 3.0f) return "good";
    if (iaq < 4.0f) return "medium";
    if (iaq < 5.0f) return "poor";
    return "bad";
}

bool RRH46410Full::clean_sensor(bool confirm) {
    if (!confirm) {
        _lastError = ERR_ARG;
        return false;
    }
    if (!_setMode(MODE_CLEANING)) return false;
    for (unsigned i = 0; i < 90; i++) {
        int m = get_mode();
        if (m < 0) return false;
        if (m != MODE_CLEANING) {
            _mode = MODE_SUSPEND;
            return true;
        }
        periph_delay_ms(1000);
    }
    _lastError = ERR_TIMEOUT;
    return false;
}

bool RRH46410Full::_gpio(uint8_t command, uint8_t mask) {
    if (mask > 3) {
        _lastError = ERR_ARG;
        return false;
    }
    return _transact(command, &mask, 1, nullptr, 0);
}

bool RRH46410Full::config_gpio(uint8_t directions) { return _gpio(CMD_CONFIG_GPIO, directions); }

int RRH46410Full::get_gpio() {
    uint8_t v;
    if (!_transact(CMD_GET_GPIO, nullptr, 0, &v, 1)) return -1;
    return v;
}

bool RRH46410Full::set_gpio(uint8_t mask) { return _gpio(CMD_SET_GPIO, mask); }

bool RRH46410Full::clear_gpio(uint8_t mask) { return _gpio(CMD_CLEAR_GPIO, mask); }

RRH46410Status RRH46410Full::status() const {
    RRH46410Status s;
    s.raw = _lastStatus;
    s.invalid_command = (_lastStatus & 0x80) != 0;
    s.checksum_error = (_lastStatus & 0x40) != 0;
    s.sensor_nack = (_lastStatus & 0x20) != 0;
    s.not_complete = (_lastStatus & 0x10) != 0;
    s.mode_error = _lastStatus & 0x0F;
    return s;
}

bool RRH46410Full::reset() {
    uint8_t req[2] = {CMD_RESET, 0};
    req[1] = _checksum(req, 1);
    _connection.write(req, 2);  // the module may not acknowledge while resetting
    periph_delay_ms(500);
    uint8_t data[5];
    if (!_waitProductId(data)) return false;
    _mode = MODE_SUSPEND;
    _haveCounter = false;
    _samples = 0;
    return true;
}

int RRH46410Full::send_command(uint8_t command, const uint8_t* params, size_t len, uint8_t* out,
                               size_t responseLen) {
    if (command == CMD_RESET) {
        uint8_t req[MAX_RESPONSE + 2];
        if (len > MAX_RESPONSE) {
            _lastError = ERR_ARG;
            return -1;
        }
        req[0] = command;
        if (len) memcpy(req + 1, params, len);
        req[len + 1] = _checksum(req, len + 1);
        _connection.write(req, len + 2);
        return 0;
    }
    if (!_transact(command, params, len, out, responseLen, false)) return -1;
    return (int)responseLen;
}

bool RRH46410Full::poll_interrupt() {
    InputPin* pin = _connection.intPin();
    if (pin) return pin->isHigh();
    uint8_t data[9];
    if (_fetch(data) <= 0) return false;
    return !_haveCounter || data[0] != _lastCounter;
}

void RRH46410Full::on_interrupt(void (*callback)(), InputPin* intPin) {
    InputPin* p = intPin ? intPin : _connection.intPin();
    if (!p) return;  // no INT line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = p;
    _activeInstance = this;
    p->onEdge(&RRH46410Full::_edgeTrampoline, InputPin::kRising);
}

void RRH46410Full::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&RRH46410Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
    if (_activeInstance == this) _activeInstance = nullptr;
}

void RRH46410Full::_edgeTrampoline() {
    if (_activeInstance && _activeInstance->_callback) _activeInstance->_callback();
}
