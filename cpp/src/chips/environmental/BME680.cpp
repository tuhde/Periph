#include "BME680.h"
#include <stdlib.h>
#include <cmath>

#ifndef ARDUINO
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif

static const int32_t CONST_ARRAY1[16] = {
    2147483647, 2147483647, 2147483647, 2147483647, 2147483647,
    2126008810, 2147483647, 2130303777, 2147483647, 2147483647,
    2143188679, 2136746228, 2147483647, 2126008810, 2147483647,
    2147483647
};

static const uint32_t CONST_ARRAY2[16] = {
    4096000000U, 2048000000U, 1024000000U, 512000000U, 255744255U,
    127110228U, 64000000U, 32258064U, 16016016U, 8000000U,
    4000000U, 2000000U, 1000000U, 500000U, 250000U,
    125000U
};

BME680Minimal::BME680Minimal(Transport& transport)
    : _transport(transport) {
    _read_calibration();
    _write_reg(REG_CTRL_HUM, _osrs_h);
    _write_reg(REG_CTRL_MEAS, (_osrs_t << 5) | (_osrs_p << 2) | 0);
    _write_reg(REG_CONFIG, 0);
    _setup_heater(0, _heat_temp, _heat_dur);
    _write_reg(REG_CTRL_GAS_1, (1 << 4) | 0);
}

void BME680Minimal::_read_calibration() {
    uint8_t b1[23], b2[14], s1[1], s2[1], s3[1];
    _read_reg(REG_CAL_BLOCK1, b1, 23);
    _read_reg(REG_CAL_BLOCK2, b2, 14);
    _read_reg(REG_RES_HEAT_VAL, s1, 1);
    _read_reg(REG_RES_HEAT_RANGE, s2, 1);
    _read_reg(REG_RANGE_SW_ERR, s3, 1);

    _par_T2 = (int16_t)(b1[0] | (b1[1] << 8));
    _par_T3 = (int8_t)b1[2];
    _par_P1 = (uint16_t)(b1[4] | (b1[5] << 8));
    _par_P2 = (int16_t)(b1[6] | (b1[7] << 8));
    _par_P3 = (int8_t)b1[8];
    _par_P4 = (int16_t)(b1[10] | (b1[11] << 8));
    _par_P5 = (int16_t)(b1[12] | (b1[13] << 8));
    _par_P7 = (int8_t)b1[14];
    _par_P6 = (int8_t)b1[15];
    _par_P8 = (int16_t)(b1[18] | (b1[19] << 8));
    _par_P9 = (int16_t)(b1[20] | (b1[21] << 8));
    _par_P10 = b1[22];

    _par_H2 = (uint16_t)((b2[0] << 4) | (b2[1] >> 4));
    _par_H1 = (uint16_t)((b2[2] << 4) | (b2[1] & 0x0F));
    _par_H3 = (int8_t)b2[3];
    _par_H4 = (int8_t)b2[4];
    _par_H5 = (int8_t)b2[5];
    _par_H6 = b2[6];
    _par_H7 = (int8_t)b2[7];
    _par_T1 = (uint16_t)(b2[8] | (b2[9] << 8));
    _par_G2 = (int16_t)(b2[10] | (b2[11] << 8));
    _par_G1 = (int8_t)b2[12];
    _par_G3 = (int8_t)b2[13];

    _res_heat_val = (int8_t)s1[0];
    _res_heat_range = (s2[0] >> 4) & 0x03;
    uint8_t rse = (s3[0] >> 4) & 0x0F;
    _range_switching_error = (rse < 8) ? (int8_t)rse : (int8_t)(rse - 16);
}

void BME680Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

void BME680Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _transport.write_read(&reg, 1, buf, len);
}

