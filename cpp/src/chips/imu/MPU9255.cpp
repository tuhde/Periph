#include "MPU9255.h"
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
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define DELAY_MS(ms) sleep_ms(ms)
#else
#include <Arduino.h>
#define DELAY_MS(ms) delay(ms)
#endif

MPU9255Minimal::MPU9255Minimal(Connection& connection)
    : _connection(connection) {
    _write_reg(REG_PWR_MGMT_1, 0x80);
    DELAY_MS(100);
    _write_reg(REG_PWR_MGMT_1, 0x01);
    uint8_t who = _read_reg(REG_WHO_AM_I);
    if (who != WHO_AM_I_VALUE) {
        abort();
    }
    _write_reg(REG_GYRO_CONFIG, 0x00);
    _write_reg(REG_ACCEL_CONFIG, 0x00);
    _write_reg(REG_ACCEL_CONFIG2, 0x03);
    _write_reg(REG_CONFIG, 0x03);
    _write_reg(REG_SMPLRT_DIV, 0x04);
    DELAY_MS(35);
}

void MPU9255Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

uint8_t MPU9255Minimal::_read_reg(uint8_t reg) {
    uint8_t val;
    _connection.write_read(&reg, 1, &val, 1);
    return val;
}

int16_t MPU9255Minimal::_read_reg16_signed(uint8_t reg) {
    uint8_t buf[2];
    _connection.write_read(&reg, 1, buf, 2);
    return static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
}

void MPU9255Minimal::_read_burst(uint8_t reg, uint8_t* buf, uint8_t len) {
    _connection.write_read(&reg, 1, buf, len);
}

void MPU9255Minimal::accel(float& x, float& y, float& z) {
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

void MPU9255Minimal::gyro(float& x, float& y, float& z) {
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

MPU9255Full::MPU9255Full(Connection& connection, Connection& magConnection)
    : MPU9255Minimal(connection), _mag_connection(magConnection) {}

void MPU9255Full::configure_gyro(uint8_t full_scale) {
    _gyro_fs = full_scale & 0x03;
    _write_reg(REG_GYRO_CONFIG, (full_scale & 0x03) << 3);
}

void MPU9255Full::configure_accel(uint8_t full_scale) {
    _accel_fs = full_scale & 0x03;
    _write_reg(REG_ACCEL_CONFIG, (full_scale & 0x03) << 3);
}

void MPU9255Full::configure_dlpf(uint8_t gyro_dlpf, uint8_t accel_dlpf) {
    _write_reg(REG_CONFIG, gyro_dlpf & 0x07);
    _write_reg(REG_ACCEL_CONFIG2, accel_dlpf & 0x07);
}

void MPU9255Full::configure_sample_rate(uint8_t divider) {
    _write_reg(REG_SMPLRT_DIV, divider);
}

float MPU9255Full::temperature() {
    int16_t raw = _read_reg16_signed(REG_TEMP_OUT_H);
    return raw / 333.87f + 21.0f;
}

void MPU9255Full::_ak8963_write(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _mag_connection.write(buf, 2);
}

uint8_t MPU9255Full::_ak8963_read(uint8_t reg) {
    uint8_t val;
    _mag_connection.write_read(&reg, 1, &val, 1);
    return val;
}

void MPU9255Full::_ak8963_read_burst(uint8_t reg, uint8_t* buf, uint8_t len) {
    _mag_connection.write_read(&reg, 1, buf, len);
}

void MPU9255Full::enable_mag(uint8_t bits, uint8_t mode) {
    _write_reg(REG_INT_PIN_CFG, 0x22);
    DELAY_MS(10);

    _ak8963_write(AK8963_REG_CNTL1, 0x00);
    DELAY_MS(10);

    _ak8963_write(AK8963_REG_CNTL1, 0x0F);
    DELAY_MS(10);

    uint8_t asax = _ak8963_read(AK8963_REG_ASAX);
    uint8_t asay = _ak8963_read(AK8963_REG_ASAY);
    uint8_t asaz = _ak8963_read(AK8963_REG_ASAZ);

    _mag_scale_x = (static_cast<float>(asax) - 128.0f) / 256.0f + 1.0f;
    _mag_scale_y = (static_cast<float>(asay) - 128.0f) / 256.0f + 1.0f;
    _mag_scale_z = (static_cast<float>(asaz) - 128.0f) / 256.0f + 1.0f;

    _ak8963_write(AK8963_REG_CNTL1, 0x00);
    DELAY_MS(10);

    uint8_t cntl1_val = 0;
    if (bits == 16) {
        cntl1_val |= 0x10;
    }
    cntl1_val |= (mode & 0x0F);
    _ak8963_write(AK8963_REG_CNTL1, cntl1_val);
    DELAY_MS(10);

    _mag_enabled = true;
    _mag_bits = bits;
}

void MPU9255Full::mag(float& x, float& y, float& z) {
    if (!_mag_enabled) {
        return;
    }
    uint8_t buf[7];
    _ak8963_read_burst(AK8963_REG_HXL, buf, 7);
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

void MPU9255Full::accel_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _read_burst(REG_ACCEL_XOUT_H, buf, 6);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
}

void MPU9255Full::gyro_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _read_burst(REG_GYRO_XOUT_H, buf, 6);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[0]) << 8) | buf[1]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[2]) << 8) | buf[3]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[4]) << 8) | buf[5]);
}

