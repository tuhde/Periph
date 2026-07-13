#include "BMP280.h"
#include <stdlib.h>
#include <cmath>

#ifndef ARDUINO
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

BMP280Minimal::BMP280Minimal(Transport& transport, bool spi)
    : _transport(transport), _spi(spi) {
    _read_calibration();
    _write_reg(REG_CTRL_MEAS, (1 << 5) | (1 << 2) | 0);
    _write_reg(REG_CONFIG, 0);
}

void BMP280Minimal::_read_calibration() {
    uint8_t buf[24];
    _read_reg(REG_CAL_START, buf, 24);

    _dig_T1 = (uint16_t)(buf[0] | (buf[1] << 8));
    _dig_T2 = (int16_t)(buf[2] | (buf[3] << 8));
    _dig_T3 = (int16_t)(buf[4] | (buf[5] << 8));
    _dig_P1 = (uint16_t)(buf[6] | (buf[7] << 8));
    _dig_P2 = (int16_t)(buf[8] | (buf[9] << 8));
    _dig_P3 = (int16_t)(buf[10] | (buf[11] << 8));
    _dig_P4 = (int16_t)(buf[12] | (buf[13] << 8));
    _dig_P5 = (int16_t)(buf[14] | (buf[15] << 8));
    _dig_P6 = (int16_t)(buf[16] | (buf[17] << 8));
    _dig_P7 = (int16_t)(buf[18] | (buf[19] << 8));
    _dig_P8 = (int16_t)(buf[20] | (buf[21] << 8));
    _dig_P9 = (int16_t)(buf[22] | (buf[23] << 8));
}

void BMP280Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t addr = _spi ? (reg & 0x7F) : reg;
    uint8_t buf[2] = { addr, value };
    _transport.write(buf, 2);
}

void BMP280Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    uint8_t addr = reg;
    _transport.write_read(&addr, 1, buf, len);
}

void BMP280Minimal::_trigger_and_read(uint32_t& adc_P, uint32_t& adc_T) {
    if (_mode != 3) {
        uint8_t ctrl = (_osrs_t << 5) | (_osrs_p << 2) | 1;
        _write_reg(REG_CTRL_MEAS, ctrl);
        delay(MEAS_TIME_MS);
    }
    uint8_t raw[6];
    _read_reg(REG_DATA_START, raw, 6);
    adc_P = ((uint32_t)raw[0] << 12) | ((uint32_t)raw[1] << 4) | (raw[2] >> 4);
    adc_T = ((uint32_t)raw[3] << 12) | ((uint32_t)raw[4] << 4) | (raw[5] >> 4);
}

float BMP280Minimal::_compensate_temp(uint32_t adc_T) {
    int64_t var1 = ((((int64_t)adc_T >> 3) - ((int64_t)_dig_T1 << 1)) * (int64_t)_dig_T2) >> 11;
    int64_t var2 = ((((((int64_t)adc_T >> 4) - (int64_t)_dig_T1) * (((int64_t)adc_T >> 4) - (int64_t)_dig_T1)) >> 12) * (int64_t)_dig_T3) >> 14;
    _t_fine = (int32_t)(var1 + var2);
    return (float)(((_t_fine * 5 + 128) >> 8)) / 100.0f;
}

float BMP280Minimal::_compensate_pressure(uint32_t adc_P) {
    int64_t t_fine = _t_fine;
    int64_t var1 = t_fine - 128000;
    int64_t var2 = var1 * var1 * (int64_t)_dig_P6;
    var2 = var2 + ((var1 * (int64_t)_dig_P5) << 17);
    var2 = var2 + ((int64_t)_dig_P4 << 35);
    var1 = ((var1 * var1 * (int64_t)_dig_P3) >> 8) + ((var1 * (int64_t)_dig_P2) << 12);
    var1 = ((((int64_t)1 << 47) + var1) * (int64_t)_dig_P1) >> 33;
    if (var1 == 0) return 0.0f;
    int64_t p = 1048576 - adc_P;
    p = (((p << 31) - var2) * 3125) / var1;
    var1 = ((int64_t)_dig_P9 * (p >> 13) * (p >> 13)) >> 25;
    var2 = ((int64_t)_dig_P8 * p) >> 19;
    p = ((p + var1 + var2) >> 8) + ((int64_t)_dig_P7 << 4);
    return (float)(p / 256.0) / 100.0f;
}

float BMP280Minimal::temperature() {
    uint32_t adc_P, adc_T;
    _trigger_and_read(adc_P, adc_T);
    return _compensate_temp(adc_T);
}

float BMP280Minimal::pressure() {
    uint32_t adc_P, adc_T;
    _trigger_and_read(adc_P, adc_T);
    _compensate_temp(adc_T);
    return _compensate_pressure(adc_P);
}

// BMP280Full

BMP280Full::BMP280Full(Transport& transport, bool spi)
    : BMP280Minimal(transport, spi) {
}

void BMP280Full::configure(uint8_t osrs_t, uint8_t osrs_p, uint8_t mode, uint8_t filter, uint8_t t_sb) {
    _osrs_t = osrs_t;
    _osrs_p = osrs_p;
    _mode = mode;
    _filter = filter;
    _t_sb = t_sb;
    _write_reg(REG_CONFIG, (t_sb << 5) | (filter << 2));
    _write_reg(REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | mode);
}

void BMP280Full::set_oversampling(uint8_t osrs_t, uint8_t osrs_p) {
    _osrs_t = osrs_t;
    _osrs_p = osrs_p;
    _write_reg(REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | _mode);
}

void BMP280Full::set_mode(uint8_t mode) {
    _mode = mode;
    _write_reg(REG_CTRL_MEAS, (_osrs_t << 5) | (_osrs_p << 2) | mode);
}

void BMP280Full::set_filter(uint8_t coeff) {
    _filter = coeff;
    _write_reg(REG_CONFIG, (_t_sb << 5) | (coeff << 2));
}

void BMP280Full::set_standby(uint8_t t_sb) {
    _t_sb = t_sb;
    _write_reg(REG_CONFIG, (t_sb << 5) | (_filter << 2));
}

uint8_t BMP280Full::status() {
    uint8_t buf[1];
    _read_reg(REG_STATUS, buf, 1);
    return buf[0];
}

float BMP280Full::altitude(float sea_level_hpa) {
    float p = pressure();
    return 44330.0f * (1.0f - powf(p / sea_level_hpa, 0.1903f));
}

float BMP280Full::sea_level_pressure(float altitude_m) {
    float p = pressure();
    return p / powf(1.0f - (altitude_m / 44330.0f), 5.255f);
}

uint8_t BMP280Full::chip_id() {
    uint8_t buf[1];
    _read_reg(REG_ID, buf, 1);
    return buf[0];
}

void BMP280Full::reset() {
    _write_reg(REG_RESET, RESET_CMD);
    delay(2);
    _read_calibration();
    _write_reg(REG_CONFIG, (_t_sb << 5) | (_filter << 2));
    _write_reg(REG_CTRL_MEAS, (_osrs_t << 5) | (_osrs_p << 2) | _mode);
}
