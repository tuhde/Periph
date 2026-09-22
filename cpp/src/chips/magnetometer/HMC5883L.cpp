#include "HMC5883L.h"
#include <cmath>

HMC5883LMinimal::HMC5883LMinimal(Connection& connection)
    : _connection(connection), _gain(1), _gain_lsb_per_gauss(GAIN_LSB_PER_GAUSS[1]) {
    _init_minimal();
}

void HMC5883LMinimal::_init_minimal() {
    _write_reg8(REG_CONFIG_A, 0x70);
    _write_reg8(REG_CONFIG_B, 0x20);
    _write_reg8(REG_MODE, 0x00);
    // 6 ms delay for first measurement - platform-specific delay needed
    // For now, we assume the platform handles timing or user calls after delay
}

uint8_t HMC5883LMinimal::_read_reg8(uint8_t reg) {
    uint8_t buf[1];
    _connection.write_read(&reg, 1, buf, 1);
    return buf[0];
}

int16_t HMC5883LMinimal::_read_reg16(uint8_t reg) {
    uint8_t buf[2];
    _connection.write_read(&reg, 1, buf, 2);
    return (int16_t)(((uint16_t)buf[0] << 8) | buf[1]);
}

void HMC5883LMinimal::_write_reg8(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

void HMC5883LMinimal::_read_data_burst(int16_t& raw_x, int16_t& raw_y, int16_t& raw_z) {
    uint8_t buf[6];
    _connection.write_read(&REG_DATA_X_MSB, 1, buf, 6);
    raw_x = (int16_t)(((uint16_t)buf[0] << 8) | buf[1]);
    raw_z = (int16_t)(((uint16_t)buf[2] << 8) | buf[3]);
    raw_y = (int16_t)(((uint16_t)buf[4] << 8) | buf[5]);
}

float HMC5883LMinimal::_raw_to_tesla(int16_t raw) {
    if (raw == -4096) {
        return NAN;
    }
    return (raw / _gain_lsb_per_gauss) * 1e-4f;
}

bool HMC5883LMinimal::magnetic_field(float& x, float& y, float& z) {
    int16_t raw_x, raw_y, raw_z;
    _read_data_burst(raw_x, raw_y, raw_z);
    x = _raw_to_tesla(raw_x);
    y = _raw_to_tesla(raw_y);
    z = _raw_to_tesla(raw_z);
    return !std::isnan(x) && !std::isnan(y) && !std::isnan(z);
}

// HMC5883LFull

HMC5883LFull::HMC5883LFull(Connection& connection)
    : HMC5883LMinimal(connection) {}

bool HMC5883LFull::configure(float odr, uint8_t averaging, uint8_t gain) {
    uint8_t ma, do_bits;

    // Map averaging
    switch (averaging) {
        case 1:  ma = 0b00; break;
        case 2:  ma = 0b01; break;
        case 4:  ma = 0b10; break;
        case 8:  ma = 0b11; break;
        default: return false;
    }

    // Map ODR
    if (fabsf(odr - 0.75f) < 0.01f)        do_bits = 0b000;
    else if (fabsf(odr - 1.5f) < 0.01f)    do_bits = 0b001;
    else if (fabsf(odr - 3.0f) < 0.01f)    do_bits = 0b010;
    else if (fabsf(odr - 7.5f) < 0.01f)    do_bits = 0b011;
    else if (fabsf(odr - 15.0f) < 0.01f)   do_bits = 0b100;
    else if (fabsf(odr - 30.0f) < 0.01f)   do_bits = 0b101;
    else if (fabsf(odr - 75.0f) < 0.01f)   do_bits = 0b110;
    else                                   return false;

    if (gain > 7) return false;

    uint8_t config_a = (ma << 5) | (do_bits << 2);
    _write_reg8(REG_CONFIG_A, config_a);

    uint8_t config_b = (gain << 5);
    _write_reg8(REG_CONFIG_B, config_b);

    _gain = gain;
    _gain_lsb_per_gauss = GAIN_LSB_PER_GAUSS[gain];
    return true;
}

bool HMC5883LFull::set_gain(uint8_t gain) {
    if (gain > 7) return false;
    _write_reg8(REG_CONFIG_B, gain << 5);
    _gain = gain;
    _gain_lsb_per_gauss = GAIN_LSB_PER_GAUSS[gain];
    return true;
}

bool HMC5883LFull::set_mode(const char* mode) {
    uint8_t md;
    if (strcmp(mode, "continuous") == 0)      md = 0b00;
    else if (strcmp(mode, "single") == 0)     md = 0b01;
    else if (strcmp(mode, "idle") == 0)       md = 0b10;
    else                                      return false;
    _write_reg8(REG_MODE, md);
    return true;
}

bool HMC5883LFull::data_ready() {
    uint8_t status = _read_reg8(REG_STATUS);
    return (status & 0x01) != 0;
}

uint8_t HMC5883LFull::status() {
    return _read_reg8(REG_STATUS);
}

bool HMC5883LFull::single_measurement(float& x, float& y, float& z) {
    _write_reg8(REG_MODE, 0x01);
    // Platform-specific 6 ms delay needed here
    return magnetic_field(x, y, z);
}

void HMC5883LFull::identify(uint8_t& id_a, uint8_t& id_b, uint8_t& id_c) {
    id_a = _read_reg8(REG_ID_A);
    id_b = _read_reg8(REG_ID_B);
    id_c = _read_reg8(REG_ID_C);
}

bool HMC5883LFull::self_test(bool positive, float& x, float& y, float& z) {
    uint8_t config_a = _read_reg8(REG_CONFIG_A);
    config_a = (config_a & 0xFC) | (positive ? 0b01 : 0b10);
    _write_reg8(REG_CONFIG_A, config_a);

    _write_reg8(REG_MODE, 0x01);
    // Platform-specific 6 ms delay needed here
    bool result = magnetic_field(x, y, z);

    config_a = (config_a & 0xFC) | 0b00;
    _write_reg8(REG_CONFIG_A, config_a);
    return result;
}