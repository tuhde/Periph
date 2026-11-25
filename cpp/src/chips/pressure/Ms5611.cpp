#include "Ms5611.h"
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

static const uint16_t OSR_VALUES[5] = {256, 512, 1024, 2048, 4096};
// maximum conversion time per OSR in microseconds (datasheet)
static const uint16_t T_CONV_US[5] = {600, 1170, 2280, 4540, 9040};

// C-style truncating integer division by a power of two
static int64_t div_pow2(int64_t a, int shift) {
    int64_t d = (int64_t)1 << shift;
    return a / d;
}

// --- Minimal ---

MS5611Minimal::MS5611Minimal(Connection& connection)
    : _connection(connection) {}

uint8_t MS5611Minimal::_crc4(const uint16_t words[8]) {
    uint16_t prom[8];
    for (int i = 0; i < 8; i++) prom[i] = words[i];
    prom[7] &= 0xFF00;
    uint16_t n_rem = 0;
    for (int cnt = 0; cnt < 16; cnt++) {
        if (cnt & 1) n_rem ^= (uint16_t)(prom[cnt >> 1] & 0x00FF);
        else         n_rem ^= (uint16_t)(prom[cnt >> 1] >> 8);
        for (int b = 8; b > 0; b--)
            n_rem = (n_rem & 0x8000) ? (uint16_t)((n_rem << 1) ^ 0x3000) : (uint16_t)(n_rem << 1);
    }
    return (uint8_t)((n_rem >> 12) & 0x0F);
}

uint8_t MS5611Minimal::_wait_ms(uint8_t osr_index) {
    return (uint8_t)((T_CONV_US[osr_index] + 999) / 1000 + 1);
}

bool MS5611Minimal::_read_prom_words(uint16_t out[8]) {
    for (uint8_t n = 0; n < 8; n++) {
        uint8_t cmd = (uint8_t)(CMD_PROM_READ + 2 * n);
        uint8_t buf[2] = {0};
        _connection.write(&cmd, 1);
        if (!_connection.ok()) return false;
        _connection.read(buf, 2);
        if (!_connection.ok()) return false;
        out[n] = (uint16_t)((buf[0] << 8) | buf[1]);
    }
    return true;
}

bool MS5611Minimal::_load_prom() {
    uint16_t words[8];
    if (!_read_prom_words(words)) return false;
    if (_crc4(words) != (words[7] & 0x0F)) return false;
    for (int i = 0; i < 8; i++) _prom[i] = words[i];
    return true;
}

bool MS5611Minimal::_read_adc(uint32_t& value) {
    uint8_t cmd = CMD_ADC_READ;
    uint8_t buf[3] = {0};
    _connection.write(&cmd, 1);
    if (!_connection.ok()) return false;
    _connection.read(buf, 3);
    if (!_connection.ok()) return false;
    value = ((uint32_t)buf[0] << 16) | ((uint32_t)buf[1] << 8) | buf[2];
    return value != 0;
}

bool MS5611Minimal::_convert(uint8_t kind, uint32_t& value) {
    uint8_t cmd = (uint8_t)((kind ? CMD_CONVERT_D2 : CMD_CONVERT_D1) | (_osr_index << 1));
    _connection.write(&cmd, 1);
    if (!_connection.ok()) return false;
    periph_delay_ms(_wait_ms(_osr_index));
    return _read_adc(value);
}

void MS5611Minimal::_compensate(uint32_t d1, uint32_t d2, bool second_order, float& t, float& p) const {
    int64_t c1 = _prom[1], c2 = _prom[2], c3 = _prom[3], c4 = _prom[4], c5 = _prom[5], c6 = _prom[6];
    int64_t dt   = (int64_t)d2 - c5 * 256;
    int64_t temp = 2000 + div_pow2(dt * c6, 23);
    int64_t off  = c2 * 65536 + div_pow2(c4 * dt, 7);
    int64_t sens = c1 * 32768 + div_pow2(c3 * dt, 8);
    if (second_order && temp < 2000) {
        int64_t d = temp - 2000;
        int64_t t2 = div_pow2(dt * dt, 31);
        int64_t off2 = 5 * d * d / 2;
        int64_t sens2 = 5 * d * d / 4;
        if (temp < -1500) {
            int64_t e = temp + 1500;
            off2 += 7 * e * e;
            sens2 += 11 * e * e / 2;
        }
        temp -= t2;
        off -= off2;
        sens -= sens2;
    }
    int64_t pr = div_pow2(div_pow2((int64_t)d1 * sens, 21) - off, 15);
    t = (float)temp / 100.0f;
    p = (float)pr;
}