void MPU9255Full::mag_raw(int16_t& x, int16_t& y, int16_t& z) {
    if (!_mag_enabled) {
        x = y = z = 0;
        return;
    }
    // ST2 (buf[6]) is not used but must be read to unlock the next measurement.
    uint8_t buf[7];
    _ak8963_read_burst(AK8963_REG_HXL, buf, 7);
    x = static_cast<int16_t>((static_cast<uint16_t>(buf[1]) << 8) | buf[0]);
    y = static_cast<int16_t>((static_cast<uint16_t>(buf[3]) << 8) | buf[2]);
    z = static_cast<int16_t>((static_cast<uint16_t>(buf[5]) << 8) | buf[4]);
}

bool MPU9255Full::data_ready() {
    return (_read_reg(REG_INT_STATUS) & 0x01) != 0;
}

void MPU9255Full::set_sleep(bool sleep) {
    uint8_t val = _read_reg(REG_PWR_MGMT_1);
    if (sleep) {
        val |= 0x40;
    } else {
        val &= ~0x40;
    }
    _write_reg(REG_PWR_MGMT_1, val);
}

uint16_t MPU9255Full::fifo_count() {
    uint8_t buf[2];
    _read_burst(REG_FIFO_COUNTH, buf, 2);
    return ((static_cast<uint16_t>(buf[0]) & 0x1F) << 8) | buf[1];
}

uint16_t MPU9255Full::read_fifo(uint8_t* buf, uint16_t len) {
    uint16_t count = fifo_count();
    if (count == 0) return 0;
    uint16_t to_read = (count < len) ? count : len;
    _read_burst(REG_FIFO_R_W, buf, to_read);
    return to_read;
}

void MPU9255Full::enable_fifo(bool gyro, bool accel, bool temp) {
    uint8_t fifo_en = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4);
    _write_reg(REG_FIFO_EN, fifo_en);
    uint8_t user_ctrl = _read_reg(REG_USER_CTRL);
    _write_reg(REG_USER_CTRL, user_ctrl | 0x40);
}

void MPU9255Full::reset_fifo() {
    uint8_t user_ctrl = _read_reg(REG_USER_CTRL);
    _write_reg(REG_USER_CTRL, user_ctrl | 0x04);
}

void MPU9255Full::configure_wake_on_motion(uint16_t threshold_mg, float odr_hz) {
    int threshold_lsb = (static_cast<int>(threshold_mg) + 2) / 4;
    if (threshold_lsb < 1) threshold_lsb = 1;
    if (threshold_lsb > 255) threshold_lsb = 255;

    static const float lposc_table[16] = {
        0.24f, 0.49f, 0.98f, 1.95f, 3.91f, 7.81f, 15.63f, 31.25f,
        62.5f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f,
    };
    int best_sel = 0;
    float best_diff = (odr_hz < lposc_table[0]) ? (lposc_table[0] - odr_hz) : (odr_hz - lposc_table[0]);
    for (int sel = 1; sel < 16; ++sel) {
        float diff = (odr_hz < lposc_table[sel]) ? (lposc_table[sel] - odr_hz) : (odr_hz - lposc_table[sel]);
        if (diff < best_diff) {
            best_diff = diff;
            best_sel = sel;
        }
    }

    _write_reg(REG_PWR_MGMT_1, 0x01);
    _write_reg(REG_PWR_MGMT_2, 0x07);
    _write_reg(REG_ACCEL_CONFIG2, 0x01);
    _write_reg(REG_INT_ENABLE, 0x40);
    _write_reg(REG_MOT_DETECT_CTRL, 0xC0);
    _write_reg(REG_WOM_THR, static_cast<uint8_t>(threshold_lsb));
    _write_reg(REG_LP_ACCEL_ODR, static_cast<uint8_t>(best_sel & 0x0F));
    _write_reg(REG_PWR_MGMT_1, 0x21);
}

bool MPU9255Full::motion_detected() {
    return (_read_reg(REG_INT_STATUS) & 0x40) != 0;
}