uint8_t BME680Minimal::_calc_heater_resistance(int16_t target_temp, float ambient_temp) {
    int32_t var1 = (((int32_t)ambient_temp * _par_G3) / 10) << 8;
    int32_t var2 = (_par_G1 + 784) * (((((_par_G2 + 154009) * target_temp * 5) / 100) + 3276800) / 10);
    int32_t var3 = var1 + (var2 >> 1);
    int32_t var4 = var3 / (_res_heat_range + 4);
    int32_t var5 = (131 * _res_heat_val) + 65536;
    int32_t res_heat_x100 = ((var4 / var5) - 250) * 34;
    int32_t res_heat_x = (res_heat_x100 + 50) / 100;
    return (uint8_t)(res_heat_x & 0xFF);
}

uint8_t BME680Minimal::_calc_gas_wait(uint16_t target_ms) {
    if (target_ms <= 0x3F) {
        return (uint8_t)target_ms;
    } else if (target_ms <= 0x3F * 4) {
        return (uint8_t)((1 << 6) | (target_ms / 4));
    } else if (target_ms <= 0x3F * 16) {
        return (uint8_t)((2 << 6) | (target_ms / 16));
    } else {
        uint16_t val = target_ms / 64;
        if (val > 0x3F) val = 0x3F;
        return (uint8_t)((3 << 6) | val);
    }
}

void BME680Minimal::_setup_heater(uint8_t index, int16_t temp_c, uint16_t dur_ms) {
    uint8_t res = _calc_heater_resistance(temp_c, _ambient_temp);
    uint8_t gw = _calc_gas_wait(dur_ms);
    _write_reg(0x5A + index, res);
    _write_reg(0x64 + index, gw);
}

void BME680Minimal::_trigger_and_read(uint32_t& press_adc, uint32_t& temp_adc,
                                       uint16_t& hum_adc, uint16_t& gas_adc,
                                       uint8_t& gas_range, uint8_t& gas_valid,
                                       uint8_t& heat_stab) {
    _write_reg(REG_CTRL_HUM, _osrs_h);
    uint8_t ctrl = (_osrs_t << 5) | (_osrs_p << 2) | 1;
    _write_reg(REG_CTRL_MEAS, ctrl);
    delay(MEAS_TIME_MS);
    uint8_t raw[13];
    _read_reg(REG_PRESS_MSB, raw, 13);
    press_adc = ((uint32_t)raw[0] << 12) | ((uint32_t)raw[1] << 4) | (raw[2] >> 4);
    temp_adc  = ((uint32_t)raw[3] << 12) | ((uint32_t)raw[4] << 4) | (raw[5] >> 4);
    hum_adc   = ((uint16_t)raw[6] << 8) | raw[7];
    gas_adc   = ((uint16_t)raw[11] << 2) | (raw[12] >> 6);
    gas_range = raw[12] & 0x0F;
    gas_valid = (raw[12] >> 5) & 1;
    heat_stab = (raw[12] >> 4) & 1;
}

float BME680Minimal::_compensate_temp(uint32_t adc_T) {
    int32_t var1 = (int32_t)(adc_T >> 3) - ((int32_t)_par_T1 << 1);
    int32_t var2 = (var1 * (int32_t)_par_T2) >> 11;
    int32_t var3 = (((var1 >> 1) * (var1 >> 1)) >> 12) * ((int32_t)_par_T3 << 4) >> 14;
    _t_fine = var2 + var3;
    return (float)((_t_fine * 5 + 128) >> 8) / 100.0f;
}

