#include "LPS28DFW.h"

#ifdef ARDUINO
#include <Arduino.h>
#define LPS28DFW_DELAY_MS(ms) delay(ms)
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void LPS28DFW_DELAY_MS(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void LPS28DFW_DELAY_MS(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void LPS28DFW_DELAY_MS(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void LPS28DFW_DELAY_MS(unsigned long ms) { usleep(ms * 1000UL); }
#endif

LPS28DFWMinimal::LPS28DFWMinimal(Connection& connection)
    : _connection(connection) {
    _init();
}

void LPS28DFWMinimal::_init() {
    uint8_t buf[1];
    _read_reg(REG_WHO_AM_I, buf, 1);
    if (buf[0] != CHIP_ID) {
        return;
    }
    LPS28DFW_DELAY_MS(BOOT_WAIT_MS);
    uint8_t ctrl2 = (_fs_mode << 6) | (_lpf_cfg << 5) | (_lpf_en << 4) | (_bdu << 3);
    _write_reg(REG_CTRL_REG2, ctrl2);
    uint8_t ctrl1 = (_odr << 3) | (_avg & 0x07);
    _write_reg(REG_CTRL_REG1, ctrl1);
}

void LPS28DFWMinimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

void LPS28DFWMinimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.write_read(&reg, 1, buf, len);
}

int32_t LPS28DFWMinimal::_read_pressure_raw() {
    uint8_t buf[3];
    _read_reg(REG_PRESS_OUT_XL, buf, 3);
    int32_t v = (int32_t)((uint32_t)buf[2] << 16 | (uint32_t)buf[1] << 8 | buf[0]);
    if (v & 0x800000) v |= (int32_t)0xFF000000;
    return v;
}

int16_t LPS28DFWMinimal::_read_temperature_raw() {
    uint8_t buf[2];
    _read_reg(REG_TEMP_OUT_L, buf, 2);
    int16_t v = (int16_t)((uint16_t)buf[1] << 8 | buf[0]);
    return v;
}

float LPS28DFWMinimal::read_pressure() {
    int32_t raw = _read_pressure_raw();
    float sens = (_fs_mode == 0) ? SENSITIVITY_LSB_PER_HPA_MODE1 : SENSITIVITY_LSB_PER_HPA_MODE2;
    return (float)raw / sens;
}

float LPS28DFWMinimal::read_temperature() {
    int16_t raw = _read_temperature_raw();
    return (float)raw / 100.0f;
}

// LPS28DFWFull

LPS28DFWFull::LPS28DFWFull(Connection& connection)
    : LPS28DFWMinimal(connection) {
}

void LPS28DFWFull::configure(uint8_t odr, uint8_t avg, uint8_t fs_mode, uint8_t lpf_en, uint8_t lpf_cfg) {
    _odr     = odr;
    _avg     = avg;
    _fs_mode = fs_mode;
    _lpf_en  = lpf_en ? 1 : 0;
    _lpf_cfg = lpf_cfg;
    uint8_t ctrl2 = (_fs_mode << 6) | (_lpf_cfg << 5) | (_lpf_en << 4) | (_bdu << 3);
    _write_reg(REG_CTRL_REG2, ctrl2);
    uint8_t ctrl1 = (_odr << 3) | (_avg & 0x07);
    _write_reg(REG_CTRL_REG1, ctrl1);
}

void LPS28DFWFull::read(float& pressure, float& temperature) {
    uint8_t buf[5];
    _read_reg(REG_PRESS_OUT_XL, buf, 5);
    int32_t p = (int32_t)((uint32_t)buf[2] << 16 | (uint32_t)buf[1] << 8 | buf[0]);
    if (p & 0x800000) p |= (int32_t)0xFF000000;
    int16_t t = (int16_t)((uint16_t)buf[4] << 8 | buf[3]);
    float sens = (_fs_mode == 0) ? SENSITIVITY_LSB_PER_HPA_MODE1 : SENSITIVITY_LSB_PER_HPA_MODE2;
    pressure    = (float)p / sens;
    temperature = (float)t / 100.0f;
}

void LPS28DFWFull::read_oneshot(float& pressure, float& temperature) {
    uint8_t saved_ctrl1 = 0;
    _read_reg(REG_CTRL_REG1, &saved_ctrl1, 1);
    uint8_t saved_odr = saved_ctrl1 >> 3;
    _write_reg(REG_CTRL_REG1, (0u << 3) | (_avg & 0x07));
    uint8_t ctrl2 = 0;
    _read_reg(REG_CTRL_REG2, &ctrl2, 1);
    _write_reg(REG_CTRL_REG2, ctrl2 | 0x01);
    for (int i = 0; i < 200; ++i) {
        uint8_t status = 0;
        _read_reg(REG_STATUS, &status, 1);
        if (status & STATUS_P_DA) break;
        LPS28DFW_DELAY_MS(5);
    }
    read(pressure, temperature);
    _write_reg(REG_CTRL_REG1, (saved_odr << 3) | (_avg & 0x07));
}

uint8_t LPS28DFWFull::is_data_ready() {
    uint8_t status = 0;
    _read_reg(REG_STATUS, &status, 1);
    return (status & STATUS_P_DA) ? 1 : 0;
}

void LPS28DFWFull::set_offset(float offset_hpa) {
    float sens = (_fs_mode == 0) ? SENSITIVITY_LSB_PER_HPA_MODE1 : SENSITIVITY_LSB_PER_HPA_MODE2;
    int32_t raw = (int32_t)(offset_hpa * sens);
    if (raw < 0) raw += 0x10000;
    _write_reg(0x1A, (uint8_t)(raw & 0xFF));
    _write_reg(0x1B, (uint8_t)((raw >> 8) & 0xFF));
}

void LPS28DFWFull::softreset() {
    uint8_t ctrl2 = 0;
    _read_reg(REG_CTRL_REG2, &ctrl2, 1);
    _write_reg(REG_CTRL_REG2, ctrl2 | 0x02);
    LPS28DFW_DELAY_MS(BOOT_WAIT_MS);
}

void LPS28DFWFull::fifo_configure(uint8_t mode, uint8_t wtm, uint8_t stop_on_wtm) {
    if (mode == FIFO_BYPASS) {
        _write_reg(0x14, 0x00);
    }
    uint8_t trig = (mode >= 4) ? 1 : 0;
    uint8_t f_mode = mode & 0x03;
    uint8_t ctrl = (trig << 2) | ((stop_on_wtm ? 1 : 0) << 3) | f_mode;
    _write_reg(0x14, ctrl);
    _write_reg(0x15, wtm & 0x7F);
}

void LPS28DFWFull::fifo_read(uint8_t count, float* buf) {
    if (count == 0) return;
    if (count > 128) count = 128;
    uint8_t raw[384];
    _read_reg(REG_FIFO_DATA_PRESS_XL, raw, count * 3);
    float sens = (_fs_mode == 0) ? SENSITIVITY_LSB_PER_HPA_MODE1 : SENSITIVITY_LSB_PER_HPA_MODE2;
    for (uint8_t i = 0; i < count; ++i) {
        uint8_t b0 = raw[i * 3];
        uint8_t b1 = raw[i * 3 + 1];
        uint8_t b2 = raw[i * 3 + 2];
        int32_t v = (int32_t)((uint32_t)b2 << 16 | (uint32_t)b1 << 8 | b0);
        if (v & 0x800000) v |= (int32_t)0xFF000000;
        buf[i] = (float)v / sens;
    }
}

uint8_t LPS28DFWFull::fifo_level() {
    uint8_t buf[1];
    _read_reg(0x25, buf, 1);
    return buf[0];
}

void LPS28DFWFull::set_threshold(float threshold_hpa, uint8_t high, uint8_t low) {
    float sens = (_fs_mode == 0) ? 16.0f : 8.0f;
    int32_t raw = (int32_t)(threshold_hpa * sens);
    if (raw < 0) raw = 0;
    if (raw > 0x7FFF) raw = 0x7FFF;
    _write_reg(0x0C, (uint8_t)(raw & 0xFF));
    _write_reg(0x0D, (uint8_t)((raw >> 8) & 0x7F));
    uint8_t cfg = 0;
    _read_reg(REG_INTERRUPT_CFG, &cfg, 1);
    cfg &= ~0x03;
    if (high) cfg |= 0x01;
    if (low)  cfg |= 0x02;
    _write_reg(REG_INTERRUPT_CFG, cfg);
}

uint8_t LPS28DFWFull::chip_id() {
    uint8_t buf[1];
    _read_reg(REG_WHO_AM_I, buf, 1);
    return buf[0];
}

float LPS28DFWFull::altitude(float sea_level_hpa) {
    float p = read_pressure();
    return 44330.0f * (1.0f - powf(p / sea_level_hpa, 1.0f / 5.255f));
}