#include "MPU9250.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr float MPU9250Minimal::ACCEL_SENSITIVITY[4];
constexpr float MPU9250Minimal::GYRO_SENSITIVITY[4];

#include <stdlib.h>

#if defined(__linux__)
#include <unistd.h>
#define DELAY_MS(ms) usleep((ms) * 1000)
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define DELAY_MS(ms) k_sleep(K_MSEC(ms))
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
#define DELAY_MS(ms) HAL_Delay(ms)
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

MPU9250Minimal::MPU9250Minimal(RegisterConnection& connection)
    : _connection(connection) {
    { uint8_t v = 0x80; _connection.write(REG_PWR_MGMT_1, &v, 1); }
    DELAY_MS(100);
    { uint8_t v = 0x01; _connection.write(REG_PWR_MGMT_1, &v, 1); }
    uint8_t who = _read_reg(REG_WHO_AM_I);
    if (who != WHO_AM_I_VALUE) {
        abort();
    }
    { uint8_t v = 0x00; _connection.write(REG_GYRO_CONFIG, &v, 1); }
    { uint8_t v = 0x00; _connection.write(REG_ACCEL_CONFIG, &v, 1); }
    { uint8_t v = 0x03; _connection.write(REG_ACCEL_CONFIG2, &v, 1); }
    { uint8_t v = 0x03; _connection.write(REG_CONFIG, &v, 1); }
    { uint8_t v = 0x04; _connection.write(REG_SMPLRT_DIV, &v, 1); }
    DELAY_MS(35);
}

uint8_t MPU9250Minimal::_read_reg(uint8_t reg) {
    uint8_t val;
    _connection.read(reg, &val, 1);
    return val;
}

int16_t MPU9250Minimal::_read_reg16_signed(uint8_t reg) {
    uint8_t buf[2];
    _connection.read(reg, buf, 2);
    return static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
}

void MPU9250Minimal::accel(float& x, float& y, float& z) {
    uint8_t buf[6];
    _connection.read(REG_ACCEL_XOUT_H, buf, 6);
    int16_t ax = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    int16_t ay = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    int16_t az = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
    float sens = ACCEL_SENSITIVITY[_accel_fs];
    x = ax / sens * 9.80665f;
    y = ay / sens * 9.80665f;
    z = az / sens * 9.80665f;
}

void MPU9250Minimal::gyro(float& x, float& y, float& z) {
    uint8_t buf[6];
    _connection.read(REG_GYRO_XOUT_H, buf, 6);
    int16_t gx = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    int16_t gy = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    int16_t gz = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
    float sens = GYRO_SENSITIVITY[_gyro_fs];
    x = gx / sens * 3.141592653589793f / 180.0f;
    y = gy / sens * 3.141592653589793f / 180.0f;
    z = gz / sens * 3.141592653589793f / 180.0f;
}

MPU9250Full::MPU9250Full(RegisterConnection& connection, RegisterConnection& magConnection)
    : MPU9250Minimal(connection), _mag_connection(magConnection) {}

void MPU9250Full::configure_gyro(uint8_t full_scale) {
    _gyro_fs = full_scale & 0x03;
    { uint8_t v = (full_scale & 0x03) << 3; _connection.write(REG_GYRO_CONFIG, &v, 1); }
}

void MPU9250Full::configure_accel(uint8_t full_scale) {
    _accel_fs = full_scale & 0x03;
    { uint8_t v = (full_scale & 0x03) << 3; _connection.write(REG_ACCEL_CONFIG, &v, 1); }
}

void MPU9250Full::configure_dlpf(uint8_t gyro_dlpf, uint8_t accel_dlpf) {
    { uint8_t v = gyro_dlpf & 0x07; _connection.write(REG_CONFIG, &v, 1); }
    { uint8_t v = accel_dlpf & 0x07; _connection.write(REG_ACCEL_CONFIG2, &v, 1); }
}

void MPU9250Full::configure_sample_rate(uint8_t divider) {
    { uint8_t v = divider; _connection.write(REG_SMPLRT_DIV, &v, 1); }
}

float MPU9250Full::temperature() {
    int16_t raw = _read_reg16_signed(REG_TEMP_OUT_H);
    return raw / 333.87f + 21.0f;
}

uint8_t MPU9250Full::_ak8963_read(uint8_t reg) {
    uint8_t val;
    _mag_connection.read(reg, &val, 1);
    return val;
}