float BME680Minimal::_compensate_pressure(uint32_t adc_P) {
    int32_t t_fine = _t_fine;
    int32_t var1 = (t_fine >> 1) - 64000;
    int32_t var2 = ((((var1 >> 2) * (var1 >> 2)) >> 11) * (int32_t)_par_P6) >> 2;
    var2 = var2 + ((var1 * (int32_t)_par_P5) << 1);
    var2 = (var2 >> 2) + ((int32_t)_par_P4 << 16);
    var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * ((int32_t)_par_P3 << 5)) >> 3) + (((int32_t)_par_P2 * var1) >> 1);
    var1 = var1 >> 18;
    var1 = ((32768 + var1) * (int32_t)_par_P1) >> 15;
    int32_t press_comp = 1048576 - (int32_t)adc_P;
    press_comp = (int32_t)(((int64_t)(press_comp - (var2 >> 12)) * 3125));
    if (press_comp >= (1 << 30)) {
        press_comp = (press_comp / var1) << 1;
    } else {
        press_comp = (press_comp << 1) / var1;
    }
    var1 = ((int32_t)_par_P9 * (((press_comp >> 3) * (press_comp >> 3)) >> 13)) >> 12;
    var2 = ((press_comp >> 2) * (int32_t)_par_P8) >> 13;
    int32_t var3 = ((press_comp >> 8) * (press_comp >> 8) * (press_comp >> 8) * (int32_t)_par_P10) >> 17;
    press_comp = press_comp + ((var1 + var2 + var3 + ((int32_t)_par_P7 << 7)) >> 4);
    return (float)press_comp / 100.0f;
}

float BME680Minimal::_compensate_humidity(uint16_t hum_adc) {
    int32_t temp_scaled = _t_fine;
    int32_t var1 = (int32_t)hum_adc - (((int32_t)_par_H1 << 4) + (((temp_scaled * (int32_t)_par_H3) / 100) >> 1));
    int32_t var2 = ((int32_t)_par_H2 * (((temp_scaled * (int32_t)_par_H4) / 100) +
                     (((temp_scaled * ((temp_scaled * (int32_t)_par_H5) / 100)) >> 6) / 100) +
                     (1 << 14))) >> 10;
    int32_t var3 = var1 * var2;
    int32_t var4 = (((int32_t)_par_H6 << 7) + ((temp_scaled * (int32_t)_par_H7) / 100)) >> 4;
    int32_t var5 = ((var3 >> 14) * (var3 >> 14)) >> 10;
    int32_t var6 = (var4 * var5) >> 1;
    int32_t hum_comp = (((var3 + var6) >> 10) * 1000) >> 12;
    if (hum_comp < 0) hum_comp = 0;
    if (hum_comp > 100000) hum_comp = 100000;
    return (float)hum_comp / 1000.0f;
}

float BME680Minimal::_compensate_gas(uint16_t gas_adc, uint8_t gas_range) {
    int32_t rse = (int32_t)_range_switching_error;
    int64_t var1 = ((int64_t)(1340 + 5 * rse) * (int64_t)CONST_ARRAY1[gas_range]) >> 16;
    int64_t var2 = (((int64_t)gas_adc << 15) - (int64_t)(1 << 24)) + var1;
    if (var2 == 0) return NAN;
    int64_t gas_res = (((int64_t)CONST_ARRAY2[gas_range] * var1) >> 9) + (var2 >> 1);
    gas_res = gas_res / var2;
    return (float)gas_res;
}

float BME680Minimal::temperature() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    float t = _compensate_temp(temp_adc);
    _ambient_temp = t;
    return t;
}

float BME680Minimal::pressure() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    _compensate_temp(temp_adc);
    return _compensate_pressure(press_adc);
}

float BME680Minimal::humidity() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    _compensate_temp(temp_adc);
    return _compensate_humidity(hum_adc);
}

float BME680Minimal::gas_resistance() {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    _compensate_temp(temp_adc);
    if (!gas_valid || !heat_stab) return NAN;
    return _compensate_gas(gas_adc, gas_range);
}

// BME680Full

BME680Full::BME680Full(Transport& transport)
    : BME680Minimal(transport) {
}

void BME680Full::configure(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h, uint8_t mode, uint8_t filter) {
    _osrs_t = osrs_t;
    _osrs_p = osrs_p;
    _osrs_h = osrs_h;
    _filter = filter;
    _write_reg(REG_CTRL_HUM, osrs_h);
    _write_reg(REG_CONFIG, filter << 2);
    _write_reg(REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | mode);
}

