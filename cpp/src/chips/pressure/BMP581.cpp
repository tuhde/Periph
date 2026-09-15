#include "BMP581.h"
#include <stdlib.h>
#include <cmath>

#ifdef ARDUINO
#include <Arduino.h>
#define delay_ms(ms) delay(ms)
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

static int32_t _u24(const uint8_t* data) {
    int32_t raw = ((int32_t)data[2] << 16) | ((int32_t)data[1] << 8) | data[0];
    if (raw & 0x800000) raw -= 0x1000000;
    return raw;
}

BMP581Minimal::BMP581Minimal(Connection& connection, bool spi)
    : _connection(connection), _spi(spi),
      _odr(0x1C), _pwr_mode(0x01), _osr_p(0), _osr_t(0), _press_en(true) {
    _init();
}

void BMP581Minimal::_spi_dummy_read() {
    if (!_spi) return;
    uint8_t addr = REG_CHIP_ID | 0x80;
    uint8_t buf[1] = {0};
    _connection.write_read(&addr, 1, buf, 1);
}

void BMP581Minimal::_init() {
    _spi_dummy_read();
    uint8_t buf[1];
    _read_reg(REG_CHIP_ID, buf, 1);
    if (buf[0] != CHIP_ID_EXPECTED) {
        // Soft reset path is best-effort; if the bus truly is dead we
        // can do nothing more, so silently continue.
        (void)buf;
    }
    for (int i = 0; i < 50; i++) {
        _read_reg(REG_STATUS, buf, 1);
        if ((buf[0] & STATUS_NVM_RDY) && !(buf[0] & STATUS_NVM_ERR)) break;
        delay_ms(2);
    }
    _read_reg(REG_INT_STATUS, buf, 1);
    {
        uint8_t cmd[2] = { REG_CMD, SOFT_RESET_CMD };
        _connection.write(cmd, 2);
    }
    delay_ms(2);
    for (int i = 0; i < 50; i++) {
        _read_reg(REG_STATUS, buf, 1);
        if ((buf[0] & STATUS_NVM_RDY) && !(buf[0] & STATUS_NVM_ERR)) break;
        delay_ms(2);
    }
    _read_reg(REG_INT_STATUS, buf, 1);
    uint8_t osr[2] = { REG_OSR_CONFIG, 0x40 };
    _connection.write(osr, 2);
    uint8_t odr[2] = { REG_ODR_CONFIG, 0x71 };
    _connection.write(odr, 2);
}

void BMP581Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t addr = _spi ? (reg & 0x7F) : reg;
    uint8_t buf[2] = { addr, value };
    _connection.write(buf, 2);
}

void BMP581Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    uint8_t addr = reg;
    _connection.write_read(&addr, 1, buf, len);
}

float BMP581Minimal::pressure() {
    if (_pwr_mode == MODE_FORCED) {
        for (int i = 0; i < 200; i++) {
            uint8_t buf[1];
            _read_reg(REG_INT_STATUS, buf, 1);
            if (buf[0] & INT_STATUS_DRDY) break;
            delay_ms(5);
        }
    }
    uint8_t buf[3];
    _read_reg(REG_PRESS_XLSB, buf, 3);
    return _u24(buf) / 64.0f;
}

float BMP581Minimal::temperature() {
    if (_pwr_mode == MODE_FORCED) {
        for (int i = 0; i < 200; i++) {
            uint8_t buf[1];
            _read_reg(REG_INT_STATUS, buf, 1);
            if (buf[0] & INT_STATUS_DRDY) break;
            delay_ms(5);
        }
    }
    uint8_t buf[3];
    _read_reg(REG_TEMP_XLSB, buf, 3);
    return _u24(buf) / 65536.0f;
}

void BMP581Minimal::both(float& pressure_pa, float& temperature_c) {
    if (_pwr_mode == MODE_FORCED) {
        for (int i = 0; i < 200; i++) {
            uint8_t buf[1];
            _read_reg(REG_INT_STATUS, buf, 1);
            if (buf[0] & INT_STATUS_DRDY) break;
            delay_ms(5);
        }
    }
    uint8_t buf[6];
    _read_reg(REG_TEMP_XLSB, buf, 6);
    pressure_pa = _u24(buf + 3) / 64.0f;
    temperature_c = _u24(buf) / 65536.0f;
}

// BMP581Full

