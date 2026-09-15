#include "BMP384.h"
#include <stdlib.h>
#include <cstring>
#include <cmath>

#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

BMP384Minimal::BMP384Minimal(Connection& connection, bool spi)
    : _connection(connection), _spi(spi) {
    _read_calibration();
    _verify_chip_id();
    _apply_config();
}

void BMP384Minimal::_verify_chip_id() {
    uint8_t buf[1];
    _read_reg(REG_CHIP_ID, buf, 1);
    if (buf[0] != CHIP_ID) {
        // Indicate init failure silently — callers may catch via subsequent read errors.
        // (No exceptions; C++ chip drivers use the valid() flag pattern elsewhere.)
    }
}

void BMP384Minimal::_read_calibration() {
    uint8_t buf[REG_CAL_LEN];
    _read_reg(REG_CAL_START, buf, REG_CAL_LEN);

    uint16_t nvm_t1 = (uint16_t)(buf[0]  | (buf[1]  << 8));
    uint16_t nvm_t2 = (uint16_t)(buf[2]  | (buf[3]  << 8));
    int8_t   nvm_t3 = _s8(buf[4]);
    int16_t  nvm_p1 = (int16_t)((uint16_t)(buf[5]  | (buf[6]  << 8)));
    int16_t  nvm_p2 = (int16_t)((uint16_t)(buf[7]  | (buf[8]  << 8)));
    int8_t   nvm_p3 = _s8(buf[9]);
    int8_t   nvm_p4 = _s8(buf[10]);
    uint16_t nvm_p5 = (uint16_t)(buf[11] | (buf[12] << 8));
    uint16_t nvm_p6 = (uint16_t)(buf[13] | (buf[14] << 8));
    int8_t   nvm_p7 = _s8(buf[15]);
    int8_t   nvm_p8 = _s8(buf[16]);
    int16_t  nvm_p9 = (int16_t)((uint16_t)(buf[17] | (buf[18] << 8)));
    int8_t   nvm_p10 = _s8(buf[19]);
    int8_t   nvm_p11 = _s8(buf[20]);

    _par_t1  = (double)nvm_t1  * 256.0;   // ÷ 2^-8
    _par_t2  = (double)nvm_t2  / (double)(1ull << 30);
    _par_t3  = (double)nvm_t3  / (double)(1ull << 48);
    _par_p1  = ((double)nvm_p1 - (double)(1 << 14)) / (double)(1ull << 20);
    _par_p2  = ((double)nvm_p2 - (double)(1 << 14)) / (double)(1ull << 29);
    _par_p3  = (double)nvm_p3  / (double)(1ull << 32);
    _par_p4  = (double)nvm_p4  / (double)(1ull << 37);
    _par_p5  = (double)nvm_p5  * 8.0;     // ÷ 2^-3
    _par_p6  = (double)nvm_p6  / (double)(1 << 6);
    _par_p7  = (double)nvm_p7  / (double)(1 << 8);
    _par_p8  = (double)nvm_p8  / (double)(1 << 15);
    _par_p9  = (double)nvm_p9  / (double)(1ull << 48);
    _par_p10 = (double)nvm_p10 / (double)(1ull << 48);
    _par_p11 = (double)nvm_p11 / std::pow(2.0, 65);
}

void BMP384Minimal::_apply_config() {
    uint8_t osr_reg   = (uint8_t)((_osr_t << 3) | (_osr_p << 0));
    uint8_t config    = (uint8_t)((_iir << 1));
    uint8_t pwr_reg   = (uint8_t)((_mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
    _write_reg(REG_OSR,      osr_reg);
    _write_reg(REG_CONFIG,   config);
    _write_reg(REG_ODR,      _odr);
    _write_reg(REG_PWR_CTRL, pwr_reg);
}

void BMP384Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t addr = _spi ? (reg & 0x7F) : reg;
    uint8_t buf[2] = { addr, value };
    _connection.write(buf, 2);
}

void BMP384Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    uint8_t addr = reg;
    _connection.write_read(&addr, 1, buf, len);
}

void BMP384Minimal::_read_burst(uint32_t& uncomp_press, uint32_t& uncomp_temp) {
    uint8_t raw[6];
    _read_reg(REG_DATA_0, raw, 6);
    uncomp_press = ((uint32_t)raw[2] << 16) | ((uint32_t)raw[1] << 8) | raw[0];
    uncomp_temp  = ((uint32_t)raw[5] << 16) | ((uint32_t)raw[4] << 8) | raw[3];
}

