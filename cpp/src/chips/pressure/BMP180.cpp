#include "BMP180.h"
#include <stdlib.h>

BMP180Minimal::BMP180Minimal(Transport& transport)
    : _transport(transport) {
    _read_calibration();
}

void BMP180Minimal::_read_calibration() {
    uint8_t buf[22];
    uint8_t reg = REG_CAL_START;
    _transport.write_read(&reg, 1, buf, 22);

    _ac1 = (int16_t)((buf[0] << 8) | buf[1]);
    _ac2 = (int16_t)((buf[2] << 8) | buf[3]);
    _ac3 = (int16_t)((buf[4] << 8) | buf[5]);
    _ac4 = (uint16_t)((buf[6] << 8) | buf[7]);
    _ac5 = (uint16_t)((buf[8] << 8) | buf[9]);
    _ac6 = (uint16_t)((buf[10] << 8) | buf[11]);
    _b1  = (int16_t)((buf[12] << 8) | buf[13]);
    _b2  = (int16_t)((buf[14] << 8) | buf[15]);
    _mb  = (int16_t)((buf[16] << 8) | buf[17]);
    _mc  = (int16_t)((buf[18] << 8) | buf[19]);
    _md  = (int16_t)((buf[20] << 8) | buf[21]);

    if (_ac1 == 0 || _ac1 == 0xFFFF ||
        _ac2 == 0 || _ac2 == 0xFFFF ||
        _ac3 == 0 || _ac3 == 0xFFFF ||
        _ac4 == 0 || _ac4 == 0xFFFF ||
        _ac5 == 0 || _ac5 == 0xFFFF ||
        _ac6 == 0 || _ac6 == 0xFFFF ||
        _b1  == 0 || _b1  == 0xFFFF ||
        _b2  == 0 || _b2  == 0xFFFF ||
        _mb  == 0 || _mb  == 0xFFFF ||
        _mc  == 0 || _mc  == 0xFFFF ||
        _md  == 0 || _md  == 0xFFFF) {
        abort();
    }
}

void BMP180Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

uint16_t BMP180Minimal::_read_raw_temp() {
    _write_reg(REG_CTRL_MEAS, CMD_TEMP);
    delay(CONV_TIME_TEMP * 1000);
    uint8_t buf[2];
    uint8_t reg = REG_OUT_MSB;
    _transport.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0] << 8) | buf[1];
}

uint32_t BMP180Minimal::_read_raw_pressure() {
    uint8_t cmd = (_oss == 0) ? CMD_PRESSURE_OSS0 :
                  (_oss == 1) ? CMD_PRESSURE_OSS1 :
                  (_oss == 2) ? CMD_PRESSURE_OSS2 : CMD_PRESSURE_OSS3;
    float conv_time = (_oss == 0) ? CONV_TIME_OSS0 :
                      (_oss == 1) ? CONV_TIME_OSS1 :
                      (_oss == 2) ? CONV_TIME_OSS2 : CONV_TIME_OSS3;
    _write_reg(REG_CTRL_MEAS, cmd);
    delay(conv_time * 1000);
    uint8_t buf[3];
    uint8_t reg = REG_OUT_MSB;
    _transport.write_read(&reg, 1, buf, 3);
    uint32_t up = (((uint32_t)buf[0] << 16) | ((uint32_t)buf[1] << 8) | buf[2]) >> (8 - _oss);
    return up;
}

int32_t BMP180Minimal::_compensate_temp(int32_t ut) {
    int32_t x1 = ((ut - (int32_t)_ac6) * (int32_t)_ac5) >> 15;
    int32_t x2 = ((int32_t)_mc << 11) / (x1 + (int32_t)_md);
    int32_t b5 = x1 + x2;
    _b5 = b5;
    return b5;
}

int32_t BMP180Minimal::_compensate_pressure(int32_t up) {
    int32_t oss = (int32_t)_oss;
    int32_t b6 = _b5 - 4000;
    int32_t x1 = ((int32_t)_b2 * ((b6 * b6) >> 12)) >> 11;
    int32_t x2 = ((int32_t)_ac2 * b6) >> 11;
    int32_t x3 = x1 + x2;
    int32_t b3 = (((((int32_t)_ac1 * 4) + x3) << oss) + 2) >> 2;
    x1 = ((int32_t)_ac3 * b6) >> 13;
    x2 = ((int32_t)_b1 * ((b6 * b6) >> 12)) >> 16;
    x3 = ((x1 + x2) + 2) >> 2;
    uint32_t b4 = ((uint32_t)_ac4 * ((uint32_t)(x3 + 32768))) >> 15;
    uint32_t b7 = ((uint32_t)(up - b3)) * ((uint32_t)(50000 >> oss));

    int32_t p;
    if (b7 < 0x80000000) {
        p = (int32_t)(((uint32_t)(b7 * 2)) / b4);
    } else {
        p = (int32_t)(((b7 / (int32_t)b4) * 2));
    }

    x1 = (p >> 8) * (p >> 8);
    x1 = (x1 * 3038) >> 16;
    x2 = (-7357 * p) >> 16;
    p = p + ((x1 + x2 + 3791) >> 4);

    return p;
}

float BMP180Minimal::temperature() {
    uint16_t ut = _read_raw_temp();
    int32_t b5 = _compensate_temp(ut);
    return ((float)(b5 + 8)) / 160.0f;
}

float BMP180Minimal::pressure() {
    uint16_t ut = _read_raw_temp();
    int32_t b5 = _compensate_temp(ut);
    (void)b5;
    uint32_t up = _read_raw_pressure();
    int32_t p_pa = _compensate_pressure((int32_t)up);
    return ((float)p_pa) / 100.0f;
}

// BMP180Full

BMP180Full::BMP180Full(Transport& transport, uint8_t oss)
    : BMP180Minimal(transport) {
    _oss = oss & 0x03;
}

uint8_t BMP180Full::oversampling() {
    return _oss;
}

void BMP180Full::set_oversampling(uint8_t oss) {
    _oss = oss & 0x03;
}

float BMP180Full::altitude(float sea_level_hpa) {
    float p = pressure();
    return 44330.0f * (1.0f - powf(p / sea_level_hpa, 0.1903f));
}

float BMP180Full::sea_level_pressure(float altitude_m) {
    float p = pressure();
    return p / powf(1.0f - ((float)altitude_m / 44330.0f), 5.255f);
}

uint8_t BMP180Full::chip_id() {
    uint8_t reg = REG_ID;
    uint8_t buf[1];
    _transport.write_read(&reg, 1, buf, 1);
    return buf[0];
}

void BMP180Full::reset() {
    _write_reg(REG_SOFT_RESET, SOFT_RESET_CMD);
    delay(10);
    _read_calibration();
}
