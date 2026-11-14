#include "Si7021.h"
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

static const uint8_t USER_RESERVED = 0x3A;
static const uint8_t USER_VDDS     = 0x40;
static const uint8_t USER_HTRE     = 0x04;
static const uint8_t USER_RES_MASK = 0x81;

// RES1:RES0 index -> (rh_bits, temperature_bits)
static const uint8_t RES_RH[4] = {12, 8, 10, 11};
static const uint8_t RES_T[4]  = {14, 12, 13, 11};

// --- Minimal ---

Si7021Minimal::Si7021Minimal(Connection& connection)
    : _connection(connection) {}

uint8_t Si7021Minimal::_crc8(const uint8_t* data, uint8_t len) {
    uint8_t crc = 0x00;
    for (uint8_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t b = 0; b < 8; b++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

void Si7021Minimal::_command(uint8_t code) {
    _connection.write(&code, 1);
}

bool Si7021Minimal::_measure(uint8_t hold_cmd, uint8_t poll_cmd, uint16_t& code) {
    uint8_t buf[3] = {0};
    if (_stretch) {
        _command(hold_cmd);
        _connection.read(buf, 3);
        if (!_connection.ok()) return false;
    } else {
        _command(poll_cmd);
        bool got = false;
        for (uint8_t i = 0; i < 50; i++) {
            _connection.read(buf, 3);
            if (_connection.ok()) { got = true; break; }
            periph_delay_ms(1);                        // read header NACKed: not ready yet
        }
        if (!got) return false;
    }
    if (_crc8(buf, 2) != buf[2]) return false;
    code = (uint16_t)((buf[0] << 8) | buf[1]);
    return true;
}

bool Si7021Minimal::_previous_code(uint16_t& code) {
    _command(CMD_T_PREVIOUS);
    uint8_t buf[2] = {0};
    _connection.read(buf, 2);
    if (!_connection.ok()) return false;
    code = (uint16_t)((buf[0] << 8) | buf[1]);
    return true;
}

float Si7021Minimal::_to_humidity(uint16_t code) {
    float rh = 125.0f * code / 65536.0f - 6.0f;
    if (rh < 0.0f) rh = 0.0f;
    if (rh > 100.0f) rh = 100.0f;
    return rh;
}

float Si7021Minimal::_to_temperature(uint16_t code) {
    return 175.72f * code / 65536.0f - 46.85f;
}

bool Si7021Minimal::read(float& temperature_c, float& humidity_pct) {
    uint16_t rh, t;
    if (!_measure(CMD_RH_HOLD, CMD_RH_POLL, rh)) return false;
    if (!_previous_code(t)) return false;
    humidity_pct = _to_humidity(rh);
    temperature_c = _to_temperature(t);
    return true;
}

// --- Full ---

Si7021Full::Si7021Full(Connection& connection)
    : Si7021Minimal(connection) {}

bool Si7021Full::read_raw_humidity(uint16_t& code) { return _measure(CMD_RH_HOLD, CMD_RH_POLL, code); }

bool Si7021Full::read_raw_temperature(uint16_t& code) { return _measure(CMD_T_HOLD, CMD_T_POLL, code); }

float Si7021Full::read_humidity() {
    uint16_t code;
    return read_raw_humidity(code) ? _to_humidity(code) : NAN;
}

float Si7021Full::read_temperature() {
    uint16_t code;
    return read_raw_temperature(code) ? _to_temperature(code) : NAN;
}

float Si7021Full::read_temperature_previous() {
    uint16_t code;
    return _previous_code(code) ? _to_temperature(code) : NAN;
}

uint8_t Si7021Full::read_user_register() {
    uint8_t cmd = CMD_READ_USER, value = 0;
    _connection.write_read(&cmd, 1, &value, 1);
    return value;
}

void Si7021Full::write_user_register(uint8_t value) {
    uint8_t keep = USER_RESERVED | USER_VDDS;
    uint8_t merged = (uint8_t)((read_user_register() & keep) | (value & ~keep));
    uint8_t buf[2] = {CMD_WRITE_USER, merged};
    _connection.write(buf, 2);
}

bool Si7021Full::set_resolution(uint8_t rh_bits, uint8_t temperature_bits) {
    for (uint8_t res = 0; res < 4; res++) {
        if (RES_RH[res] == rh_bits && RES_T[res] == temperature_bits) {
            uint8_t bits = (uint8_t)(((res >> 1) << 7) | (res & 1));
            write_user_register((uint8_t)((read_user_register() & ~USER_RES_MASK) | bits));
            return true;
        }
    }
    return false;
}

void Si7021Full::get_resolution(uint8_t& rh_bits, uint8_t& temperature_bits) {
    uint8_t reg = read_user_register();
    uint8_t res = (uint8_t)((((reg >> 7) & 1) << 1) | (reg & 1));
    rh_bits = RES_RH[res];
    temperature_bits = RES_T[res];
}

void Si7021Full::set_heater(bool on) {
    uint8_t reg = read_user_register();
    write_user_register(on ? (uint8_t)(reg | USER_HTRE) : (uint8_t)(reg & ~USER_HTRE));
}

bool Si7021Full::is_heater_on() { return (read_user_register() & USER_HTRE) != 0; }

bool Si7021Full::vdd_low() { return (read_user_register() & USER_VDDS) != 0; }

void Si7021Full::set_clock_stretching(bool on) { _stretch = on; }

void Si7021Full::software_reset() {
    _command(CMD_RESET);
    periph_delay_ms(15);
}

uint64_t Si7021Full::serial_number() {
    uint8_t c1[2] = {0xFA, 0x0F}, c2[2] = {0xFC, 0xC9};
    uint8_t a[8] = {0}, b[6] = {0};
    _connection.write_read(c1, 2, a, 8);
    _connection.write_read(c2, 2, b, 6);
    const uint8_t parts[8] = {a[0], a[2], a[4], a[6], b[0], b[1], b[3], b[4]};
    uint64_t sn = 0;
    for (uint8_t i = 0; i < 8; i++) sn = (sn << 8) | parts[i];
    return sn;
}

uint8_t Si7021Full::device_type() {
    uint8_t cmd[2] = {0xFC, 0xC9}, b[6] = {0};
    _connection.write_read(cmd, 2, b, 6);
    return b[0];
}

uint8_t Si7021Full::firmware_revision() {
    uint8_t cmd[2] = {0x84, 0xB8}, value = 0;
    _connection.write_read(cmd, 2, &value, 1);
    return value;
}

float Si7021Full::conversion_time() {
    static const float RH_MS[4] = {10.0f, 2.6f, 3.7f, 5.8f};
    static const float T_MS[4]  = {7.0f, 2.4f, 4.0f, 1.5f};
    uint8_t reg = read_user_register();
    uint8_t res = (uint8_t)((((reg >> 7) & 1) << 1) | (reg & 1));
    return (RH_MS[res] + T_MS[res]) / 1000.0f;
}

float Si7021Full::dew_point() {
    float t, rh;
    if (!read(t, rh)) return NAN;
    if (rh <= 0.0f) rh = 0.01f;
    float gamma = logf(rh / 100.0f) + (17.62f * t) / (243.12f + t);
    return 243.12f * gamma / (17.62f - gamma);
}
