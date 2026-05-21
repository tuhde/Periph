#include "BMP280.h"
#include <cmath>

#ifndef ARDUINO
#include <unistd.h>
static inline void delay_ms(unsigned long ms) { usleep(ms * 1000UL); }
#else
static inline void delay_ms(unsigned long ms) { delay(ms); }
#endif

BMP280Minimal::BMP280Minimal(Transport& transport, uint8_t addr)
    : _transport(transport), _addr(addr), _t_fine(0) {
    _load_calibration();
    _write_ctrl_meas(CTRL_MEAS_DEFAULT);
    _write_config(0x00);
}

void BMP280Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

void BMP280Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _transport.write_read(&reg, 1, buf, len);
}

void BMP280Minimal::_write_ctrl_meas(uint8_t value) {
    _write_reg(REG_CTRL_MEAS, value);
}

void BMP280Minimal::_write_config(uint8_t value) {
    _write_reg(REG_CONFIG, value);
}

void BMP280Minimal::_load_calibration() {
    uint8_t raw[24];
    _read_reg(REG_CAL_START, raw, 24);

    _dig_T1 = (uint16_t)((raw[0] << 8) | raw[1]);
    _dig_T2 = (int16_t)((raw[2] << 8) | raw[3]);
    _dig_T3 = (int16_t)((raw[4] << 8) | raw[5]);
    _dig_P1 = (uint16_t)((raw[6] << 8) | raw[7]);
    _dig_P2 = (int16_t)((raw[8] << 8) | raw[9]);
    _dig_P3 = (int16_t)((raw[10] << 8) | raw[11]);
    _dig_P4 = (int16_t)((raw[12] << 8) | raw[13]);
    _dig_P5 = (int16_t)((raw[14] << 8) | raw[15]);
    _dig_P6 = (int16_t)((raw[16] << 8) | raw[17]);
    _dig_P7 = (int16_t)((raw[18] << 8) | raw[19]);
    _dig_P8 = (int16_t)((raw[20] << 8) | raw[21]);
    _dig_P9 = (int16_t)((raw[22] << 8) | raw[23]);
}

void BMP280Minimal::_trigger_read_burst(int32_t& adc_T, int32_t& adc_P) {
    uint8_t raw[6];
    _read_reg(REG_DATA, raw, 6);

    adc_P = ((int32_t)raw[0] << 12) | ((int32_t)raw[1] << 4) | ((int32_t)(raw[2] >> 4));
    adc_T = ((int32_t)raw[3] << 12) | ((int32_t)raw[4] << 4) | ((int32_t)(raw[5] >> 4));
}

float BMP280Minimal::_compensate_temp(int32_t adc_T) {
    int64_t var1 = (((int64_t)adc_T >> 3) - ((int64_t)_dig_T1 << 1)) * (int64_t)_dig_T2;
    var1 >>= 11;

    int64_t var2 = (((((int64_t)adc_T >> 4) - (int64_t)_dig_T1) *
                    ((int64_t)adc_T >> 4) - (int64_t)_dig_T1) >> 12) * (int64_t)_dig_T3;
    var2 >>= 14;

    _t_fine = (int32_t)(var1 + var2);

    return ((int64_t)_t_fine * 5 + 128) >> 8) / 100.0f;
}

float BMP280Minimal::_compensate_pressure(int32_t adc_P) {
    int64_t var1 = ((int64_t)_t_fine - 128000);
    int64_t var2 = var1 * var1 * (int64_t)_dig_P6;
    var2 = var2 + ((var1 * (int64_t)_dig_P5) << 17);
    var2 = var2 + (((int64_t)_dig_P4) << 35);
    var1 = ((var1 * var1 * (int64_t)_dig_P3) >> 8) + ((var1 * (int64_t)_dig_P2) << 12);
    var1 = ((((int64_t)1 << 47) + var1) * (int64_t)_dig_P1) >> 33;

    if (var1 == 0) {
        return 0.0f;
    }

    int64_t p = 1048576 - adc_P;
    p = (((p << 31) - var2) * 3125) / var1;
    var1 = (((int64_t)_dig_P9 * (p >> 13) * (p >> 13)) >> 25);
    var2 = (((int64_t)_dig_P8 * p) >> 19);
    p = ((p + var1 + var2) >> 8) + (((int64_t)_dig_P7) << 4);

    return (float)((p / 256.0) / 100.0);
}

void BMP280Minimal::_trigger_measurement() {
    _write_ctrl_meas(0x25 | (1 << 5));
    delay_ms(7);
}

float BMP280Minimal::temperature() {
    _trigger_measurement();
    int32_t adc_T, adc_P;
    _trigger_read_burst(adc_T, adc_P);
    (void)adc_P;
    return _compensate_temp(adc_T);
}

float BMP280Minimal::pressure() {
    _trigger_measurement();
    int32_t adc_T, adc_P;
    _trigger_read_burst(adc_T, adc_P);
    _compensate_temp(adc_T);
    return _compensate_pressure(adc_P);
}

// BMP280Full

BMP280Full::BMP280Full(Transport& transport, uint8_t addr,
                       uint8_t osrs_t, uint8_t osrs_p,
                       uint8_t mode, uint8_t filter, uint8_t t_sb)
    : BMP280Minimal(transport, addr),
      _osrs_t(osrs_t), _osrs_p(osrs_p), _mode(mode),
      _filter(filter), _t_sb(t_sb) {
    _write_ctrl_meas(_ctrl_meas_value());
    _write_config(_config_value());
}

uint8_t BMP280Full::_ctrl_meas_value() {
    return (uint8_t)((_osrs_t << 5) | (_osrs_p << 2) | _mode);
}

uint8_t BMP280Full::_config_value() {
    return (uint8_t)((_t_sb << 5) | (_filter << 2));
}

void BMP280Full::configure(uint8_t osrs_t, uint8_t osrs_p, uint8_t mode, uint8_t filter, uint8_t t_sb) {
    if (osrs_t < 6) _osrs_t = osrs_t;
    if (osrs_p < 6) _osrs_p = osrs_p;
    if (mode == 0 || mode == 1 || mode == 3) _mode = mode;
    if (filter < 5) _filter = filter;
    if (t_sb < 8) _t_sb = t_sb;
    _write_ctrl_meas(_ctrl_meas_value());
    _write_config(_config_value());
}

void BMP280Full::set_oversampling(uint8_t osrs_t, uint8_t osrs_p) {
    if (osrs_t < 6) _osrs_t = osrs_t;
    if (osrs_p < 6) _osrs_p = osrs_p;
    _write_ctrl_meas(_ctrl_meas_value());
}

void BMP280Full::set_mode(uint8_t mode) {
    if (mode == 0 || mode == 1 || mode == 3) _mode = mode;
    _write_ctrl_meas(_ctrl_meas_value());
}

void BMP280Full::set_filter(uint8_t coeff) {
    if (coeff < 5) _filter = coeff;
    _write_config(_config_value());
}

void BMP280Full::set_standby(uint8_t t_sb) {
    if (t_sb < 8) _t_sb = t_sb;
    _write_config(_config_value());
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
    return p / powf(1.0f - ((float)altitude_m / 44330.0f), 5.255f);
}

uint8_t BMP280Full::chip_id() {
    uint8_t buf[1];
    _read_reg(REG_ID, buf, 1);
    return buf[0];
}

void BMP280Full::reset() {
    _write_reg(REG_RESET, RESET_CMD);
    delay_ms(2);
    _load_calibration();
    _write_ctrl_meas(_ctrl_meas_value());
    _write_config(_config_value());
}