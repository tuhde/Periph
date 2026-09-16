#include "L3G4200D.h"
#include <cmath>

#ifdef ARDUINO
#include <Arduino.h>
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

L3G4200DMinimal::L3G4200DMinimal(Connection& connection, bool spi)
    : _connection(connection), _spi(spi), _full_scale(250) {
    uint8_t who = 0;
    _read_reg(REG_WHO_AM_I, &who, 1);
    if (who != WHO_AM_I_EXPECTED) {
        return;  // silent no-op; callers verify via who_am_i() on Full
    }
    _write_reg(REG_CTRL_REG4, CTRL_REG4_DEFAULT);
    _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
}

void L3G4200DMinimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t addr = _spi ? (reg & 0x3F) : reg;
    uint8_t buf[2] = { addr, value };
    _connection.write(buf, 2);
}

void L3G4200DMinimal::_read_reg(uint8_t reg, uint8_t* buf, uint8_t len) {
    if (_spi) {
        uint8_t addr = reg | 0xC0;  // READ=1, MS=1 (auto-increment)
        _connection.write_read(&addr, 1, buf, len);
    } else if (len > 1) {
        uint8_t addr = reg | 0x80;  // MSB set = I²C multi-byte auto-increment
        _connection.write_read(&addr, 1, buf, len);
    } else {
        _connection.write_read(&reg, 1, buf, len);
    }
}

float L3G4200DMinimal::_sensitivity() const {
    switch (_full_scale) {
        case 250:  return 8.75e-3f;
        case 500:  return 17.5e-3f;
        case 2000: return 70.0e-3f;
        default:   return 8.75e-3f;
    }
}

int16_t L3G4200DMinimal::_int16_le(const uint8_t* data) {
    int16_t v = (int16_t)((uint16_t)data[0] | ((uint16_t)data[1] << 8));
    return v;
}

