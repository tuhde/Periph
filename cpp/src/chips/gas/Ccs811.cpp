#include "Ccs811.h"

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

// Sample interval in ms per drive mode (0 = idle)
static const uint32_t CCS811_INTERVAL_MS[5] = {0, 1000, 10000, 60000, 250};

// --- Minimal ---

CCS811Minimal::CCS811Minimal(RegisterConnection& connection)
    : _connection(connection) {}

bool CCS811Minimal::_write_reg(uint8_t reg, const uint8_t* data, size_t len) {
    static const uint8_t none = 0;
    _connection.write(reg, data ? data : &none, len);
    return _connection.ok();
}

bool CCS811Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.read(reg, buf, len);
    return _connection.ok();
}

bool CCS811Minimal::_start_app() {
    uint8_t s;
    if (!_read_reg(REG_STATUS, &s, 1)) return false;
    if (!(s & STATUS_APP_VALID)) return false;
    if (s & STATUS_FW_MODE) return true;
    if (!_write_reg(REG_APP_START, nullptr, 0)) return false;
    periph_delay_ms(2);
    if (!_read_reg(REG_STATUS, &s, 1)) return false;
    return (s & STATUS_FW_MODE) != 0;
}

bool CCS811Minimal::init() {
    uint8_t id;
    if (!_read_reg(REG_HW_ID, &id, 1) || id != HW_ID) return false;
    if (!_start_app()) return false;
    _meas_mode = MODE_1;
    return _write_reg(REG_MEAS_MODE, &_meas_mode, 1);
}

bool CCS811Minimal::_wait_data_ready() {
    uint8_t mode = (_meas_mode >> 4) & 0x07;
    uint32_t interval = mode < 5 ? CCS811_INTERVAL_MS[mode] : 0;
    uint32_t limit = interval * 3 / 2;
    if (limit < 1500) limit = 1500;
    for (uint32_t waited = 0; ; waited += 20) {
        uint8_t s;
        if (!_read_reg(REG_STATUS, &s, 1)) return false;
        if (s & STATUS_DATA_READY) return true;
        if (waited >= limit) return false;
        periph_delay_ms(20);
    }
}

bool CCS811Minimal::_read_result(uint16_t& eco2_ppm, uint16_t& tvoc_ppb) {
    uint8_t b[5];
    if (!_read_reg(REG_ALG_RESULT_DATA, b, 5)) return false;
    if (b[4] & STATUS_ERROR) return false;
    eco2_ppm = (uint16_t)((b[0] << 8) | b[1]);
    tvoc_ppb = (uint16_t)((b[2] << 8) | b[3]);
    return true;
}

bool CCS811Minimal::read(uint16_t& eco2_ppm, uint16_t& tvoc_ppb) {
    return _wait_data_ready() && _read_result(eco2_ppm, tvoc_ppb);
}

// --- Full ---

CCS811Full* CCS811Full::_activeInstance = nullptr;

CCS811Full::CCS811Full(RegisterConnection& connection)
    : CCS811Minimal(connection) {}

uint8_t CCS811Full::_int_bits(uint8_t source) {
    uint8_t bits = 0;
    if (source & SOURCE_DATA_READY) bits |= INT_DATARDY;
    if (source & SOURCE_THRESHOLD)  bits |= INT_DATARDY | INT_THRESH;
    return bits;
}

bool CCS811Full::_write_meas_mode() {
    return _write_reg(REG_MEAS_MODE, &_meas_mode, 1);
}

bool CCS811Full::_read16(uint8_t reg, uint16_t& value) {
    uint8_t b[2];
    if (!_read_reg(reg, b, 2)) return false;
    value = (uint16_t)((b[0] << 8) | b[1]);
    return true;
}

bool CCS811Full::read_eco2(uint16_t& eco2_ppm) {
    uint16_t tvoc;
    return read(eco2_ppm, tvoc);
}

bool CCS811Full::read_tvoc(uint16_t& tvoc_ppb) {
    uint16_t eco2;
    return read(eco2, tvoc_ppb);
}

bool CCS811Full::read_raw(uint8_t& current_ua, float& voltage_v) {
    uint16_t raw;
    if (!_read16(REG_RAW_DATA, raw)) return false;
    current_ua = (uint8_t)(raw >> 10);
    voltage_v = (raw & 0x3FF) * 1.65f / 1023.0f;
    return true;
}

bool CCS811Full::data_ready(bool& ready) {
    uint8_t s;
    if (!status(s)) return false;
    ready = (s & STATUS_DATA_READY) != 0;
    return true;
}

bool CCS811Full::set_mode(uint8_t mode) {
    if (mode > 4) return false;
    _meas_mode = (uint8_t)((_meas_mode & 0x0C) | (mode << 4));
    return _write_meas_mode();
}

