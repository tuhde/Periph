#include "HS40xx.h"
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

static const uint8_t BITS[4] = {8, 10, 12, 14};

static int bits_code(uint8_t bits) {
    for (int i = 0; i < 4; i++) if (BITS[i] == bits) return i;
    return -1;
}

// --- Minimal ---

HS40xxMinimal::HS40xxMinimal(Connection& connection)
    : _connection(connection) {}

uint8_t HS40xxMinimal::_crc8(const uint8_t* data, uint8_t len) {
    uint8_t crc = 0xFF;
    for (uint8_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x1D) : (uint8_t)(crc << 1);
    }
    return crc;
}

uint16_t HS40xxMinimal::_raw_humidity(const uint8_t* d) { return (uint16_t)(((d[0] & 0x3F) << 8) | d[1]); }
uint16_t HS40xxMinimal::_raw_temperature(const uint8_t* d) { return (uint16_t)(((d[2] & 0x3F) << 8) | d[3]); }
float HS40xxMinimal::_to_humidity(uint16_t raw) { return raw / 16383.0f * 100.0f; }
float HS40xxMinimal::_to_temperature(uint16_t raw) { return raw / 16383.0f * 165.0f - 40.0f; }

bool HS40xxMinimal::_read_frame(uint8_t* buf, uint8_t len) {
    // The sensor NACKs the read header while converting; retry for up to 20 ms.
    for (int i = 0; i < 21; i++) {
        _connection.read(buf, len);
        if (_connection.ok()) return true;
        periph_delay_ms(1);
    }
    return false;
}

// buf receives the frame; for a 3-byte frame buf = {T_hi, T_lo, CRC}.
bool HS40xxMinimal::_measure(uint8_t cmd, uint8_t* buf, uint8_t len, bool hold) {
    for (int attempt = 0; attempt < 2; attempt++) {
        _connection.write(&cmd, 1);
        if (!_connection.ok()) return false;
        if (!hold) periph_delay_ms(3);
        if (!_read_frame(buf, len)) return false;
        uint8_t data[4] = {0, 0, 0, 0};
        if (len == 3) { data[2] = buf[0]; data[3] = buf[1]; }
        else for (int i = 0; i < 4; i++) data[i] = buf[i];
        if (_crc8(data, 4) == buf[len - 1]) return true;
    }
    return false;
}

bool HS40xxMinimal::read(float& temperature_c, float& humidity_pct) {
    uint8_t d[5];
    if (!_measure(0xF5, d, 5, false)) return false;
    temperature_c = _to_temperature(_raw_temperature(d));
    humidity_pct = _to_humidity(_raw_humidity(d));
    return true;
}

// --- Full ---

HS40xxFull* HS40xxFull::_active_instance = nullptr;

HS40xxFull::HS40xxFull(Connection& connection)
    : HS40xxMinimal(connection) {}

float HS40xxFull::read_temperature() {
    uint8_t d[3];
    if (!_measure(0xF3, d, 3, false)) return NAN;
    uint8_t f[4] = {0, 0, d[0], d[1]};
    return _to_temperature(_raw_temperature(f));
}

float HS40xxFull::read_humidity() {
    float t, rh;
    return read(t, rh) ? rh : NAN;
}

bool HS40xxFull::read_hold(float& temperature_c, float& humidity_pct) {
    uint8_t d[5];
    if (!_measure(0xE5, d, 5, true)) return false;
    temperature_c = _to_temperature(_raw_temperature(d));
    humidity_pct = _to_humidity(_raw_humidity(d));
    return true;
}

bool HS40xxFull::read_raw(uint16_t& humidity_raw, uint16_t& temperature_raw) {
    uint8_t d[5];
    if (!_measure(0xF5, d, 5, false)) return false;
    humidity_raw = _raw_humidity(d);
    temperature_raw = _raw_temperature(d);
    return true;
}

bool HS40xxFull::read_register(uint8_t reg, uint8_t& value) {
    const uint8_t cmd[2] = {0xA7, reg};
    _connection.write(cmd, 2);
    if (!_connection.ok()) return false;
    return _read_frame(&value, 1);
}

bool HS40xxFull::write_register(uint8_t reg, uint8_t value) {
    const uint8_t cmd[3] = {0xA6, reg, value};
    _connection.write(cmd, 3);
    return _connection.ok();
}

bool HS40xxFull::_modify(uint8_t reg, uint8_t mask, uint8_t bits) {
    uint8_t v;
    if (!read_register(reg, v)) return false;
    return write_register(reg, (uint8_t)((v & ~mask) | (bits & mask)));
}

bool HS40xxFull::set_resolution(uint8_t humidity_bits, uint8_t temperature_bits) {
    int rh_code = humidity_bits ? bits_code(humidity_bits) : -2;
    int t_code = temperature_bits ? bits_code(temperature_bits) : -2;
    if (rh_code == -1 || t_code == -1) return false;
    uint8_t mask = 0, value = 0;
    if (rh_code >= 0) { mask |= 0x0C; value |= (uint8_t)(rh_code << 2); }
    if (t_code >= 0) { mask |= 0x03; value |= (uint8_t)t_code; }
    return mask == 0 || _modify(0x00, mask, value);
}

bool HS40xxFull::get_resolution(uint8_t& humidity_bits, uint8_t& temperature_bits) {
    uint8_t v;
    if (!read_register(0x00, v)) return false;
    humidity_bits = BITS[(v >> 2) & 3];
    temperature_bits = BITS[v & 3];
    return true;
}