BMP581Full::BMP581Full(Connection& connection, bool spi)
    : BMP581Minimal(connection, spi) {
}

void BMP581Full::configure(uint8_t odr, uint8_t osr_p, uint8_t osr_t, bool press_en) {
    _odr = odr;
    _osr_p = osr_p;
    _osr_t = osr_t;
    _press_en = press_en;
    uint8_t osr_val = (press_en ? 0x40 : 0) | ((osr_p & 0x7) << 3) | (osr_t & 0x7);
    _write_reg(REG_OSR_CONFIG, osr_val);
    uint8_t odr_val = ((odr & 0x1F) << 2) | (_pwr_mode & 0x3);
    _write_reg(REG_ODR_CONFIG, odr_val);
}

void BMP581Full::set_mode(uint8_t mode) {
    _pwr_mode = mode;
    uint8_t odr_val = ((_odr & 0x1F) << 2) | (mode & 0x3);
    _write_reg(REG_ODR_CONFIG, odr_val);
}

void BMP581Full::forced(float& pressure_pa, float& temperature_c) {
    uint8_t prev_mode = _pwr_mode;
    if (prev_mode != MODE_FORCED) set_mode(MODE_FORCED);
    for (int i = 0; i < 400; i++) {
        uint8_t buf[1];
        _read_reg(REG_INT_STATUS, buf, 1);
        if (buf[0] & INT_STATUS_DRDY) break;
        delay_ms(5);
    }
    uint8_t buf[6];
    _read_reg(REG_TEMP_XLSB, buf, 6);
    pressure_pa = _u24(buf + 3) / 64.0f;
    temperature_c = _u24(buf) / 65536.0f;
}

float BMP581Full::altitude(float sea_level_pa) {
    float p = pressure();
    if (p <= 0.0f) return 0.0f;
    return 44330.0f * (1.0f - powf(p / sea_level_pa, 1.0f / 5.255f));
}

void BMP581Full::software_reset() {
    _write_reg(REG_CMD, SOFT_RESET_CMD);
    delay_ms(2);
    _init();
}

uint8_t BMP581Full::chip_id() {
    uint8_t buf[1];
    _read_reg(REG_CHIP_ID, buf, 1);
    return buf[0];
}

uint8_t BMP581Full::rev_id() {
    uint8_t buf[1];
    _read_reg(REG_REV_ID, buf, 1);
    return buf[0];
}

uint8_t BMP581Full::status() {
    uint8_t buf[1];
    _read_reg(REG_STATUS, buf, 1);
    return buf[0];
}

uint8_t BMP581Full::interrupt_status() {
    uint8_t buf[1];
    _read_reg(REG_INT_STATUS, buf, 1);
    return buf[0];
}

bool BMP581Full::data_ready() {
    return (interrupt_status() & INT_STATUS_DRDY) != 0;
}

void BMP581Full::configure_interrupt(uint8_t mode, uint8_t polarity, bool open_drain, bool enable) {
    uint8_t val = enable ? 0x08 : 0;
    if (open_drain) val |= 0x04;
    if (polarity)   val |= 0x02;
    if (mode)       val |= 0x01;
    _write_reg(REG_INT_CONFIG, val);
}

void BMP581Full::_set_int_source(uint8_t source, bool enable) {
    uint8_t buf[1];
    _read_reg(REG_INT_SOURCE, buf, 1);
    uint8_t cur = buf[0];
    if (enable) cur |= source;
    else        cur &= ~source;
    _write_reg(REG_INT_SOURCE, cur);
}

void BMP581Full::enable_drdy_interrupt(bool enable) {
    _set_int_source(INT_SOURCE_DRDY, enable);
}

void BMP581Full::enable_fifo_interrupt(bool threshold, bool full) {
    uint8_t buf[1];
    _read_reg(REG_INT_SOURCE, buf, 1);
    uint8_t cur = buf[0] & ~(INT_SOURCE_FIFO_FULL | INT_SOURCE_FIFO_THS);
    if (threshold) cur |= INT_SOURCE_FIFO_THS;
    if (full)      cur |= INT_SOURCE_FIFO_FULL;
    _write_reg(REG_INT_SOURCE, cur);
}

void BMP581Full::enable_oor_interrupt(bool enable) {
    _set_int_source(INT_SOURCE_OOR_P, enable);
}

