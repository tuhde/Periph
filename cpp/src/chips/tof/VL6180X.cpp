#include "VL6180X.h"
#include <math.h>

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

namespace {
struct RegValue { uint16_t reg; uint8_t value; };

// Mandatory private-register settings (ST AN4545), applied while SYSTEM__FRESH_OUT_OF_RESET = 1.
const RegValue PRIVATE_SETTINGS[] = {
    {0x0207, 0x01}, {0x0208, 0x01}, {0x0096, 0x00}, {0x0097, 0xFD},
    {0x00E3, 0x01}, {0x00E4, 0x03}, {0x00E5, 0x02}, {0x00E6, 0x01},
    {0x00E7, 0x03}, {0x00F5, 0x02}, {0x00D9, 0x05}, {0x00DB, 0xCE},
    {0x00DC, 0x03}, {0x00DD, 0xF8}, {0x009F, 0x00}, {0x00A3, 0x3C},
    {0x00B7, 0x00}, {0x00BB, 0x3C}, {0x00B2, 0x09}, {0x00CA, 0x09},
    {0x0198, 0x01}, {0x01B0, 0x17}, {0x01AD, 0x00}, {0x00FF, 0x05},
    {0x0100, 0x05}, {0x0199, 0x05}, {0x01A6, 0x1B}, {0x01AC, 0x3E},
    {0x01A7, 0x1F}, {0x0030, 0x00},
};

// Recommended public settings (the 0x040 integration period is written as 16 bit separately).
const RegValue RECOMMENDED_SETTINGS[] = {
    {0x011, 0x10}, {0x10A, 0x30}, {0x03F, 0x46}, {0x031, 0xFF},
    {0x02E, 0x01}, {0x01B, 0x09}, {0x03E, 0x31}, {0x014, 0x24},
};

// Actual ALS gain per gain code 0–7.
const float GAIN[8] = { 20.0f, 10.32f, 5.21f, 2.60f, 1.72f, 1.28f, 1.01f, 40.0f };

const uint32_t RANGE_TIMEOUT_MS = 100;
}  // namespace

// --- Minimal ---

VL6180XMinimal::VL6180XMinimal(RegisterConnection& connection) : _conn(connection) {}

void VL6180XMinimal::_delayMs(uint32_t ms) { periph_delay_ms(ms); }

uint8_t VL6180XMinimal::_rd8(uint16_t reg) {
    uint8_t v = 0;
    _conn.read(reg, &v, 1);
    return v;
}

void VL6180XMinimal::_wr8(uint16_t reg, uint8_t value) { _conn.write(reg, &value, 1); }

uint16_t VL6180XMinimal::_rd16(uint16_t reg) {
    uint8_t b[2] = { 0, 0 };
    _conn.read(reg, b, 2);
    return (uint16_t)((b[0] << 8) | b[1]);
}

