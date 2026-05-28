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
