#include "Lps33hw.h"
#include <stdlib.h>
#include <cmath>

#ifdef ARDUINO
#include <Arduino.h>
#define LPS33HW_DELAY_MS(ms) delay(ms)
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
#define LPS33HW_DELAY_MS(ms) k_msleep(ms)
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define LPS33HW_DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
#define LPS33HW_DELAY_MS(ms) sleep_ms(ms)
#else
#include <unistd.h>
static inline void lps33hw_delay_ms(unsigned long ms) { usleep(ms * 1000UL); }
#define LPS33HW_DELAY_MS(ms) lps33hw_delay_ms(ms)
#endif

static inline int32_t sign_extend_24(uint32_t raw) {
    if (raw & 0x800000) return (int32_t)(raw | 0xFF000000);
    return (int32_t)raw;
}

static inline int16_t sign_extend_16(uint16_t raw) {
    if (raw & 0x8000) return (int16_t)(raw | 0xFF00);
    return (int16_t)raw;
}

LPS33HWMinimal::LPS33HWMinimal(Connection& connection)
    : _connection(connection) {
    uint8_t buf[1];
    _read_reg(REG_WHO_AM_I, buf, 1);
    if (buf[0] != CHIP_ID) {
        // No exceptions in no-STL driver; fall through, calls will fail.
    }
    _write_reg(REG_CTRL_REG2, CTRL_REG2_RESET);
    LPS33HW_DELAY_MS(1);
    _write_reg(REG_CTRL_REG2, CTRL_REG2_DEFAULT);
    _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
}

void LPS33HWMinimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

void LPS33HWMinimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.write_read(&reg, 1, buf, len);
}

void LPS33HWMinimal::_wait_status(uint8_t mask) {
    uint8_t buf[1];
    for (int i = 0; i < 50; i++) {
        _read_reg(REG_STATUS, buf, 1);
        if ((buf[0] & mask) == mask) return;
        LPS33HW_DELAY_MS(5);
    }
}

int32_t LPS33HWMinimal::_read_pressure_raw() {
    uint8_t raw[5];
    _read_reg(REG_PRESS_XL, raw, 5);
    uint32_t v = ((uint32_t)raw[2] << 16) | ((uint32_t)raw[1] << 8) | raw[0];
    return sign_extend_24(v);
}

int16_t LPS33HWMinimal::_read_temperature_raw() {
    uint8_t raw[5];
    _read_reg(REG_PRESS_XL, raw, 5);
    uint16_t v = ((uint16_t)raw[4] << 8) | raw[3];
    return sign_extend_16(v);
}

float LPS33HWMinimal::pressure() {
    _wait_status(STATUS_P_DA);
    int32_t raw = _read_pressure_raw();
    return raw * 100.0f / 4096.0f;
}

float LPS33HWMinimal::temperature() {
    _wait_status(STATUS_T_DA);
    int16_t raw = _read_temperature_raw();
    return raw / 100.0f;
}

LPS33HWFull::LPS33HWFull(Connection& connection)
    : LPS33HWMinimal(connection) {
}

void LPS33HWFull::configure(uint8_t odr, bool bdu, bool en_lpfp,
                            uint8_t lpfp_cfg, bool lc_en, bool sim) {
    uint8_t ctrl1 = ((odr & 7) << 4)
                  | (en_lpfp ? (1 << 3) : 0)
                  | ((lpfp_cfg & 1) << 2)
                  | (bdu ? (1 << 1) : 0)
                  | (sim ? 1 : 0);
    _write_reg(REG_CTRL_REG1, ctrl1);

    uint8_t cur[1];
    _read_reg(REG_RES_CONF, cur, 1);
    _write_reg(REG_RES_CONF, (cur[0] & 0xFE) | (lc_en ? 1 : 0));
}

bool LPS33HWFull::one_shot(float& pressure_Pa, float& temperature_C) {
    uint8_t cur[1];
    _read_reg(REG_CTRL_REG2, cur, 1);
    _write_reg(REG_CTRL_REG2, cur[0] | 0x01);
    for (int i = 0; i < 50; i++) {
        uint8_t status_buf[1];
        _read_reg(REG_STATUS, status_buf, 1);
        if ((status_buf[0] & 0x03) == 0x03) {
            pressure_Pa = pressure();
            temperature_C = temperature();
            return true;
        }
        LPS33HW_DELAY_MS(5);
    }
    return false;
}

uint8_t LPS33HWFull::status() {
    uint8_t buf[1];
    _read_reg(REG_STATUS, buf, 1);
    return buf[0];
}

