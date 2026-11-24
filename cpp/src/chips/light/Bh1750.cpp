#include "Bh1750.h"
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

static const uint8_t OP_CONTINUOUS[3] = {0x10, 0x11, 0x13};
static const uint8_t OP_ONE_TIME[3]   = {0x20, 0x21, 0x23};
// maximum measurement time in milliseconds at MTreg = 69, indexed by mode
static const unsigned T_MAX_MS[3]     = {180, 180, 24};

// --- Minimal ---

BH1750Minimal::BH1750Minimal(Connection& connection)
    : _connection(connection) {}

void BH1750Minimal::_command(uint8_t code) {
    _connection.write(&code, 1);
}

void BH1750Minimal::init() {
    _command(OP_POWER_ON);
}

unsigned BH1750Minimal::_wait_ms(uint8_t mode) const {
    return (unsigned)ceilf((float)T_MAX_MS[mode] * (float)_mtreg / (float)DEFAULT_MTREG);
}

float BH1750Minimal::_to_lux(uint16_t raw, uint8_t mode) const {
    float lux = (float)raw / 1.2f * ((float)DEFAULT_MTREG / (float)_mtreg);
    return mode == MODE_H2 ? lux / 2.0f : lux;
}

bool BH1750Minimal::_read_word(uint16_t& raw) {
    uint8_t buf[2] = {0, 0};
    _connection.read(buf, 2);
    if (!_connection.ok()) return false;
    raw = (uint16_t)((buf[0] << 8) | buf[1]);
    return true;
}

bool BH1750Minimal::_measure_raw(uint8_t mode, uint16_t& raw) {
    _command(OP_ONE_TIME[mode]);
    if (!_connection.ok()) return false;
    periph_delay_ms(_wait_ms(mode));
    return _read_word(raw);
}

bool BH1750Minimal::read(float& lux) {
    uint16_t raw = 0;
    if (!_measure_raw(_mode, raw)) return false;
    lux = _to_lux(raw, _mode);
    return true;
}

// --- Full ---

BH1750Full::BH1750Full(Connection& connection)
    : BH1750Minimal(connection) {}

bool BH1750Full::read_raw(uint16_t& raw) {
    return _measure_raw(_mode, raw);
}

bool BH1750Full::measure_once(uint8_t mode, float& lux) {
    if (mode > MODE_L) return false;
    _mode = mode;
    uint16_t raw = 0;
    if (!_measure_raw(mode, raw)) return false;
    lux = _to_lux(raw, mode);
    return true;
}

bool BH1750Full::start_continuous(uint8_t mode) {
    if (mode > MODE_L) return false;
    _mode = mode;
    _command(OP_CONTINUOUS[mode]);
    return _connection.ok();
}

bool BH1750Full::read_continuous(float& lux) {
    uint16_t raw = 0;
    if (!_read_word(raw)) return false;
    lux = _to_lux(raw, _mode);
    return true;
}

void BH1750Full::power_down() { _command(OP_POWER_DOWN); }
void BH1750Full::power_on()   { _command(OP_POWER_ON); }

void BH1750Full::reset() {
    _command(OP_POWER_ON);
    _command(OP_RESET);
}

bool BH1750Full::set_measurement_time(uint8_t mtreg) {
    if (mtreg < 31 || mtreg > 254) return false;
    _command((uint8_t)(OP_MT_HIGH | (mtreg >> 5)));
    if (!_connection.ok()) return false;
    _command((uint8_t)(OP_MT_LOW | (mtreg & 0x1F)));
    if (!_connection.ok()) return false;
    _mtreg = mtreg;
    return true;
}

uint8_t BH1750Full::get_measurement_time_register() const { return _mtreg; }

bool BH1750Full::set_sensitivity(float factor) {
    float mt = roundf((float)DEFAULT_MTREG * factor);
    if (mt < 31.0f || mt > 254.0f) return false;
    return set_measurement_time((uint8_t)mt);
}

float BH1750Full::measurement_time(uint8_t mode) const {
    if (mode > MODE_L) mode = MODE_H;
    return (float)T_MAX_MS[mode] / 1000.0f * (float)_mtreg / (float)DEFAULT_MTREG;
}

float BH1750Full::lux_per_count(uint8_t mode) const {
    if (mode > MODE_L) mode = MODE_H;
    return _to_lux(1, mode);
}