void MPU9250Full::enable_mag(uint8_t bits, uint8_t mode) {
    { uint8_t v = 0x22; _connection.write(REG_INT_PIN_CFG, &v, 1); }
    DELAY_MS(10);

    { uint8_t v = 0x00; _mag_connection.write(AK8963_REG_CNTL1, &v, 1); }
    DELAY_MS(10);

    { uint8_t v = 0x0F; _mag_connection.write(AK8963_REG_CNTL1, &v, 1); }
    DELAY_MS(10);

    uint8_t asax = _ak8963_read(AK8963_REG_ASAX);
    uint8_t asay = _ak8963_read(AK8963_REG_ASAY);
    uint8_t asaz = _ak8963_read(AK8963_REG_ASAZ);

    _mag_scale_x = (static_cast<float>(asax) - 128.0f) / 256.0f + 1.0f;
    _mag_scale_y = (static_cast<float>(asay) - 128.0f) / 256.0f + 1.0f;
    _mag_scale_z = (static_cast<float>(asaz) - 128.0f) / 256.0f + 1.0f;

    { uint8_t v = 0x00; _mag_connection.write(AK8963_REG_CNTL1, &v, 1); }
    DELAY_MS(10);

    uint8_t cntl1_val = 0;
    if (bits == 16) {
        cntl1_val |= 0x10;
    }
    cntl1_val |= (mode & 0x0F);
    { uint8_t v = cntl1_val; _mag_connection.write(AK8963_REG_CNTL1, &v, 1); }
    DELAY_MS(10);

    _mag_enabled = true;
    _mag_bits = bits;
}

void MPU9250Full::mag(float& x, float& y, float& z) {
    if (!_mag_enabled) {
        return;
    }
    uint8_t buf[7];
    _mag_connection.read(AK8963_REG_HXL, buf, 7);
    int16_t mx = static_cast<int16_t>((static_cast<uint16_t>(buf[1]) << 8) | buf[0]);
    int16_t my = static_cast<int16_t>((static_cast<uint16_t>(buf[3]) << 8) | buf[2]);
    int16_t mz = static_cast<int16_t>((static_cast<uint16_t>(buf[5]) << 8) | buf[4]);
    uint8_t st2 = buf[6];
    (void)st2;

    float sens = (_mag_bits == 16) ? MAG_SENSITIVITY_16BIT : MAG_SENSITIVITY_14BIT;
    x = static_cast<float>(mx) * sens * _mag_scale_x;
    y = static_cast<float>(my) * sens * _mag_scale_y;
    z = static_cast<float>(mz) * sens * _mag_scale_z;
}

void MPU9250Full::accel_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _connection.read(REG_ACCEL_XOUT_H, buf, 6);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
}

void MPU9250Full::gyro_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _connection.read(REG_GYRO_XOUT_H, buf, 6);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
}

void MPU9250Full::mag_raw(int16_t& x, int16_t& y, int16_t& z) {
    if (!_mag_enabled) {
        x = y = z = 0;
        return;
    }
    // ST2 (buf[6]) is not used but must be read to unlock the next measurement.
    uint8_t buf[7];
    _mag_connection.read(AK8963_REG_HXL, buf, 7);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[1]) << 8) | buf[0]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[3]) << 8) | buf[2]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[5]) << 8) | buf[4]);
}

bool MPU9250Full::data_ready() {
    return (_read_reg(REG_INT_STATUS) & 0x01) != 0;
}

void MPU9250Full::set_sleep(bool sleep) {
    uint8_t val = _read_reg(REG_PWR_MGMT_1);
    if (sleep) {
        val |= 0x40;
    } else {
        val &= ~0x40;
    }
    { uint8_t v = val; _connection.write(REG_PWR_MGMT_1, &v, 1); }
}

uint16_t MPU9250Full::fifo_count() {
    uint8_t buf[2];
    _connection.read(REG_FIFO_COUNTH, buf, 2);
    return ((static_cast<uint16_t>(buf[0]) & 0x1F) << 8) | buf[1];
}

uint16_t MPU9250Full::read_fifo(uint8_t* buf, uint16_t len) {
    uint16_t count = fifo_count();
    if (count == 0) return 0;
    uint16_t to_read = (count < len) ? count : len;
    _connection.read(REG_FIFO_R_W, buf, to_read);
    return to_read;
}

void MPU9250Full::enable_fifo(bool gyro, bool accel, bool temp) {
    uint8_t fifo_en = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4);
    { uint8_t v = fifo_en; _connection.write(REG_FIFO_EN, &v, 1); }
    uint8_t user_ctrl = _read_reg(REG_USER_CTRL);
    { uint8_t v = user_ctrl | 0x40; _connection.write(REG_USER_CTRL, &v, 1); }
}

void MPU9250Full::reset_fifo() {
    uint8_t user_ctrl = _read_reg(REG_USER_CTRL);
    { uint8_t v = user_ctrl | 0x04; _connection.write(REG_USER_CTRL, &v, 1); }
}