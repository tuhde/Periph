#include "L3gd20h.h"
#include <math.h>
#include <string.h>

#ifdef ARDUINO
#include <Arduino.h>
static inline void delay_ms(unsigned long ms) { delay(ms); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay_ms(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay_ms(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay_ms(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay_ms(unsigned long ms) { usleep(ms * 1000UL); }
#endif

static constexpr float kPi = 3.141592653589793f;

L3gd20hMinimal::L3gd20hMinimal(Connection& connection, bool spi)
    : _connection(connection), _spi(spi), _full_scale(250) {
    uint8_t who = 0;
    _read_reg(REG_WHO_AM_I, &who, 1);
    if (who != WHO_AM_I_L3GD20 && who != WHO_AM_I_L3GD20H) {
        return;
    }
    _write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT);
    _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
    delay_ms(250);
}

void L3gd20hMinimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t addr = _spi ? (reg & 0x3F) : reg;
    uint8_t buf[2] = { addr, value };
    _connection.write(buf, 2);
}

void L3gd20hMinimal::_read_reg(uint8_t reg, uint8_t* buf, uint8_t len) {
    if (_spi) {
        uint8_t addr = reg | 0xC0;
        _connection.write_read(&addr, 1, buf, len);
    } else if (len > 1) {
        uint8_t addr = reg | 0x80;
        _connection.write_read(&addr, 1, buf, len);
    } else {
        _connection.write_read(&reg, 1, buf, len);
    }
}

float L3gd20hMinimal::_sensitivity() const {
    switch (_full_scale) {
        case 250:  return 8.75e-3f;
        case 500:  return 17.5e-3f;
        case 2000: return 70.0e-3f;
        default:   return 8.75e-3f;
    }
}

int16_t L3gd20hMinimal::_int16_le(const uint8_t* data) {
    int16_t v = (int16_t)((uint16_t)data[0] | ((uint16_t)data[1] << 8));
    return v;
}

void L3gd20hMinimal::gyro(float& x_rad_s, float& y_rad_s, float& z_rad_s) {
    uint8_t raw[6] = {0, 0, 0, 0, 0, 0};
    _read_reg(REG_OUT_X_L, raw, 6);
    float sens = _sensitivity();
    float x_dps = _int16_le(&raw[0]) * sens;
    float y_dps = _int16_le(&raw[2]) * sens;
    float z_dps = _int16_le(&raw[4]) * sens;
    x_rad_s = x_dps * (kPi / 180.0f);
    y_rad_s = y_dps * (kPi / 180.0f);
    z_rad_s = z_dps * (kPi / 180.0f);
}

// L3gd20hFull

L3gd20hFull::L3gd20hFull(Connection& connection, bool spi)
    : L3gd20hMinimal(connection, spi), _odr(0), _bw(0), _threshold_raw(0) {
}

void L3gd20hFull::configure(uint8_t odr, uint8_t bw, uint8_t full_scale) {
    if (odr > 3) odr = 3;
    if (bw > 3) bw = 3;
    if (full_scale > 2) full_scale = 2;
    _odr = odr;
    _bw = bw;
    static const uint16_t fs_map[3] = { 250, 500, 2000 };
    _full_scale = fs_map[full_scale];
    uint8_t ctrl1 = CTRL_REG1_DEFAULT | ((_odr & 0x3) << 6) | ((_bw & 0x3) << 4);
    _write_reg(REG_CTRL_REG1, ctrl1);
    uint8_t ctrl4 = CTRL_REG4_DEFAULT | ((full_scale & 0x3) << 4);
    _write_reg(REG_CTRL_REG4, ctrl4);
}

void L3gd20hFull::gyro_raw(int16_t& x_raw, int16_t& y_raw, int16_t& z_raw) {
    uint8_t raw[6] = {0, 0, 0, 0, 0, 0};
    _read_reg(REG_OUT_X_L, raw, 6);
    x_raw = _int16_le(&raw[0]);
    y_raw = _int16_le(&raw[2]);
    z_raw = _int16_le(&raw[4]);
}

int8_t L3gd20hFull::temperature() {
    uint8_t v = 0;
    _read_reg(REG_OUT_TEMP, &v, 1);
    return (int8_t)v;
}

bool L3gd20hFull::data_ready() {
    uint8_t v = 0;
    _read_reg(REG_STATUS, &v, 1);
    return (v & 0x08) != 0;
}

void L3gd20hFull::configure_hp_filter(uint8_t mode, uint8_t cutoff) {
    if (mode > 3) mode = 3;
    if (cutoff > 15) cutoff = 15;
    uint8_t ctrl2 = ((mode & 0x3) << 4) | (cutoff & 0x0F);
    _write_reg(REG_CTRL_REG2, ctrl2);
}

void L3gd20hFull::enable_hp_filter(bool enable) {
    uint8_t ctrl5 = 0;
    _read_reg(REG_CTRL_REG5, &ctrl5, 1);
    if (enable) {
        ctrl5 |= 0x10;
    } else {
        ctrl5 &= ~0x10;
    }
    _write_reg(REG_CTRL_REG5, ctrl5);
}

void L3gd20hFull::configure_fifo(uint8_t mode, uint8_t watermark) {
    static const uint8_t valid_modes[] = { 0, 1, 2, 3, 7 };
    bool valid = false;
    for (size_t i = 0; i < sizeof(valid_modes); i++) {
        if (mode == valid_modes[i]) { valid = true; break; }
    }
    if (!valid) mode = 0;
    if (watermark > 31) watermark = 31;
    uint8_t ctrl5 = 0;
    _read_reg(REG_CTRL_REG5, &ctrl5, 1);
    ctrl5 |= 0x40;
    _write_reg(REG_CTRL_REG5, ctrl5);
    uint8_t fifo_ctrl = ((mode & 0x7) << 5) | (watermark & 0x1F);
    _write_reg(REG_FIFO_CTRL, fifo_ctrl);
}

void L3gd20hFull::enable_fifo(bool enable) {
    uint8_t ctrl5 = 0;
    _read_reg(REG_CTRL_REG5, &ctrl5, 1);
    if (enable) {
        ctrl5 |= 0x40;
    } else {
        ctrl5 &= ~0x40;
        _write_reg(REG_FIFO_CTRL, 0x00);
    }
    _write_reg(REG_CTRL_REG5, ctrl5);
}

uint8_t L3gd20hFull::fifo_level() {
    uint8_t v = 0;
    _read_reg(REG_FIFO_SRC, &v, 1);
    return v & 0x1F;
}

uint8_t L3gd20hFull::read_fifo(float* out_x, float* out_y, float* out_z, uint8_t max_samples) {
    uint8_t n = fifo_level();
    if (n == 0 || max_samples == 0) return 0;
    if (n > max_samples) n = max_samples;
    float sens = _sensitivity();
    uint8_t* data = new uint8_t[n * 6];
    _read_reg(REG_OUT_X_L, data, n * 6);
    for (uint8_t i = 0; i < n; i++) {
        uint8_t offset = i * 6;
        float x_dps = _int16_le(&data[offset]) * sens;
        float y_dps = _int16_le(&data[offset + 2]) * sens;
        float z_dps = _int16_le(&data[offset + 4]) * sens;
        out_x[i] = x_dps * (kPi / 180.0f);
        out_y[i] = y_dps * (kPi / 180.0f);
        out_z[i] = z_dps * (kPi / 180.0f);
    }
    delete[] data;
    return n;
}

void L3gd20hFull::set_power_mode(const char* mode) {
    if (!mode) return;
    if (strcmp(mode, POWER_NORMAL) == 0) {
        uint8_t ctrl1 = 0;
        _read_reg(REG_CTRL_REG1, &ctrl1, 1);
        ctrl1 = (ctrl1 & 0xF0) | 0x0F;
        _write_reg(REG_CTRL_REG1, ctrl1);
    } else if (strcmp(mode, POWER_SLEEP) == 0) {
        uint8_t ctrl1 = 0;
        _read_reg(REG_CTRL_REG1, &ctrl1, 1);
        ctrl1 = (ctrl1 & 0xF8) | 0x08;
        _write_reg(REG_CTRL_REG1, ctrl1);
    } else if (strcmp(mode, POWER_POWERDOWN) == 0) {
        uint8_t ctrl1 = 0;
        _read_reg(REG_CTRL_REG1, &ctrl1, 1);
        ctrl1 &= 0xF7;
        _write_reg(REG_CTRL_REG1, ctrl1);
    }
}