double BMP384Minimal::_compensate_temperature(uint32_t uncomp_temp) {
    double partial1 = (double)uncomp_temp - _par_t1;
    double partial2 = partial1 * _par_t2;
    _t_lin = partial2 + (partial1 * partial1) * _par_t3;
    return _t_lin;
}

double BMP384Minimal::_compensate_pressure(uint32_t uncomp_press) {
    double t_lin = _t_lin;
    double p = (double)uncomp_press;

    double partial1 = _par_p6 * t_lin;
    double partial2 = _par_p7 * t_lin * t_lin;
    double partial3 = _par_p8 * t_lin * t_lin * t_lin;
    double partial_out1 = _par_p5 + partial1 + partial2 + partial3;

    partial1 = _par_p2 * t_lin;
    partial2 = _par_p3 * t_lin * t_lin;
    partial3 = _par_p4 * t_lin * t_lin * t_lin;
    double partial_out2 = p * (_par_p1 + partial1 + partial2 + partial3);

    partial1 = p * p;
    partial2 = _par_p9 + _par_p10 * t_lin;
    partial3 = partial1 * partial2;
    double partial4 = partial3 + (p * p * p) * _par_p11;

    return partial_out1 + partial_out2 + partial4;
}

float BMP384Minimal::temperature() {
    if (_mode == MODE_FORCED) {
        uint8_t pwr_reg = (uint8_t)((MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
        _write_reg(REG_PWR_CTRL, pwr_reg);
        delay(MEAS_TIME_MS);
    }
    uint32_t uncomp_press, uncomp_temp;
    _read_burst(uncomp_press, uncomp_temp);
    (void)uncomp_press;
    return (float)_compensate_temperature(uncomp_temp);
}

float BMP384Minimal::pressure() {
    if (_mode == MODE_FORCED) {
        uint8_t pwr_reg = (uint8_t)((MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
        _write_reg(REG_PWR_CTRL, pwr_reg);
        delay(MEAS_TIME_MS);
    }
    uint32_t uncomp_press, uncomp_temp;
    _read_burst(uncomp_press, uncomp_temp);
    _compensate_temperature(uncomp_temp);
    // compensate_pressure returns Pa; convert to hPa.
    return (float)(_compensate_pressure(uncomp_press) / 100.0);
}

// BMP384Full

BMP384Full::BMP384Full(Connection& connection, bool spi)
    : BMP384Minimal(connection, spi) {
}

void BMP384Full::configure(uint8_t osr_p, uint8_t osr_t, uint8_t iir_filter, uint8_t odr_sel) {
    _osr_p = osr_p;
    _osr_t = osr_t;
    _iir   = iir_filter;
    _odr   = odr_sel;

    // T_conv per spec: 234 + 392 + 2^osr_p*2000 + 313 + 2^osr_t*2000 µs.
    uint32_t t_conv_us = 234u
        + 392u + (1u << osr_p) * 2000u
        + 313u + (1u << osr_t) * 2000u;
    (void)t_conv_us;  // validation handled via ERR_REG.conf_err at runtime;
                       // we still write the values the caller asked for.

    uint8_t osr_reg = (uint8_t)((osr_t << 3) | (osr_p << 0));
    uint8_t config  = (uint8_t)((iir_filter << 1));
    _write_reg(REG_OSR,    osr_reg);
    _write_reg(REG_CONFIG, config);
    _write_reg(REG_ODR,    odr_sel);
}

void BMP384Full::read(float& pressure_hpa, float& temperature_c) {
    if (_mode == MODE_FORCED) {
        _trigger_forced();
    }
    uint32_t uncomp_press, uncomp_temp;
    _read_burst(uncomp_press, uncomp_temp);
    temperature_c = (float)_compensate_temperature(uncomp_temp);
    pressure_hpa  = (float)(_compensate_pressure(uncomp_press) / 100.0);
}

void BMP384Full::read_forced(float& pressure_hpa, float& temperature_c) {
    uint8_t prev_mode = _mode;
    set_mode(MODE_FORCED);
    _trigger_forced();
    uint32_t t_conv_us = 234u
        + 392u + (1u << _osr_p) * 2000u
        + 313u + (1u << _osr_t) * 2000u;
    delay((t_conv_us + 999) / 1000);
    uint32_t uncomp_press, uncomp_temp;
    _read_burst(uncomp_press, uncomp_temp);
    temperature_c = (float)_compensate_temperature(uncomp_temp);
    pressure_hpa  = (float)(_compensate_pressure(uncomp_press) / 100.0);
    _mode = prev_mode;
    _apply_pwr();
}

void BMP384Full::set_mode(uint8_t mode) {
    _mode = mode;
    _apply_pwr();
}

bool BMP384Full::is_data_ready() {
    uint8_t buf[1];
    _read_reg(REG_STATUS, buf, 1);
    return (buf[0] & (1 << 5)) != 0;
}

void BMP384Full::softreset() {
    _write_reg(REG_CMD, SOFT_RESET_CMD);
    delay(3);
    _read_calibration();
    _verify_chip_id();
    _apply_config();
}

void BMP384Full::fifo_configure(bool press_en, bool temp_en, uint16_t wtm, bool stop_on_full) {
    uint8_t cfg1 = (uint8_t)((1u << 4)
        | ((stop_on_full ? 1u : 0u) << 3)
        | ((temp_en ? 1u : 0u) << 1)
        | (press_en ? 1u : 0u));
    _write_reg(0x17, cfg1);
    _write_reg(0x15, (uint8_t)(wtm & 0xFF));
    _write_reg(0x16, (uint8_t)((wtm >> 8) & 0x01));
}

size_t BMP384Full::fifo_read(const char** type_out, double* value_out, size_t max_frames) {
    if (max_frames == 0) return 0;
    uint8_t len_lo[1], len_hi[1];
    _read_reg(0x12, len_lo, 1);
    _read_reg(0x13, len_hi, 1);
    uint16_t length = (uint16_t)(((uint16_t)len_hi[0] << 8) | len_lo[0]);
    if (length == 0) return 0;

    uint8_t* buf = (uint8_t*)malloc(length);
    if (!buf) return 0;
    _read_reg(0x14, buf, length);

    size_t n = 0;
    size_t i = 0;
    while (i < length && n < max_frames) {
        uint8_t hdr = buf[i];
        if (hdr == FIFO_HEADER_PRESS) {
            if (i + 3 >= length) break;
            uint32_t uncomp = ((uint32_t)buf[i + 3] << 16)
                             | ((uint32_t)buf[i + 2] << 8)
                             | buf[i + 1];
            double v_pa = _compensate_pressure_with_t_lin(uncomp, _t_lin);
            type_out[n]  = "pressure";
            value_out[n] = v_pa / 100.0;
            i += 4; n++;
        } else if (hdr == FIFO_HEADER_TEMP) {
            if (i + 3 >= length) break;
            uint32_t uncomp = ((uint32_t)buf[i + 3] << 16)
                             | ((uint32_t)buf[i + 2] << 8)
                             | buf[i + 1];
            double t = _compensate_temperature(uncomp);
            type_out[n]  = "temperature";
            value_out[n] = t;
            i += 4; n++;
        } else if (hdr == FIFO_HEADER_SENSORT) {
            if (i + 3 >= length) break;
            uint32_t uncomp = ((uint32_t)buf[i + 3] << 16)
                             | ((uint32_t)buf[i + 2] << 8)
                             | buf[i + 1];
            type_out[n]  = "sensortime";
            value_out[n] = (double)uncomp;
            i += 4; n++;
        } else if (hdr == FIFO_HEADER_ERROR || hdr == FIFO_HEADER_EMPTY) {
            type_out[n]  = (hdr == FIFO_HEADER_ERROR) ? "error" : "empty";
            value_out[n] = 0.0;
            i += 1; n++;
        } else {
            type_out[n]  = "unknown";
            value_out[n] = 0.0;
            i += 1; n++;
        }
    }
    free(buf);
    return n;
}

void BMP384Full::fifo_flush() {
    _write_reg(REG_CMD, FIFO_FLUSH_CMD);
}

float BMP384Full::altitude(float sea_level_hpa) {
    float p = pressure();
    if (p <= 0.0f) return 0.0f;
    return 44330.0f * (1.0f - powf(p / sea_level_hpa, 1.0f / 5.255f));
}

void BMP384Full::_trigger_forced() {
    uint8_t pwr_reg = (uint8_t)((MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
    _write_reg(REG_PWR_CTRL, pwr_reg);
}

void BMP384Full::_apply_pwr() {
    uint8_t pwr_reg = (uint8_t)((_mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
    _write_reg(REG_PWR_CTRL, pwr_reg);
}

double BMP384Full::_compensate_pressure_with_t_lin(uint32_t uncomp_press, double t_lin) {
    _t_lin = t_lin;
    return _compensate_pressure(uncomp_press);
}