void LPS33HWFull::reset() {
    _write_reg(REG_CTRL_REG2, CTRL_REG2_RESET);
    for (int i = 0; i < 50; i++) {
        uint8_t buf[1];
        _read_reg(REG_CTRL_REG2, buf, 1);
        if (!(buf[0] & 0x04)) break;
        LPS33HW_DELAY_MS(1);
    }
    _write_reg(REG_CTRL_REG2, CTRL_REG2_DEFAULT);
    _write_reg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
}

void LPS33HWFull::reboot() {
    _write_reg(REG_CTRL_REG2, 0x80);
    for (int i = 0; i < 100; i++) {
        uint8_t buf[1];
        _read_reg(REG_INT_SOURCE, buf, 1);
        if (!(buf[0] & 0x80)) break;
        LPS33HW_DELAY_MS(5);
    }
}

void LPS33HWFull::set_pressure_offset(float offset_hPa) {
    int32_t raw = (int32_t)(offset_hPa * 16.0f);
    uint16_t u = (raw < 0) ? (uint16_t)(raw + 0x10000) : (uint16_t)raw;
    _write_reg(REG_RPDS_L, u & 0xFF);
    _write_reg(REG_RPDS_H, (u >> 8) & 0xFF);
}

void LPS33HWFull::set_autozero() {
    uint8_t cur[1];
    _read_reg(REG_INTERRUPT_CFG, cur, 1);
    _write_reg(REG_INTERRUPT_CFG, cur[0] | 0x20);
}

void LPS33HWFull::clear_autozero() {
    uint8_t cur[1];
    _read_reg(REG_INTERRUPT_CFG, cur, 1);
    _write_reg(REG_INTERRUPT_CFG, cur[0] | 0x10);
}

void LPS33HWFull::set_autorifp() {
    uint8_t cur[1];
    _read_reg(REG_INTERRUPT_CFG, cur, 1);
    _write_reg(REG_INTERRUPT_CFG, cur[0] | 0x80);
}

void LPS33HWFull::clear_autorifp() {
    uint8_t cur[1];
    _read_reg(REG_INTERRUPT_CFG, cur, 1);
    _write_reg(REG_INTERRUPT_CFG, cur[0] | 0x40);
}

void LPS33HWFull::configure_interrupt(bool drdy, bool f_fth, bool f_ovr,
                                      bool f_fss5, uint8_t int_s,
                                      bool active_low, bool open_drain) {
    uint8_t ctrl3 = (active_low ? 0x80 : 0)
                  | (open_drain ? 0x40 : 0)
                  | (f_fss5 ? 0x20 : 0)
                  | (f_fth ? 0x10 : 0)
                  | (f_ovr ? 0x08 : 0)
                  | (drdy ? 0x04 : 0)
                  | (int_s & 0x03);
    _write_reg(REG_CTRL_REG3, ctrl3);
}

void LPS33HWFull::configure_pressure_interrupt(bool high_en, bool low_en,
                                               float threshold_hPa, bool latch) {
    uint16_t raw_ths = (uint16_t)(threshold_hPa * 16.0f) & 0xFFFF;
    _write_reg(REG_THS_P_L, raw_ths & 0xFF);
    _write_reg(REG_THS_P_H, (raw_ths >> 8) & 0xFF);

    uint8_t cur[1];
    _read_reg(REG_INTERRUPT_CFG, cur, 1);
    uint8_t new_cfg = (cur[0] & 0xF0)
                    | (latch ? 0x04 : 0)
                    | (high_en ? 0x02 : 0)
                    | (low_en ? 0x01 : 0);
    _write_reg(REG_INTERRUPT_CFG, new_cfg);
}

uint8_t LPS33HWFull::interrupt_status() {
    uint8_t buf[1];
    _read_reg(REG_INT_SOURCE, buf, 1);
    return buf[0];
}

void LPS33HWFull::enable_fifo(uint8_t mode, uint8_t watermark) {
    uint8_t ctrl = ((mode & 7) << 5) | (watermark & 0x1F);
    _write_reg(REG_FIFO_CTRL, ctrl);
    uint8_t cur[1];
    _read_reg(REG_CTRL_REG2, cur, 1);
    _write_reg(REG_CTRL_REG2, cur[0] | 0x40);
}

void LPS33HWFull::disable_fifo() {
    uint8_t cur[1];
    _read_reg(REG_CTRL_REG2, cur, 1);
    _write_reg(REG_CTRL_REG2, cur[0] & ~0x40);
    _write_reg(REG_FIFO_CTRL, 0);
}

uint8_t LPS33HWFull::fifo_status() {
    uint8_t buf[1];
    _read_reg(REG_FIFO_STATUS, buf, 1);
    return buf[0];
}

void LPS33HWFull::reset_lpf() {
    uint8_t buf[1];
    _read_reg(REG_LPFP_RES, buf, 1);
}