void BME680Full::set_oversampling(uint8_t osrs_t, uint8_t osrs_p, uint8_t osrs_h) {
    _osrs_t = osrs_t;
    _osrs_p = osrs_p;
    _osrs_h = osrs_h;
    _write_reg(REG_CTRL_HUM, osrs_h);
    _write_reg(REG_CTRL_MEAS, (osrs_t << 5) | (osrs_p << 2) | 0);
}

void BME680Full::set_filter(uint8_t coeff) {
    _filter = coeff;
    _write_reg(REG_CONFIG, coeff << 2);
}

void BME680Full::set_heater(int16_t temp_c, uint16_t duration_ms) {
    _heat_temp = temp_c;
    _heat_dur = duration_ms;
    _setup_heater(0, temp_c, duration_ms);
    _write_reg(REG_CTRL_GAS_1, (1 << 4) | 0);
}

void BME680Full::set_heater_profile(uint8_t index, int16_t temp_c, uint16_t duration_ms) {
    _setup_heater(index, temp_c, duration_ms);
}

void BME680Full::select_heater_profile(uint8_t index) {
    _nb_conv = index;
    uint8_t gas1 = _gas_enabled ? ((1 << 4) | index) : index;
    _write_reg(REG_CTRL_GAS_1, gas1);
}

void BME680Full::set_gas_enabled(bool enabled) {
    _gas_enabled = enabled ? 1 : 0;
    uint8_t gas1 = enabled ? ((1 << 4) | _nb_conv) : _nb_conv;
    _write_reg(REG_CTRL_GAS_1, gas1);
}

void BME680Full::set_heater_off(bool off) {
    _write_reg(REG_CTRL_GAS_0, off ? 0x08 : 0x00);
}

void BME680Full::set_ambient_temperature(float temp_c) {
    _ambient_temp = temp_c;
    _setup_heater(_nb_conv, (int16_t)_heat_temp, _heat_dur);
}

void BME680Full::read_all(float& t, float& p, float& h, float& g) {
    uint32_t press_adc, temp_adc;
    uint16_t hum_adc, gas_adc;
    uint8_t gas_range, gas_valid, heat_stab;
    _trigger_and_read(press_adc, temp_adc, hum_adc, gas_adc, gas_range, gas_valid, heat_stab);
    t = _compensate_temp(temp_adc);
    _ambient_temp = t;
    p = _compensate_pressure(press_adc);
    h = _compensate_humidity(hum_adc);
    g = (gas_valid && heat_stab) ? _compensate_gas(gas_adc, gas_range) : NAN;
}

bool BME680Full::gas_valid() {
    uint8_t buf[1];
    _read_reg(0x2B, buf, 1);
    return (buf[0] >> 5) & 1;
}

bool BME680Full::heater_stable() {
    uint8_t buf[1];
    _read_reg(0x2B, buf, 1);
    return (buf[0] >> 4) & 1;
}

uint8_t BME680Full::status() {
    uint8_t buf[1];
    _read_reg(REG_MEAS_STATUS, buf, 1);
    return buf[0];
}

uint8_t BME680Full::chip_id() {
    uint8_t buf[1];
    _read_reg(REG_ID, buf, 1);
    return buf[0];
}

void BME680Full::reset() {
    _write_reg(REG_RESET, RESET_CMD);
    delay(2);
    _read_calibration();
    _write_reg(REG_CTRL_HUM, _osrs_h);
    _write_reg(REG_CONFIG, _filter << 2);
    _write_reg(REG_CTRL_MEAS, (_osrs_t << 5) | (_osrs_p << 2) | 0);
    _setup_heater(_nb_conv, (int16_t)_heat_temp, _heat_dur);
    uint8_t gas1 = _gas_enabled ? ((1 << 4) | _nb_conv) : _nb_conv;
    _write_reg(REG_CTRL_GAS_1, gas1);
}
