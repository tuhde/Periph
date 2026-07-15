#include "Mpu6050.h"

MPU6050Minimal::MPU6050Minimal(Transport& transport)
    : _transport(transport) {
    _write_reg(REG_PWR_MGMT_1, 0x80);
    delay(100);
    _write_reg(REG_PWR_MGMT_1, 0x01);
    uint8_t who = _read_reg(REG_WHO_AM_I);
    if (who != WHO_AM_I_VALUE) {
        return;
    }
    _write_reg(REG_GYRO_CONFIG, 0x00);
    _write_reg(REG_ACCEL_CONFIG, 0x00);
    _write_reg(REG_CONFIG, 0x03);
    _write_reg(REG_SMPLRT_DIV, 0x04);
    delay(35);
}

void MPU6050Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _transport.write(buf, 2);
}

uint8_t MPU6050Minimal::_read_reg(uint8_t reg) {
    uint8_t val;
    _transport.write_read(&reg, 1, &val, 1);
    return val;
}

int16_t MPU6050Minimal::_read_reg16_signed(uint8_t reg) {
    uint8_t buf[2];
    _transport.write_read(&reg, 1, buf, 2);
    return static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
}

void MPU6050Minimal::_read_burst(uint8_t reg, uint8_t* buf, uint8_t len) {
    _transport.write_read(&reg, 1, buf, len);
}

void MPU6050Minimal::accel(float& x, float& y, float& z) {
    uint8_t buf[6];
    _read_burst(REG_ACCEL_XOUT_H, buf, 6);
    int16_t ax = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    int16_t ay = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    int16_t az = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
    float sens = ACCEL_SENSITIVITY[_accel_fs];
    x = ax / sens * 9.80665f;
    y = ay / sens * 9.80665f;
    z = az / sens * 9.80665f;
}

void MPU6050Minimal::gyro(float& x, float& y, float& z) {
    uint8_t buf[6];
    _read_burst(REG_GYRO_XOUT_H, buf, 6);
    int16_t gx = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    int16_t gy = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    int16_t gz = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
    float sens = GYRO_SENSITIVITY[_gyro_fs];
    x = gx / sens * 3.141592653589793f / 180.0f;
    y = gy / sens * 3.141592653589793f / 180.0f;
    z = gz / sens * 3.141592653589793f / 180.0f;
}

MPU6050Full::MPU6050Full(Transport& transport)
    : MPU6050Minimal(transport) {}

void MPU6050Full::configure_gyro(uint8_t full_scale) {
    _gyro_fs = full_scale & 0x03;
    _write_reg(REG_GYRO_CONFIG, (full_scale & 0x03) << 3);
}

void MPU6050Full::configure_accel(uint8_t full_scale) {
    _accel_fs = full_scale & 0x03;
    _write_reg(REG_ACCEL_CONFIG, (full_scale & 0x03) << 3);
}

void MPU6050Full::configure_dlpf(uint8_t dlpf) {
    _write_reg(REG_CONFIG, dlpf & 0x07);
}

void MPU6050Full::configure_sample_rate(uint8_t divider) {
    _write_reg(REG_SMPLRT_DIV, divider);
}

float MPU6050Full::temperature() {
    int16_t raw = _read_reg16_signed(REG_TEMP_OUT_H);
    return raw / 340.0f + 36.53f;
}

void MPU6050Full::accel_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _read_burst(REG_ACCEL_XOUT_H, buf, 6);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
}

void MPU6050Full::gyro_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _read_burst(REG_GYRO_XOUT_H, buf, 6);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
}

bool MPU6050Full::data_ready() {
    return (_read_reg(REG_INT_STATUS) & 0x01) != 0;
}

void MPU6050Full::set_sleep(bool sleep) {
    uint8_t val = _read_reg(REG_PWR_MGMT_1);
    if (sleep) {
        val |= 0x40;
    } else {
        val &= ~0x40;
    }
    _write_reg(REG_PWR_MGMT_1, val);
}

void MPU6050Full::set_standby(bool xa, bool ya, bool za, bool xg, bool yg, bool zg) {
    uint8_t val = ((xa ? 1 : 0) << 5) | ((ya ? 1 : 0) << 4) | ((za ? 1 : 0) << 3) |
                  ((xg ? 1 : 0) << 2) | ((yg ? 1 : 0) << 1) | (zg ? 1 : 0);
    _write_reg(REG_PWR_MGMT_2, val);
}

uint16_t MPU6050Full::fifo_count() {
    uint8_t buf[2];
    _read_burst(REG_FIFO_COUNTH, buf, 2);
    return ((static_cast<uint16_t>(buf[0]) & 0x1F) << 8) | buf[1];
}

uint16_t MPU6050Full::read_fifo(uint8_t* buf, uint16_t len) {
    uint16_t count = fifo_count();
    if (count == 0) return 0;
    uint16_t to_read = (count < len) ? count : len;
    _read_burst(REG_FIFO_R_W, buf, to_read);
    return to_read;
}

void MPU6050Full::enable_fifo(bool gyro, bool accel, bool temp) {
    uint8_t fifo_en = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4);
    _write_reg(REG_FIFO_EN, fifo_en);
    uint8_t user_ctrl = _read_reg(REG_USER_CTRL);
    _write_reg(REG_USER_CTRL, user_ctrl | 0x40);
}

void MPU6050Full::reset_fifo() {
    uint8_t user_ctrl = _read_reg(REG_USER_CTRL);
    _write_reg(REG_USER_CTRL, user_ctrl | 0x04);
}

uint8_t MPU6050Full::who_am_i() {
    return _read_reg(REG_WHO_AM_I);
}
