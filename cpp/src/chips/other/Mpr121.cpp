#include "Mpr121.h"
#include <assert.h>

#ifdef __linux__
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

MPR121Minimal::MPR121Minimal(Connection& connection)
    : _connection(connection) {
    _reset();
    _write_reg(REG_MHDR, 0x01);
    _write_reg(REG_NHDR, 0x01);
    _write_reg(REG_MHDF, 0x01);
    _write_reg(REG_NHDF, 0x01);
    _write_reg(REG_CDC_CONFIG, CDC_CONFIG_DEFAULT);
    _write_reg(REG_CDT_CONFIG, CDT_CONFIG_DEFAULT);
    _write_reg(REG_USL, USL_3V3);
    _write_reg(REG_TL,  TL_3V3);
    _write_reg(REG_LSL, LSL_3V3);
    _write_reg(REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT);
    for (uint8_t n = 0; n < 12; n++) {
        _write_reg(REG_E0TTH + 2 * n, TOUCH_DEFAULT);
        _write_reg(REG_E0RTH + 2 * n, RELEASE_DEFAULT);
    }
    _write_reg(REG_ECR, ECR_DEFAULT);
}

void MPR121Minimal::_reset() {
    _write_reg(REG_SRST, SOFT_RESET_KEY);
    DELAY_MS(1);
}

void MPR121Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
}

uint8_t MPR121Minimal::_read_reg(uint8_t reg) {
    uint8_t val = 0;
    _connection.write_read(&reg, 1, &val, 1);
    return val;
}

uint16_t MPR121Minimal::_read_reg16(uint8_t reg) {
    uint8_t buf[2] = { 0, 0 };
    _connection.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0]) | ((uint16_t)(buf[1] & 0x03) << 8);
}

uint16_t MPR121Minimal::touched() {
    uint8_t reg = REG_ELE0_7_TOUCH;
    uint8_t buf[2] = { 0, 0 };
    _connection.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0]) | ((uint16_t)(buf[1] & 0x0F) << 8);
}

bool MPR121Minimal::is_touched(uint8_t electrode) {
    assert(electrode < 12);
    return (touched() & (1u << electrode)) != 0;
}

// MPR121Full

MPR121Full::MPR121Full(Connection& connection)
    : MPR121Minimal(connection) {}

void MPR121Full::reset() {
    _reset();
    _write_reg(REG_MHDR, 0x01);
    _write_reg(REG_NHDR, 0x01);
    _write_reg(REG_MHDF, 0x01);
    _write_reg(REG_NHDF, 0x01);
    _write_reg(REG_CDC_CONFIG, CDC_CONFIG_DEFAULT);
    _write_reg(REG_CDT_CONFIG, CDT_CONFIG_DEFAULT);
    _write_reg(REG_USL, USL_3V3);
    _write_reg(REG_TL,  TL_3V3);
    _write_reg(REG_LSL, LSL_3V3);
    _write_reg(REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT);
    for (uint8_t n = 0; n < 12; n++) {
        _write_reg(REG_E0TTH + 2 * n, TOUCH_DEFAULT);
        _write_reg(REG_E0RTH + 2 * n, RELEASE_DEFAULT);
    }
    _write_reg(REG_ECR, ECR_DEFAULT);
}

void MPR121Full::stop() {
    _write_reg(REG_ECR, 0x00);
}

void MPR121Full::start(uint8_t n_electrodes, uint8_t cl, uint8_t eleprox_en) {
    assert(n_electrodes >= 1 && n_electrodes <= 12);
    assert(cl <= 3);
    assert(eleprox_en <= 3);
    uint8_t ecr = ((cl & 0x03) << 6) | ((eleprox_en & 0x03) << 4) | (n_electrodes & 0x0F);
    _write_reg(REG_ECR, ecr);
}

void MPR121Full::configure_thresholds(uint8_t electrode, uint8_t touch, uint8_t release) {
    assert(electrode < 12);
    _write_reg(REG_E0TTH + 2 * electrode, touch);
    _write_reg(REG_E0RTH + 2 * electrode, release);
}

void MPR121Full::configure_all_thresholds(uint8_t touch, uint8_t release) {
    for (uint8_t n = 0; n < 12; n++) {
        configure_thresholds(n, touch, release);
    }
}

void MPR121Full::configure_proximity_thresholds(uint8_t touch, uint8_t release) {
    _write_reg(REG_EPROXTTH, touch);
    _write_reg(REG_EPROXRTH, release);
}

uint16_t MPR121Full::filtered(uint8_t electrode) {
    assert(electrode <= 12);
    uint8_t addr = (electrode == 12) ? 0x1C : (uint8_t)(0x04 + 2 * electrode);
    return _read_reg16(addr);
}