bool MS5611Minimal::init() {
    uint8_t cmd = CMD_RESET;
    _connection.write(&cmd, 1);
    if (!_connection.ok()) return false;
    periph_delay_ms(3);
    return _load_prom();
}

bool MS5611Minimal::read(float& temperature_c, float& pressure_pa) {
    uint32_t d1, d2;
    if (!_convert(1, d2)) return false;
    if (!_convert(0, d1)) return false;
    _compensate(d1, d2, true, temperature_c, pressure_pa);
    return true;
}

// --- Full ---

MS5611Full::MS5611Full(Connection& connection)
    : MS5611Minimal(connection) {}

bool MS5611Full::read_raw(uint32_t& d1, uint32_t& d2) {
    return _convert(1, d2) && _convert(0, d1);
}

float MS5611Full::read_pressure() {
    float t, p;
    return read(t, p) ? p : NAN;
}

float MS5611Full::read_temperature() {
    uint32_t d2;
    if (!_convert(1, d2)) return NAN;
    float t, p;
    _compensate(0, d2, true, t, p);
    return t;
}

bool MS5611Full::set_oversampling(uint16_t osr) {
    for (uint8_t i = 0; i < 5; i++) {
        if (OSR_VALUES[i] == osr) { _osr_index = i; return true; }
    }
    return false;
}

uint16_t MS5611Full::get_oversampling() const { return OSR_VALUES[_osr_index]; }

float MS5611Full::conversion_time(uint16_t osr) const {
    if (osr == 0) return (float)T_CONV_US[_osr_index] / 1.0e6f;
    for (uint8_t i = 0; i < 5; i++) {
        if (OSR_VALUES[i] == osr) return (float)T_CONV_US[i] / 1.0e6f;
    }
    return 0.0f;
}

bool MS5611Full::start_conversion(uint8_t kind, uint16_t osr) {
    uint8_t index = _osr_index;
    if (osr != 0) {
        index = 255;
        for (uint8_t i = 0; i < 5; i++) if (OSR_VALUES[i] == osr) index = i;
        if (index == 255) return false;
    }
    uint8_t cmd = (uint8_t)((kind == TEMPERATURE ? CMD_CONVERT_D2 : CMD_CONVERT_D1) | (index << 1));
    _connection.write(&cmd, 1);
    return _connection.ok();
}

bool MS5611Full::read_adc(uint32_t& value) { return _read_adc(value); }

void MS5611Full::compensate(uint32_t d1, uint32_t d2, float& temperature_c, float& pressure_pa, bool second_order) const {
    _compensate(d1, d2, second_order, temperature_c, pressure_pa);
}

bool MS5611Full::reset() { return init(); }

bool MS5611Full::read_prom(uint16_t words[8]) { return _read_prom_words(words); }

void MS5611Full::coefficients(uint16_t c[6]) const {
    for (int i = 0; i < 6; i++) c[i] = _prom[i + 1];
}

bool MS5611Full::prom_crc_ok() const { return _crc4(_prom) == (_prom[7] & 0x0F); }

uint16_t MS5611Full::serial_code() const { return (uint16_t)((_prom[7] >> 4) & 0x0FFF); }

float MS5611Full::altitude(float sea_level_pa) {
    float p = read_pressure();
    if (isnan(p)) return NAN;
    return 44330.0f * (1.0f - powf(p / sea_level_pa, 1.0f / 5.255f));
}
