#include "LPS22DF.h"
#include <stdlib.h>
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

LPS22DFMinimal::LPS22DFMinimal(Connection& connection, bool spi)
    : _connection(connection), _spi(spi) {
    uint8_t who = 0;
    _read_reg(REG_WHO_AM_I, &who, 1);
    if (who != CHIP_ID) {
        // No exception support: driver is silently no-op for absent chip.
        // Callers should verify via who_am_i() on the Full class.
        return;
    }
    _write_reg(REG_CTRL_REG2, 0x04);  // SWRESET=1
    delay_ms(1);
    // CTRL_REG1: ODR[3:0]=0011 (10 Hz), AVG[2:0]=000 (4 samples)
    _write_reg(REG_CTRL_REG1, (3 << 3) | 0);
    // CTRL_REG2: BDU=1
    _write_reg(REG_CTRL_REG2, 0x08);
}

void LPS22DFMinimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t addr = _spi ? (reg & 0x7F) : reg;
    uint8_t buf[2] = { addr, value };
    _connection.write(buf, 2);
}

void LPS22DFMinimal::_read_reg(uint8_t reg, uint8_t* buf, uint8_t len) {
    uint8_t addr = _spi ? (reg & 0x7F) : reg;
    _connection.write_read(&addr, 1, buf, len);
}

void LPS22DFMinimal::_wait_p_da() {
    while (true) {
        uint8_t status = 0;
        _read_reg(REG_STATUS, &status, 1);
        if (status & 0x01) return;  // P_DA
        delay_ms(1);
    }
}

float LPS22DFMinimal::pressure() {
    _wait_p_da();
    uint8_t raw[3] = {0, 0, 0};
    _read_reg(REG_PRESS_OUT_XL, raw, 3);
    int32_t value = (int32_t)raw[0] | ((int32_t)raw[1] << 8) | ((int32_t)raw[2] << 16);
    if (value & 0x800000) value -= 0x1000000;
    return (value / 4096.0f) * 100.0f;
}

float LPS22DFMinimal::temperature() {
    uint8_t raw[2] = {0, 0};
    _read_reg(REG_TEMP_OUT_L, raw, 2);
    int16_t value = (int16_t)((uint16_t)raw[0] | ((uint16_t)raw[1] << 8));
    return value / 100.0f;
}

// LPS22DFFull

LPS22DFFull::LPS22DFFull(Connection& connection, bool spi)
    : LPS22DFMinimal(connection, spi) {
}

void LPS22DFFull::configure(uint8_t odr, uint8_t avg, bool en_lpfp, uint8_t lfpf_cfg, bool bdu) {
    uint8_t ctrl1 = ((odr & 0x0F) << 3) | (avg & 0x07);
    uint8_t ctrl2 = 0;
    if (en_lpfp)  ctrl2 |= 0x10;
    if (lfpf_cfg) ctrl2 |= 0x20;
    if (bdu)      ctrl2 |= 0x08;
    _write_reg(REG_CTRL_REG1, ctrl1);
    _write_reg(REG_CTRL_REG2, ctrl2);
}

void LPS22DFFull::oneshot() {
    _write_reg(REG_CTRL_REG1, 0x00);
    _write_reg(REG_CTRL_REG2, 0x08 | 0x01);
    _wait_p_da();
}

float LPS22DFFull::altitude(float sea_level_pa) {
    float p = pressure();
    return 44330.0f * (1.0f - powf(p / sea_level_pa, 1.0f / 5.255f));
}

void LPS22DFFull::software_reset() {
    _write_reg(REG_CTRL_REG2, 0x04);
    delay_ms(1);
}

void LPS22DFFull::set_pressure_offset(float offset_pa) {
    float offset_hpa = offset_pa / 100.0f;
    int32_t raw = (int32_t)lroundf(offset_hpa * 4096.0f);
    if (raw < 0) raw += 0x10000;
    _write_reg(0x1A, (uint8_t)(raw & 0xFF));
    _write_reg(0x1B, (uint8_t)((raw >> 8) & 0xFF));
}