void VL6180XMinimal::_wr16(uint16_t reg, uint16_t value) {
    uint8_t b[2] = { (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _conn.write(reg, b, 2);
}

bool VL6180XMinimal::init() {
    _delayMs(1);
    if (_rd8(REG_FRESH_OUT_OF_RESET) == 1) {
        for (const RegValue& s : PRIVATE_SETTINGS) _wr8(s.reg, s.value);
        _wr8(REG_FRESH_OUT_OF_RESET, 0);
    }
    for (const RegValue& s : RECOMMENDED_SETTINGS) _wr8(s.reg, s.value);
    _wr16(REG_ALS_INTEGRATION, 0x0063);
    _gainCode = 6;
    _integrationMs = 100;
    return _conn.ok();
}

bool VL6180XMinimal::_waitSample(uint8_t shift, uint32_t timeoutMs) {
    for (uint32_t i = 0; i <= timeoutMs; i++) {
        if (((_rd8(REG_INTERRUPT_STATUS) >> shift) & 0x07) == 4) return true;
        _delayMs(1);
    }
    return false;
}

bool VL6180XMinimal::_readRangeResult(uint8_t& mm, uint8_t& error) {
    error = (uint8_t)(_rd8(REG_RANGE_STATUS) >> 4);
    mm = _rd8(REG_RANGE_VAL);
    _wr8(REG_INTERRUPT_CLEAR, 0x07);
    _lastError = error;
    return true;
}

bool VL6180XMinimal::_measureRange(uint8_t& mm, uint8_t& error) {
    _wr8(REG_RANGE_START, 0x01);
    if (!_waitSample(0, RANGE_TIMEOUT_MS)) {
        _wr8(REG_INTERRUPT_CLEAR, 0x07);
        _lastError = ERROR_TIMEOUT;
        return false;
    }
    return _readRangeResult(mm, error);
}

bool VL6180XMinimal::read_range(uint8_t& mm) {
    uint8_t error = 0;
    if (!_measureRange(mm, error)) return false;
    return error == 0;
}

// --- Full ---

VL6180XFull* VL6180XFull::_activeInstance = nullptr;

bool VL6180XFull::read_range_status(uint8_t& mm, uint8_t& error) { return _measureRange(mm, error); }

bool VL6180XFull::start_continuous_range(uint16_t period_ms) {
    if (period_ms < 10 || period_ms > 2550) return false;
    _wr8(REG_RANGE_INTERMEASUREMENT, (uint8_t)(period_ms / 10 - 1));
    _wr8(REG_INTERRUPT_CLEAR, 0x07);
    _wr8(REG_RANGE_START, 0x03);
    return true;
}

void VL6180XFull::stop_continuous_range() { _wr8(REG_RANGE_START, 0x01); }

bool VL6180XFull::read_range_continuous(uint8_t& mm) {
    uint32_t period = ((uint32_t)_rd8(REG_RANGE_INTERMEASUREMENT) + 1) * 10;
    if (!_waitSample(0, RANGE_TIMEOUT_MS + period)) {
        _lastError = ERROR_TIMEOUT;
        return false;
    }
    uint8_t error = 0;
    _readRangeResult(mm, error);
    return error == 0;
}

bool VL6180XFull::read_als_raw(uint16_t& raw) {
    _wr8(REG_ALS_START, 0x01);
    if (!_waitSample(3, _integrationMs + 100)) {
        _wr8(REG_INTERRUPT_CLEAR, 0x07);
        return false;
    }
    raw = _rd16(REG_ALS_VAL);
    _wr8(REG_INTERRUPT_CLEAR, 0x07);
    return true;
}

float VL6180XFull::read_lux() {
    uint16_t raw = 0;
    if (!read_als_raw(raw)) return NAN;
    return 0.32f * ((float)raw / GAIN[_gainCode]) * (100.0f / (float)_integrationMs);
}

bool VL6180XFull::start_continuous_als(uint16_t period_ms) {
    if (period_ms < 10 || period_ms > 2550) return false;
    _wr8(REG_ALS_INTERMEASUREMENT, (uint8_t)(period_ms / 10 - 1));
    _wr8(REG_INTERRUPT_CLEAR, 0x07);
    _wr8(REG_ALS_START, 0x03);
    return true;
}

void VL6180XFull::stop_continuous_als() { _wr8(REG_ALS_START, 0x01); }

void VL6180XFull::set_interleaved(bool on) { _wr8(REG_INTERLEAVED_ENABLE, on ? 1 : 0); }

bool VL6180XFull::set_als_gain(uint8_t code) {
    if (code > 7) return false;
    _wr8(REG_GROUPED_PARAM_HOLD, 1);
    _wr8(REG_ALS_GAIN, (uint8_t)(0x40 | code));
    _wr8(REG_GROUPED_PARAM_HOLD, 0);
    _gainCode = code;
    return true;
}

bool VL6180XFull::set_als_integration_time(uint16_t ms) {
    if (ms < 1 || ms > 512) return false;
    _wr8(REG_GROUPED_PARAM_HOLD, 1);
    _wr16(REG_ALS_INTEGRATION, (uint16_t)(ms - 1));
    _wr8(REG_GROUPED_PARAM_HOLD, 0);
    _integrationMs = ms;
    return true;
}

bool VL6180XFull::set_als_scaler(uint8_t scaler) {
    if (scaler < 1 || scaler > 16) return false;
    _wr8(REG_RESULT_SCALER, scaler);
    return true;
}

bool VL6180XFull::set_range_thresholds(uint8_t low, uint8_t high) {
    if (low > high) return false;
    _wr8(REG_GROUPED_PARAM_HOLD, 1);
    _wr8(REG_RANGE_THRESH_HIGH, high);
    _wr8(REG_RANGE_THRESH_LOW, low);
    _wr8(REG_GROUPED_PARAM_HOLD, 0);
    return true;
}

bool VL6180XFull::set_als_thresholds(uint16_t low, uint16_t high) {
    if (low > high) return false;
    _wr8(REG_GROUPED_PARAM_HOLD, 1);
    _wr16(REG_ALS_THRESH_HIGH, high);
    _wr16(REG_ALS_THRESH_LOW, low);
    _wr8(REG_GROUPED_PARAM_HOLD, 0);
    return true;
}

bool VL6180XFull::set_max_convergence_time(uint8_t ms) {
    if (ms < 1 || ms > 63) return false;
    _wr8(REG_RANGE_MAX_CONVERGENCE, ms);
    return true;
}

void VL6180XFull::set_averaging_period(uint8_t units) { _wr8(REG_AVERAGING_PERIOD, units); }

void VL6180XFull::set_range_offset(int8_t mm) { _wr8(REG_RANGE_OFFSET, (uint8_t)mm); }

int8_t VL6180XFull::get_range_offset() { return (int8_t)_rd8(REG_RANGE_OFFSET); }

bool VL6180XFull::set_crosstalk_rate(float rate) {
    if (!(rate >= 0.0f)) return false;
    float code = rate * 128.0f + 0.5f;
    if (code > 65535.0f) return false;
    _wr16(REG_RANGE_CROSSTALK_RATE, (uint16_t)code);
    return true;
}

bool VL6180XFull::recalibrate_vhv() {
    _wr8(REG_VHV_RECALIBRATE, 0x01);
    for (uint32_t i = 0; i <= 100; i++) {
        if ((_rd8(REG_VHV_RECALIBRATE) & 0x01) == 0) return true;
        _delayMs(1);
    }
    return false;
}

bool VL6180XFull::set_address(uint8_t address) {
    if (address < 0x08 || address > 0x77) return false;
    _wr8(REG_I2C_ADDRESS, (uint8_t)(address & 0x7F));
    return true;
}

uint8_t VL6180XFull::model_id() { return _rd8(REG_MODEL_ID); }

void VL6180XFull::revision(uint8_t& major, uint8_t& minor) {
    major = (uint8_t)(_rd8(REG_MODEL_REV_MAJOR) & 0x07);
    minor = (uint8_t)(_rd8(REG_MODEL_REV_MINOR) & 0x07);
}

void VL6180XFull::read_history(uint16_t out[8]) {
    uint8_t b[16] = {};
    _conn.read(REG_HISTORY_BUFFER, b, 16);
    for (int i = 0; i < 8; i++) out[i] = (uint16_t)((b[2 * i] << 8) | b[2 * i + 1]);
}

bool VL6180XFull::_setInterruptMode(uint8_t source, uint8_t mode) {
    uint8_t shift;
    if (source == SOURCE_RANGE) shift = 0;
    else if (source == SOURCE_ALS) shift = 3;
    else return false;
    uint8_t value = _rd8(REG_INTERRUPT_CONFIG);
    value = (uint8_t)((value & ~(0x07 << shift)) | (mode << shift));
    _wr8(REG_GROUPED_PARAM_HOLD, 1);
    _wr8(REG_INTERRUPT_CONFIG, value);
    _wr8(REG_GROUPED_PARAM_HOLD, 0);
    return true;
}

bool VL6180XFull::enable_interrupt(uint8_t source, uint8_t mode) {
    if (mode < 1 || mode > 4) return false;
    return _setInterruptMode(source, mode);
}

bool VL6180XFull::disable_interrupt(uint8_t source) { return _setInterruptMode(source, 0); }

uint8_t VL6180XFull::poll_interrupt() {
    uint8_t status = _rd8(REG_INTERRUPT_STATUS);
    uint8_t sources = 0;
    if (status & 0x07) sources |= SOURCE_RANGE;
    if (status & 0x38) sources |= SOURCE_ALS;
    if (status & 0xC0) sources |= SOURCE_ERROR;
    if (sources) _wr8(REG_INTERRUPT_CLEAR, 0x07);
    return sources;
}

void VL6180XFull::on_interrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    InputPin* pin = intPin ? intPin : _conn.intPin();
    if (!pin) return;  // no INT line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = pin;
    _activeInstance = this;
    pin->onEdge(&VL6180XFull::_edgeTrampoline, InputPin::kFalling);
}

void VL6180XFull::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&VL6180XFull::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void VL6180XFull::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void VL6180XFull::_handleEdge() {
    uint8_t status = poll_interrupt();
    if (status && _callback) _callback(status);
}
