#include "Spl06.h"
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

// Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4)
static const int32_t SPL06_SCALE[8] = {524288, 1572864, 3670016, 7864320,
                                       253952, 516096, 1040384, 2088960};
// Typical measurement time in ms indexed by log2(oversampling), rounded up
static const uint8_t SPL06_MEAS_MS[8] = {4, 6, 9, 15, 28, 54, 105, 207};

// --- Minimal ---

SPL06Minimal::SPL06Minimal(RegisterConnection& connection)
    : _connection(connection) {}

bool SPL06Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
    return _connection.ok();
}

bool SPL06Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.read(reg, buf, len);
    return _connection.ok();
}

int32_t SPL06Minimal::_signed(uint32_t value, uint8_t bits) {
    uint32_t sign = 1UL << (bits - 1);
    value &= (sign << 1) - 1;
    return (value & sign) ? (int32_t)value - (int32_t)(sign << 1) : (int32_t)value;
}

int32_t SPL06Minimal::_raw24(const uint8_t* b) {
    return _signed(((uint32_t)b[0] << 16) | ((uint32_t)b[1] << 8) | b[2], 24);
}

float SPL06Minimal::_scale(uint8_t os_log2) {
    return (float)SPL06_SCALE[os_log2 & 7];
}

bool SPL06Minimal::_wait_ready() {
    const uint8_t mask = MEAS_COEF_RDY | MEAS_SENSOR_RDY;
    for (int i = 0; i < 40; i++) {
        uint8_t m;
        if (_read_reg(REG_MEAS_CFG, &m, 1) && (m & mask) == mask) return true;
        periph_delay_ms(5);
    }
    return false;
}

bool SPL06Minimal::_read_coefficients() {
    uint8_t b[18];
    if (!_read_reg(REG_COEF, b, sizeof(b))) return false;
    _coef.c0  = _signed(((uint32_t)b[0] << 4) | (b[1] >> 4), 12);
    _coef.c1  = _signed(((uint32_t)(b[1] & 0x0F) << 8) | b[2], 12);
    _coef.c00 = _signed(((uint32_t)b[3] << 12) | ((uint32_t)b[4] << 4) | (b[5] >> 4), 20);
    _coef.c10 = _signed(((uint32_t)(b[5] & 0x0F) << 16) | ((uint32_t)b[6] << 8) | b[7], 20);
    _coef.c01 = _signed(((uint32_t)b[8] << 8) | b[9], 16);
    _coef.c11 = _signed(((uint32_t)b[10] << 8) | b[11], 16);
    _coef.c20 = _signed(((uint32_t)b[12] << 8) | b[13], 16);
    _coef.c21 = _signed(((uint32_t)b[14] << 8) | b[15], 16);
    _coef.c30 = _signed(((uint32_t)b[16] << 8) | b[17], 16);
    return true;
}

bool SPL06Minimal::_apply_defaults() {
    _p_os = 4;
    _t_os = 0;
    _cfg = CFG_P_SHIFT;
    return _write_reg(REG_TMP_CFG, TMP_EXT) && _write_reg(REG_PRS_CFG, 0x04)
        && _write_reg(REG_CFG_REG, _cfg);
}

bool SPL06Minimal::init() {
    uint8_t id;
    if (!_read_reg(REG_ID, &id, 1) || id != CHIP_ID) return false;
    if (!_wait_ready()) return false;
    if (!_read_coefficients()) return false;
    return _apply_defaults();
}

bool SPL06Minimal::_measure(uint8_t command, uint8_t flag, uint8_t os_log2, int32_t& raw) {
    if (!_write_reg(REG_MEAS_CFG, command)) return false;
    unsigned wait = SPL06_MEAS_MS[os_log2 & 7];
    periph_delay_ms(wait);
    unsigned limit = wait + 50;
    uint8_t m = 0;
    for (unsigned waited = 0; ; waited += 2) {
        if (!_read_reg(REG_MEAS_CFG, &m, 1)) return false;
        if (m & flag) break;
        if (waited > limit) return false;
        periph_delay_ms(2);
    }
    uint8_t b[3];
    if (!_read_reg(command == CMD_PRESSURE ? REG_PRS_B2 : REG_TMP_B2, b, 3)) return false;
    raw = _raw24(b);
    return true;
}

bool SPL06Minimal::_measure_raw(int32_t& praw, int32_t& traw) {
    if (!_measure(CMD_TEMPERATURE, MEAS_TMP_RDY, _t_os, traw)) return false;
    if (!_measure(CMD_PRESSURE, MEAS_PRS_RDY, _p_os, praw)) return false;
    _last_traw = traw;
    return true;
}

float SPL06Minimal::_temperature(int32_t traw) const {
    return _coef.c0 * 0.5f + _coef.c1 * ((float)traw / _scale(_t_os));
}