bool HS40xxFull::start_periodic(float period_s) {
    uint8_t code;
    if (period_s == 0.5f) code = 0;
    else if (period_s == 1.0f) code = 1;
    else if (period_s == 2.5f) code = 2;
    else return false;
    uint8_t en;
    if (!read_register(0x03, en)) return false;
    _alerts_enabled = (en & 0x0F) != 0;
    return _modify(0x02, 0xB0, (uint8_t)(0x80 | (code << 4)));
}

bool HS40xxFull::stop_periodic() {
    const uint8_t cmd = 0x30;
    _connection.write(&cmd, 1);
    return _connection.ok();
}

bool HS40xxFull::read_periodic(float& temperature_c, float& humidity_pct, uint8_t& status) {
    uint8_t d[6];
    uint8_t len = _alerts_enabled ? 6 : 5;
    if (!_read_frame(d, len)) return false;
    const uint8_t* f = _alerts_enabled ? d + 1 : d;
    status = _alerts_enabled ? (uint8_t)(d[0] & 0x0F) : 0;
    if (_crc8(f, 4) != f[4]) return false;
    temperature_c = _to_temperature(_raw_temperature(f));
    humidity_pct = _to_humidity(_raw_humidity(f));
    return true;
}

uint16_t HS40xxFull::_rh_word(float rh) {
    float w = rh / 100.0f * 16383.0f + 0.5f;
    return (uint16_t)(w < 0 ? 0 : (w > 16383 ? 16383 : w));
}

uint16_t HS40xxFull::_temp_word(float t) {
    float w = (t + 40.0f) / 165.0f * 16383.0f + 0.5f;
    return (uint16_t)(w < 0 ? 0 : (w > 16383 ? 16383 : w));
}

bool HS40xxFull::_write_threshold(uint8_t low_reg, uint16_t word) {
    return write_register(low_reg, (uint8_t)word) && write_register((uint8_t)(low_reg + 1), (uint8_t)(word >> 8));
}

bool HS40xxFull::set_alert_thresholds(float rh_high, float rh_high_reset, float rh_low, float rh_low_reset,
                                      float temp_high, float temp_high_reset, float temp_low, float temp_low_reset) {
    struct T { float v; uint8_t reg; bool rh; };
    const T list[8] = {
        {rh_high, 0x0F, true}, {rh_high_reset, 0x0D, true}, {rh_low, 0x11, true}, {rh_low_reset, 0x13, true},
        {temp_high, 0x07, false}, {temp_high_reset, 0x05, false}, {temp_low, 0x09, false}, {temp_low_reset, 0x0B, false}};
    for (const T& t : list) {
        if (isnan(t.v)) continue;
        if (!_write_threshold(t.reg, t.rh ? _rh_word(t.v) : _temp_word(t.v))) return false;
    }
    return true;
}

bool HS40xxFull::set_alert_polarity(bool active_high) {
    _active_high = active_high;
    return _modify(0x02, 0x01, active_high ? 1 : 0);
}

bool HS40xxFull::enable_interrupt(uint8_t source) {
    uint8_t v;
    if (!read_register(0x03, v)) return false;
    v = (uint8_t)(v | (source & 0x0F));
    _alerts_enabled = (v & 0x0F) != 0;
    return write_register(0x03, v);
}

bool HS40xxFull::disable_interrupt(uint8_t source) {
    uint8_t v;
    if (!read_register(0x03, v)) return false;
    v = (uint8_t)(v & ~(source & 0x0F));
    _alerts_enabled = (v & 0x0F) != 0;
    return write_register(0x03, v);
}

uint8_t HS40xxFull::poll_interrupt() {
    if (!_alerts_enabled) return 0;
    float t, rh;
    uint8_t status = 0;
    return read_periodic(t, rh, status) ? status : 0;
}

void HS40xxFull::_edge_trampoline() {
    if (_active_instance && _active_instance->_callback)
        _active_instance->_callback(_active_instance->poll_interrupt());
}

void HS40xxFull::on_interrupt(void (*callback)(uint8_t status), InputPin* intPin) {
    InputPin* pin = intPin ? intPin : _connection.intPin();
    if (!pin) return;
    _callback = callback;
    _int_pin = pin;
    _active_instance = this;
    pin->onEdge(&HS40xxFull::_edge_trampoline, _active_high ? InputPin::kRising : InputPin::kFalling);
}

void HS40xxFull::off_interrupt() {
    if (_int_pin) _int_pin->offEdge(&HS40xxFull::_edge_trampoline);
    _int_pin = nullptr;
    _callback = nullptr;
}

bool HS40xxFull::read_id(uint32_t& id) {
    const uint8_t cmd = 0xD7;
    _connection.write(&cmd, 1);
    if (!_connection.ok()) return false;
    uint8_t d[4];
    if (!_read_frame(d, 4)) return false;
    id = ((uint32_t)d[0] << 24) | ((uint32_t)d[1] << 16) | ((uint32_t)d[2] << 8) | d[3];
    return true;
}

bool HS40xxFull::soft_reset() {
    if (!stop_periodic()) return false;
    const uint8_t cmd = 0xFE;
    _connection.write(&cmd, 1);
    if (!_connection.ok()) return false;
    periph_delay_ms(500);
    _alerts_enabled = false;
    _active_high = true;
    return true;
}

float HS40xxFull::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh <= 0.0f) return -273.15f;
    float gamma = logf(rh / 100.0f) + 17.62f * t / (243.12f + t);
    return 243.12f * gamma / (17.62f - gamma);
}