void LPS22DFFull::set_pressure_threshold(float threshold_pa) {
    float threshold_hpa = threshold_pa / 100.0f;
    uint16_t raw = ((uint16_t)lroundf(threshold_hpa * 16.0f)) & 0x7FFF;
    _write_reg(0x0C, (uint8_t)(raw & 0xFF));
    _write_reg(0x0D, (uint8_t)((raw >> 8) & 0xFF));
}

void LPS22DFFull::configure_interrupt(bool int_h_l, bool pp_od, bool drdy, bool drdy_pls,
                                       bool int_en, bool int_f_wtm, bool int_f_full, bool int_f_ovr) {
    uint8_t ctrl3 = 0x01;  // IF_ADD_INC=1
    if (int_h_l) ctrl3 |= 0x08;
    if (pp_od)   ctrl3 |= 0x02;
    uint8_t ctrl4 = 0;
    if (drdy_pls)  ctrl4 |= 0x40;
    if (drdy)      ctrl4 |= 0x20;
    if (int_en)    ctrl4 |= 0x10;
    if (int_f_full) ctrl4 |= 0x04;
    if (int_f_wtm)  ctrl4 |= 0x02;
    if (int_f_ovr)  ctrl4 |= 0x01;
    _write_reg(REG_CTRL_REG3, ctrl3);
    _write_reg(REG_CTRL_REG4, ctrl4);
}

void LPS22DFFull::configure_pressure_event(bool phe, bool ple, bool lir) {
    uint8_t cfg = 0;
    if (phe) cfg |= 0x01;
    if (ple) cfg |= 0x02;
    if (lir) cfg |= 0x04;
    _write_reg(REG_INTERRUPT_CFG, cfg);
}

void LPS22DFFull::autozero() {
    _write_reg(REG_INTERRUPT_CFG, 0x20);
}

void LPS22DFFull::autorefp() {
    _write_reg(REG_INTERRUPT_CFG, 0x80);
}

void LPS22DFFull::reset_reference() {
    _write_reg(REG_INTERRUPT_CFG, 0x50);
}

float LPS22DFFull::reference_pressure() {
    uint8_t raw[2] = {0, 0};
    _read_reg(REG_REF_P_L, raw, 2);
    int16_t value = (int16_t)((uint16_t)raw[0] | ((uint16_t)raw[1] << 8));
    return (value / 4096.0f) * 100.0f;
}

void LPS22DFFull::set_fifo_mode(uint8_t mode) {
    uint8_t trig = 0, fm = 0;
    if (mode == 0)      { trig = 0; fm = 0; }
    else if (mode == 1) { trig = 0; fm = 1; }
    else if (mode == 2) { trig = 0; fm = 2; }
    else if (mode == 3) { trig = 1; fm = 1; }
    else if (mode == 4) { trig = 1; fm = 2; }
    else                { trig = 1; fm = 3; }
    _write_reg(REG_FIFO_CTRL, (uint8_t)((trig << 2) | (fm & 0x03)));
}

void LPS22DFFull::set_fifo_watermark(uint8_t level) {
    _write_reg(REG_FIFO_WTM, level & 0x7F);
}

uint8_t LPS22DFFull::fifo_sample_count() {
    uint8_t v = 0;
    _read_reg(REG_FIFO_STATUS1, &v, 1);
    return v;
}

uint8_t LPS22DFFull::read_fifo(float* out_buf, uint8_t max_samples) {
    uint8_t count = fifo_sample_count();
    if (count > max_samples) count = max_samples;
    if (count == 0) return 0;
    uint8_t raw[3 * 128];
    _read_reg(REG_FIFO_PRESS_XL, raw, (uint8_t)(count * 3));
    for (uint8_t i = 0; i < count; i++) {
        uint8_t base = (uint8_t)(i * 3);
        int32_t value = (int32_t)raw[base] | ((int32_t)raw[base + 1] << 8) | ((int32_t)raw[base + 2] << 16);
        if (value & 0x800000) value -= 0x1000000;
        out_buf[i] = (value / 4096.0f) * 100.0f;
    }
    return count;
}

uint8_t LPS22DFFull::interrupt_source() {
    uint8_t v = 0;
    _read_reg(REG_INT_SOURCE, &v, 1);
    return v;
}

uint8_t LPS22DFFull::who_am_i() {
    uint8_t v = 0;
    _read_reg(REG_WHO_AM_I, &v, 1);
    return v;
}