bool CCS811Full::set_environment(float humidity_rh, float temperature_c) {
    if (!(humidity_rh >= 0.0f && humidity_rh <= 100.0f)) return false;
    if (!(temperature_c >= -25.0f && temperature_c <= 102.0f)) return false;
    uint16_t h = (uint16_t)(humidity_rh * 512.0f + 0.5f);
    uint16_t t = (uint16_t)((temperature_c + 25.0f) * 512.0f + 0.5f);
    uint8_t b[4] = {(uint8_t)(h >> 8), (uint8_t)h, (uint8_t)(t >> 8), (uint8_t)t};
    return _write_reg(REG_ENV_DATA, b, 4);
}

bool CCS811Full::read_ntc(uint16_t& v_ref_mv, uint16_t& v_ntc_mv) {
    uint8_t b[4];
    if (!_read_reg(REG_NTC, b, 4)) return false;
    v_ref_mv = (uint16_t)((b[0] << 8) | b[1]);
    v_ntc_mv = (uint16_t)((b[2] << 8) | b[3]);
    return true;
}

bool CCS811Full::ntc_resistance(float r_ref_ohm, float& r_ntc_ohm) {
    uint16_t v_ref, v_ntc;
    if (!read_ntc(v_ref, v_ntc) || v_ref == 0) return false;
    r_ntc_ohm = (float)v_ntc * r_ref_ohm / (float)v_ref;
    return true;
}

bool CCS811Full::set_thresholds(uint16_t low_medium_ppm, uint16_t medium_high_ppm,
                                uint8_t hysteresis_ppm) {
    if (low_medium_ppm >= medium_high_ppm) return false;
    uint8_t b[5] = {(uint8_t)(low_medium_ppm >> 8), (uint8_t)low_medium_ppm,
                    (uint8_t)(medium_high_ppm >> 8), (uint8_t)medium_high_ppm, hysteresis_ppm};
    return _write_reg(REG_THRESHOLDS, b, 5);
}

bool CCS811Full::get_baseline(uint16_t& baseline) {
    return _read16(REG_BASELINE, baseline);
}

bool CCS811Full::set_baseline(uint16_t baseline) {
    uint8_t b[2] = {(uint8_t)(baseline >> 8), (uint8_t)baseline};
    return _write_reg(REG_BASELINE, b, 2);
}

bool CCS811Full::status(uint8_t& status) {
    return _read_reg(REG_STATUS, &status, 1);
}

bool CCS811Full::error(uint8_t& error_id) {
    return _read_reg(REG_ERROR_ID, &error_id, 1);
}

bool CCS811Full::hardware_version(uint8_t& hw_id, uint8_t& hw_version) {
    return _read_reg(REG_HW_ID, &hw_id, 1) && _read_reg(REG_HW_VERSION, &hw_version, 1);
}

bool CCS811Full::_version(uint8_t reg, CCS811Version& version) {
    uint8_t b[2];
    if (!_read_reg(reg, b, 2)) return false;
    version.major = b[0] >> 4;
    version.minor = b[0] & 0x0F;
    version.trivial = b[1];
    return true;
}

bool CCS811Full::firmware_version(CCS811Version& boot, CCS811Version& app) {
    return _version(REG_FW_BOOT_VERSION, boot) && _version(REG_FW_APP_VERSION, app);
}

bool CCS811Full::soft_reset() {
    static const uint8_t key[4] = {0x11, 0xE5, 0x72, 0x8A};
    _write_reg(REG_SW_RESET, key, 4);  // the chip may not acknowledge while it resets
    periph_delay_ms(20);
    return init();
}

bool CCS811Full::enable_interrupt(uint8_t source) {
    _meas_mode |= _int_bits(source);
    return _write_meas_mode();
}

bool CCS811Full::disable_interrupt(uint8_t source) {
    _meas_mode &= (uint8_t)~_int_bits(source);
    return _write_meas_mode();
}

uint8_t CCS811Full::poll_interrupt() {
    uint8_t s;
    if (!status(s)) return 0;
    return s;
}

void CCS811Full::on_interrupt(void (*callback)(uint8_t), InputPin* intPin) {
    InputPin* p = intPin ? intPin : _connection.intPin();
    if (!p) return;  // no nINT line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = p;
    _activeInstance = this;
    p->onEdge(&CCS811Full::_edgeTrampoline, InputPin::kFalling);
}

void CCS811Full::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&CCS811Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
    if (_activeInstance == this) _activeInstance = nullptr;
}

void CCS811Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void CCS811Full::_handleEdge() {
    uint8_t s = poll_interrupt();
    if ((s & STATUS_DATA_READY) && _callback) _callback(s);
}