float SPL06Minimal::_pressure(int32_t praw, int32_t traw) const {
    float p = (float)praw / _scale(_p_os);
    float t = (float)traw / _scale(_t_os);
    return _coef.c00 + p * (_coef.c10 + p * (_coef.c20 + p * _coef.c30))
         + t * _coef.c01 + t * p * (_coef.c11 + p * _coef.c21);
}

bool SPL06Minimal::read(float& temperature_c, float& pressure_pa) {
    int32_t praw, traw;
    if (!_measure_raw(praw, traw)) return false;
    temperature_c = _temperature(traw);
    pressure_pa = _pressure(praw, traw);
    return true;
}

// --- Full ---

SPL06Full* SPL06Full::_activeInstance = nullptr;

SPL06Full::SPL06Full(RegisterConnection& connection)
    : SPL06Minimal(connection) {}

int8_t SPL06Full::_log2(uint8_t value) {
    for (int8_t i = 0; i < 8; i++)
        if (value == (1u << i)) return i;
    return -1;
}

uint8_t SPL06Full::_int_bits(uint8_t source) {
    uint8_t bits = 0;
    if (source & SOURCE_PRESSURE)    bits |= CFG_INT_PRS;
    if (source & SOURCE_TEMPERATURE) bits |= CFG_INT_TMP;
    if (source & SOURCE_FIFO_FULL)   bits |= CFG_INT_FIFO;
    return bits;
}

bool SPL06Full::_write_cfg() {
    return _write_reg(REG_CFG_REG, _cfg);
}

bool SPL06Full::read(float& temperature_c, float& pressure_pa) {
    if (_continuous) return read_continuous(temperature_c, pressure_pa);
    return SPL06Minimal::read(temperature_c, pressure_pa);
}

bool SPL06Full::read_pressure(float& pressure_pa) {
    float t;
    return read(t, pressure_pa);
}

bool SPL06Full::read_temperature(float& temperature_c) {
    if (_continuous) {
        float p;
        return read_continuous(temperature_c, p);
    }
    int32_t traw;
    if (!_measure(CMD_TEMPERATURE, MEAS_TMP_RDY, _t_os, traw)) return false;
    _last_traw = traw;
    temperature_c = _temperature(traw);
    return true;
}

bool SPL06Full::read_raw(int32_t& praw, int32_t& traw) {
    if (_continuous) {
        uint8_t b[6];
        if (!_read_reg(REG_PRS_B2, b, 6)) return false;
        praw = _raw24(b);
        traw = _raw24(b + 3);
        _last_traw = traw;
        return true;
    }
    return _measure_raw(praw, traw);
}

bool SPL06Full::set_pressure_config(uint8_t oversampling, uint8_t rate) {
    int8_t os = _log2(oversampling), r = _log2(rate);
    if (os < 0 || r < 0) return false;
    if (!_write_reg(REG_PRS_CFG, (uint8_t)((r << 4) | os))) return false;
    _p_os = (uint8_t)os;
    _p_rate = (uint8_t)r;
    if (oversampling > 8) _cfg |= CFG_P_SHIFT; else _cfg &= (uint8_t)~CFG_P_SHIFT;
    return _write_cfg();
}

bool SPL06Full::set_temperature_config(uint8_t oversampling, uint8_t rate) {
    int8_t os = _log2(oversampling), r = _log2(rate);
    if (os < 0 || r < 0) return false;
    if (!_write_reg(REG_TMP_CFG, (uint8_t)(TMP_EXT | (r << 4) | os))) return false;
    _t_os = (uint8_t)os;
    _t_rate = (uint8_t)r;
    if (oversampling > 8) _cfg |= CFG_T_SHIFT; else _cfg &= (uint8_t)~CFG_T_SHIFT;
    return _write_cfg();
}

bool SPL06Full::start_continuous(bool pressure, bool temperature) {
    if (!pressure && !temperature) return false;
    uint8_t mode = (uint8_t)(0x04 | (pressure ? 0x01 : 0) | (temperature ? 0x02 : 0));
    if (!_write_reg(REG_MEAS_CFG, mode)) return false;
    _continuous = mode;
    return true;
}

bool SPL06Full::stop() {
    if (!_write_reg(REG_MEAS_CFG, CMD_IDLE)) return false;
    _continuous = 0;
    return true;
}

bool SPL06Full::read_continuous(float& temperature_c, float& pressure_pa) {
    uint8_t flags = 0;
    if (_continuous & 0x01) flags |= MEAS_PRS_RDY;
    if (_continuous & 0x02) flags |= MEAS_TMP_RDY;
    if (flags) {
        uint8_t slow = _p_rate < _t_rate ? _p_rate : _t_rate;
        unsigned limit = 2000u / (1u << slow) + 500;
        uint8_t m = 0;
        for (unsigned waited = 0; ; waited += 2) {
            if (!_read_reg(REG_MEAS_CFG, &m, 1)) return false;
            if ((m & flags) == flags) break;
            if (waited > limit) return false;
            periph_delay_ms(2);
        }
    }
    uint8_t b[6];
    if (!_read_reg(REG_PRS_B2, b, 6)) return false;
    int32_t praw = _raw24(b), traw = _raw24(b + 3);
    _last_traw = traw;
    temperature_c = _temperature(traw);
    pressure_pa = _pressure(praw, traw);
    return true;
}