void L3G4200DMinimal::angular_rate(float& x_rad_s, float& y_rad_s, float& z_rad_s) {
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

// L3G4200DFull

L3G4200DFull::L3G4200DFull(Connection& connection, bool spi)
    : L3G4200DMinimal(connection, spi), _odr(0), _bw(0) {
}

void L3G4200DFull::configure(uint8_t odr, uint8_t bandwidth, uint16_t full_scale) {
    if (full_scale != 250 && full_scale != 500 && full_scale != 2000) return;
    _odr = odr & 0x3;
    _bw = bandwidth & 0x3;
    _full_scale = full_scale;
    uint8_t ctrl1 = CTRL_REG1_DEFAULT | ((_odr & 0x3) << 6) | ((_bw & 0x3) << 4);
    _write_reg(REG_CTRL_REG1, ctrl1);
    uint8_t fs_bits = (full_scale == 250) ? 0 : (full_scale == 500 ? 1 : 2);
    uint8_t ctrl4 = CTRL_REG4_DEFAULT | ((fs_bits & 0x3) << 4);
    _write_reg(REG_CTRL_REG4, ctrl4);
}

void L3G4200DFull::set_full_scale(uint16_t full_scale) {
    if (full_scale != 250 && full_scale != 500 && full_scale != 2000) return;
    _full_scale = full_scale;
    uint8_t fs_bits = (full_scale == 250) ? 0 : (full_scale == 500 ? 1 : 2);
    uint8_t ctrl4 = 0;
    _read_reg(REG_CTRL_REG4, &ctrl4, 1);
    ctrl4 = (ctrl4 & 0xCF) | ((fs_bits & 0x3) << 4);
    _write_reg(REG_CTRL_REG4, ctrl4);
}

uint8_t L3G4200DFull::who_am_i() {
    uint8_t v = 0;
    _read_reg(REG_WHO_AM_I, &v, 1);
    return v;
}

uint8_t L3G4200DFull::status() {
    uint8_t v = 0;
    _read_reg(REG_STATUS, &v, 1);
    return v;
}

bool L3G4200DFull::data_ready() {
    return (status() & 0x08) != 0;
}

int8_t L3G4200DFull::temperature() {
    uint8_t v = 0;
    _read_reg(REG_OUT_TEMP, &v, 1);
    return (int8_t)v;
}

void L3G4200DFull::power_down() {
    uint8_t ctrl1 = 0;
    _read_reg(REG_CTRL_REG1, &ctrl1, 1);
    ctrl1 &= 0xF7;
    _write_reg(REG_CTRL_REG1, ctrl1);
}

void L3G4200DFull::wake_up() {
    uint8_t ctrl1 = 0;
    _read_reg(REG_CTRL_REG1, &ctrl1, 1);
    ctrl1 |= 0x08;
    _write_reg(REG_CTRL_REG1, ctrl1);
}

void L3G4200DFull::sleep() {
    _write_reg(REG_CTRL_REG1, 0x08);
}

void L3G4200DFull::enable_axes(bool x, bool y, bool z) {
    uint8_t ctrl1 = 0;
    _read_reg(REG_CTRL_REG1, &ctrl1, 1);
    ctrl1 &= 0xF8;
    if (z) ctrl1 |= 0x04;
    if (y) ctrl1 |= 0x02;
    if (x) ctrl1 |= 0x01;
    _write_reg(REG_CTRL_REG1, ctrl1);
}

void L3G4200DFull::enable_fifo(uint8_t mode, uint8_t watermark) {
    uint8_t ctrl5 = 0;
    _read_reg(REG_CTRL_REG5, &ctrl5, 1);
    ctrl5 |= 0x40;  // FIFO_EN
    _write_reg(REG_CTRL_REG5, ctrl5);
    uint8_t fifo_ctrl = ((mode & 0x7) << 5) | (watermark & 0x1F);
    _write_reg(REG_FIFO_CTRL, fifo_ctrl);
}

void L3G4200DFull::disable_fifo() {
    uint8_t ctrl5 = 0;
    _read_reg(REG_CTRL_REG5, &ctrl5, 1);
    ctrl5 &= ~0x40;
    _write_reg(REG_CTRL_REG5, ctrl5);
    _write_reg(REG_FIFO_CTRL, 0x00);
}

uint8_t L3G4200DFull::fifo_samples() {
    uint8_t v = 0;
    _read_reg(REG_FIFO_SRC, &v, 1);
    return v & 0x1F;
}

void L3G4200DFull::enable_highpass(uint8_t mode, uint8_t cutoff) {
    uint8_t ctrl2 = ((mode & 0x3) << 4) | (cutoff & 0x0F);
    _write_reg(REG_CTRL_REG2, ctrl2);
    uint8_t ctrl5 = 0;
    _read_reg(REG_CTRL_REG5, &ctrl5, 1);
    ctrl5 |= 0x10;  // HPen
    _write_reg(REG_CTRL_REG5, ctrl5);
}

void L3G4200DFull::disable_highpass() {
    uint8_t ctrl5 = 0;
    _read_reg(REG_CTRL_REG5, &ctrl5, 1);
    ctrl5 &= ~0x10;
    _write_reg(REG_CTRL_REG5, ctrl5);
}

void L3G4200DFull::set_interrupt(bool x_high, bool x_low,
                                  bool y_high, bool y_low,
                                  bool z_high, bool z_low,
                                  bool and_mode, bool latch) {
    uint8_t cfg = 0;
    if (and_mode) cfg |= 0x80;
    if (latch)    cfg |= 0x40;
    if (z_high)   cfg |= 0x20;
    if (z_low)    cfg |= 0x10;
    if (y_high)   cfg |= 0x08;
    if (y_low)    cfg |= 0x04;
    if (x_high)   cfg |= 0x02;
    if (x_low)    cfg |= 0x01;
    _write_reg(REG_INT1_CFG, cfg);
    if (cfg & 0x3F) {
        uint8_t ctrl3 = 0;
        _read_reg(REG_CTRL_REG3, &ctrl3, 1);
        ctrl3 |= 0x80;  // I1_Int1
        _write_reg(REG_CTRL_REG3, ctrl3);
    }
}

void L3G4200DFull::set_threshold(char axis, float threshold_dps) {
    uint16_t raw = (uint16_t)floorf(threshold_dps / _sensitivity()) & 0x7FFF;
    uint8_t hi_reg, lo_reg;
    switch (axis) {
        case 'x': hi_reg = REG_INT1_THS_XH; lo_reg = REG_INT1_THS_XL; break;
        case 'y': hi_reg = REG_INT1_THS_YH; lo_reg = REG_INT1_THS_YL; break;
        case 'z': hi_reg = REG_INT1_THS_ZH; lo_reg = REG_INT1_THS_ZL; break;
        default:  return;
    }
    _write_reg(hi_reg, (raw >> 8) & 0x7F);
    _write_reg(lo_reg, raw & 0xFF);
}

void L3G4200DFull::set_duration(uint8_t samples, bool wait) {
    uint8_t val = ((wait ? 1 : 0) << 7) | (samples & 0x7F);
    _write_reg(REG_INT1_DURATION, val);
}

uint8_t L3G4200DFull::read_int_source() {
    uint8_t v = 0;
    _read_reg(REG_INT1_SRC, &v, 1);
    return v;
}

void L3G4200DFull::set_data_ready_pin(bool enable) {
    uint8_t ctrl3 = 0;
    _read_reg(REG_CTRL_REG3, &ctrl3, 1);
    if (enable) {
        ctrl3 |= 0x08;
    } else {
        ctrl3 &= ~0x08;
    }
    _write_reg(REG_CTRL_REG3, ctrl3);
}