void BMP581Full::set_iir_filter(uint8_t coeff_p, uint8_t coeff_t) {
    uint8_t buf[1];
    _read_reg(REG_DSP_CONFIG, buf, 1);
    uint8_t dsp = buf[0];
    dsp |= 0x28;   // shdw_sel_iir_p, shdw_sel_iir_t
    _write_reg(REG_DSP_CONFIG, dsp);
    uint8_t iir_val = ((coeff_p & 0x7) << 3) | (coeff_t & 0x7);
    _write_reg(REG_DSP_IIR, iir_val);
}

void BMP581Full::configure_fifo(uint8_t frame_sel, uint8_t mode, uint8_t threshold) {
    uint8_t prev_mode = _pwr_mode;
    if (prev_mode != MODE_STANDBY) set_mode(MODE_STANDBY);
    _write_reg(REG_FIFO_SEL, frame_sel & 0x3);
    uint8_t cfg = ((mode & 0x1) << 5) | (threshold & 0x1F);
    _write_reg(REG_FIFO_CONFIG, cfg);
    if (prev_mode != MODE_STANDBY) set_mode(prev_mode);
}

uint8_t BMP581Full::fifo_count() {
    uint8_t buf[1];
    _read_reg(REG_FIFO_COUNT, buf, 1);
    return buf[0] & 0x3F;
}

void BMP581Full::effective_osr(uint8_t& osr_p_eff, uint8_t& osr_t_eff) {
    uint8_t buf[1];
    _read_reg(REG_OSR_EFF, buf, 1);
    osr_p_eff = (buf[0] >> 3) & 0x7;
    osr_t_eff = buf[0] & 0x7;
}

bool BMP581Full::odr_is_valid() {
    uint8_t buf[1];
    _read_reg(REG_OSR_EFF, buf, 1);
    return (buf[0] & 0x80) != 0;
}

void BMP581Full::set_oor_threshold(float threshold_pa, float range_pa, uint8_t count_limit) {
    int32_t oor_thr_17bit = (int32_t)(threshold_pa * 64.0f) >> 7;
    uint8_t oor_thr_p_16 = (oor_thr_17bit >> 16) & 0x01;
    uint8_t oor_thr_p_msb = (oor_thr_17bit >> 8) & 0xFF;
    uint8_t oor_thr_p_lsb = oor_thr_17bit & 0xFF;
    _write_reg(REG_OOR_THR_P_LSB, oor_thr_p_lsb);
    _write_reg(REG_OOR_THR_P_MSB, oor_thr_p_msb);
    uint8_t range_8bit = ((int32_t)(range_pa * 64.0f) >> 7) & 0xFF;
    _write_reg(REG_OOR_RANGE, range_8bit);
    uint8_t cfg = ((count_limit & 0x3) << 6) | (oor_thr_p_16 & 0x01);
    _write_reg(REG_OOR_CONFIG, cfg);
}

uint16_t BMP581Full::nvm_read(uint8_t row) {
    uint8_t prev_mode = _pwr_mode;
    if (prev_mode != MODE_STANDBY) set_mode(MODE_STANDBY);
    uint16_t value = 0;
    {
        _write_reg(REG_NVM_ADDR, 0x5D);
        _write_reg(REG_CMD, 0xA5);
        delay_ms(2);
        _write_reg(REG_NVM_ADDR, 0x40 | (row & 0x3F));
        _write_reg(REG_CMD, 0xA5);
        delay_ms(2);
        uint8_t buf[2];
        _read_reg(REG_NVM_DATA_LSB, buf, 2);
        value = ((uint16_t)buf[1] << 8) | buf[0];
    }
    if (prev_mode != MODE_STANDBY) set_mode(prev_mode);
    return value;
}

void BMP581Full::nvm_write(uint8_t row, uint16_t value) {
    uint8_t prev_mode = _pwr_mode;
    if (prev_mode != MODE_STANDBY) set_mode(MODE_STANDBY);
    _write_reg(REG_NVM_ADDR, 0x40 | (row & 0x3F));
    _write_reg(REG_NVM_DATA_LSB, value & 0xFF);
    _write_reg(REG_NVM_DATA_MSB, (value >> 8) & 0xFF);
    _write_reg(REG_NVM_ADDR, 0x5D);
    _write_reg(REG_CMD, 0xA0);
    delay_ms(5);
    if (prev_mode != MODE_STANDBY) set_mode(prev_mode);
}