uint16_t MPR121Full::baseline(uint8_t electrode) {
    assert(electrode <= 12);
    uint8_t addr = (electrode == 12) ? 0x2A : (uint8_t)(0x1E + electrode);
    return ((uint16_t)_read_reg(addr)) << 2;
}

void MPR121Full::set_baseline(uint8_t electrode, uint16_t value) {
    assert(electrode <= 12);
    uint8_t addr = (electrode == 12) ? 0x2A : (uint8_t)(0x1E + electrode);
    _write_reg(addr, (uint8_t)((value >> 2) & 0xFF));
}

uint16_t MPR121Full::oor_status() {
    uint8_t reg = REG_ELE0_7_OOR;
    uint8_t buf[2] = { 0, 0 };
    _connection.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0]) | ((uint16_t)(buf[1] & 0x1F) << 8);
}

void MPR121Full::configure_baseline_filter(uint8_t mhdr, uint8_t nhdr, uint8_t nclr, uint8_t fdlr,
                                            uint8_t mhdf, uint8_t nhdf, uint8_t nclf, uint8_t fdlf,
                                            uint8_t nhdt, uint8_t nclt, uint8_t fdlt) {
    _write_reg(REG_MHDR, mhdr & 0x3F);
    _write_reg(REG_NHDR, nhdr & 0x3F);
    _write_reg(0x2D, nclr);
    _write_reg(0x2E, fdlr);
    _write_reg(REG_MHDF, mhdf & 0x3F);
    _write_reg(REG_NHDF, nhdf & 0x3F);
    _write_reg(0x31, nclf);
    _write_reg(0x32, fdlf);
    _write_reg(0x33, nhdt & 0x3F);
    _write_reg(0x34, nclt);
    _write_reg(0x35, fdlt);
}

void MPR121Full::configure_sampling(uint8_t cdc, uint8_t cdt, uint8_t ffi,
                                     uint8_t sfi, uint8_t esi) {
    uint8_t cdc_cfg = ((ffi & 0x03) << 6) | (cdc & 0x3F);
    uint8_t cdt_cfg = ((cdt & 0x07) << 5) | ((sfi & 0x03) << 2) | (esi & 0x07);
    _write_reg(REG_CDC_CONFIG, cdc_cfg);
    _write_reg(REG_CDT_CONFIG, cdt_cfg);
}

void MPR121Full::configure_debounce(uint8_t touch, uint8_t release) {
    uint8_t deb = ((release & 0x07) << 4) | (touch & 0x07);
    _write_reg(REG_DEBOUNCE, deb);
}

void MPR121Full::configure_autoconfig(uint16_t vdd_mv, uint8_t retry,
                                       bool scts, bool are, bool ace) {
    uint16_t usl = (uint16_t)(((uint32_t)(vdd_mv - 700) * 256) / vdd_mv);
    uint8_t  tl  = (uint8_t)(usl * 0.9f);
    uint8_t  lsl = (uint8_t)(usl * 0.65f);
    _write_reg(REG_USL, (uint8_t)usl);
    _write_reg(REG_TL,  tl);
    _write_reg(REG_LSL, lsl);
    uint8_t ffi = (_read_reg(REG_CDC_CONFIG) >> 6) & 0x03;
    uint8_t autoconfig0 = ((ffi & 0x03) << 6) | ((retry & 0x03) << 4) |
                          (are ? 0x08 : 0) | (ace ? 0x01 : 0);
    _write_reg(REG_AUTOCONFIG0, autoconfig0);
    uint8_t autoconfig1 = scts ? 0x80 : 0x00;
    _write_reg(REG_AUTOCONFIG1, autoconfig1);
}

bool MPR121Full::proximity_touched() {
    return (_read_reg(REG_ELE8_PROX_TCH) & 0x10) != 0;
}

void MPR121Full::clear_overcurrent() {
    uint8_t raw = _read_reg(REG_ELE8_PROX_TCH);
    _write_reg(REG_ELE8_PROX_TCH, raw & 0x7F);
}

void MPR121Full::enable_interrupt(uint8_t source) {
    uint8_t cur = _read_reg(REG_AUTOCONFIG1) & 0x00;
    _write_reg(REG_AUTOCONFIG1, cur | (source & 0x07));
}

void MPR121Full::disable_interrupt(uint8_t source) {
    uint8_t cur = _read_reg(REG_AUTOCONFIG1);
    _write_reg(REG_AUTOCONFIG1, cur & ~(source & 0x07));
}