bool SPL06Full::data_ready(bool& pressure_ready, bool& temperature_ready) {
    uint8_t m;
    if (!_read_reg(REG_MEAS_CFG, &m, 1)) return false;
    pressure_ready = (m & MEAS_PRS_RDY) != 0;
    temperature_ready = (m & MEAS_TMP_RDY) != 0;
    return true;
}

bool SPL06Full::enable_fifo() {
    _cfg |= CFG_FIFO_EN;
    return _write_cfg();
}

bool SPL06Full::disable_fifo() {
    _cfg &= (uint8_t)~CFG_FIFO_EN;
    return _write_cfg();
}

uint8_t SPL06Full::read_fifo(FifoEntry* entries, uint8_t max_entries) {
    uint8_t n = 0;
    while (n < max_entries && n < 32) {
        uint8_t s;
        if (!_read_reg(REG_FIFO_STS, &s, 1) || (s & 0x01)) break;
        uint8_t b[3];
        if (!_read_reg(REG_PRS_B2, b, 3)) break;
        int32_t raw = _raw24(b);
        if (raw == -0x800000L) break;
        if (raw & 1) {
            entries[n].kind = KIND_PRESSURE;
            entries[n].value = _pressure(raw & ~1L, _last_traw);
        } else {
            _last_traw = raw;
            entries[n].kind = KIND_TEMPERATURE;
            entries[n].value = _temperature(raw);
        }
        n++;
    }
    return n;
}

bool SPL06Full::flush_fifo() {
    return _write_reg(REG_RESET, 0x80);
}

bool SPL06Full::fifo_status(bool& empty, bool& full) {
    uint8_t s;
    if (!_read_reg(REG_FIFO_STS, &s, 1)) return false;
    empty = (s & 0x01) != 0;
    full = (s & 0x02) != 0;
    return true;
}

bool SPL06Full::reset() {
    _write_reg(REG_RESET, 0x09);  // the chip may not acknowledge while it resets
    periph_delay_ms(12);
    if (!_wait_ready() || !_read_coefficients() || !_apply_defaults()) return false;
    _p_rate = 0;
    _t_rate = 0;
    _continuous = 0;
    return true;
}

void SPL06Full::coefficients(SPL06Coefficients& coefficients) const {
    coefficients = _coef;
}

bool SPL06Full::compensate(int32_t praw, int32_t traw, uint8_t p_oversampling,
                           uint8_t t_oversampling, float& temperature_c, float& pressure_pa) const {
    int8_t po = _log2(p_oversampling), to = _log2(t_oversampling);
    if (po < 0 || to < 0) return false;
    float p = (float)praw / _scale((uint8_t)po);
    float t = (float)traw / _scale((uint8_t)to);
    temperature_c = _coef.c0 * 0.5f + _coef.c1 * t;
    pressure_pa = _coef.c00 + p * (_coef.c10 + p * (_coef.c20 + p * _coef.c30))
                + t * _coef.c01 + t * p * (_coef.c11 + p * _coef.c21);
    return true;
}

bool SPL06Full::altitude(float& altitude_m, float sea_level_pa) {
    float p;
    if (!read_pressure(p)) return false;
    altitude_m = (float)(44330.0 * (1.0 - pow((double)p / sea_level_pa, 1.0 / 5.255)));
    return true;
}

bool SPL06Full::enable_interrupt(uint8_t source) {
    _cfg |= _int_bits(source);
    return _write_cfg();
}

bool SPL06Full::disable_interrupt(uint8_t source) {
    _cfg &= (uint8_t)~_int_bits(source);
    return _write_cfg();
}

uint8_t SPL06Full::poll_interrupt() {
    uint8_t s;
    if (!_read_reg(REG_INT_STS, &s, 1)) return 0;
    return s & 0x07;
}

void SPL06Full::on_interrupt(void (*callback)(uint8_t), InputPin* intPin) {
    InputPin* p = intPin ? intPin : _connection.intPin();
    if (!p) return;  // no INT line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = p;
    _activeInstance = this;
    uint8_t edge = InputPin::kFalling;
    if (_cfg & CFG_INT_HL) edge = InputPin::kRising;
    p->onEdge(&SPL06Full::_edgeTrampoline, edge);
}

void SPL06Full::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&SPL06Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
    if (_activeInstance == this) _activeInstance = nullptr;
}

void SPL06Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void SPL06Full::_handleEdge() {
    uint8_t src = poll_interrupt();
    if (src && _callback) _callback(src);
}
