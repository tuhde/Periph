#include "QMI8658A.h"

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

static const float G_MS2 = 9.80665f;
static const float DEG2RAD = 3.14159265358979f / 180.0f;

static inline int16_t le16(const uint8_t* p) {
    return static_cast<int16_t>((static_cast<uint16_t>(p[1]) << 8) | p[0]);
}

static inline int32_t iround(float v) {
    return static_cast<int32_t>(v >= 0.0f ? v + 0.5f : v - 0.5f);
}

static inline uint8_t clamp_u8(int32_t v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

// ---------------------------------------------------------------- Minimal

QMI8658AMinimal::QMI8658AMinimal(RegisterConnection& connection)
    : _connection(connection) {
    _reset_and_configure();
}

void QMI8658AMinimal::_w(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t QMI8658AMinimal::_r1(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void QMI8658AMinimal::_update_sens() {
    _accel_sens = static_cast<float>(16384 >> _accel_fs);
    _gyro_sens = static_cast<float>(2048 >> _gyro_fs);
}

void QMI8658AMinimal::_reset_and_configure() {
    DELAY_MS(15);
    _w(REG_RESET, RESET_CMD);
    DELAY_MS(15);
    _r1(REG_RESET_STATUS);                   // 0x80 after reset, informational
    if (_r1(REG_WHO_AM_I) != WHO_AM_I_VALUE) {
        abort();
    }
    _ctrl1 = 0x40; _ctrl7 = 0x03; _ctrl8 = 0x80;
    _fifo_ctrl = 0; _fifo_wtm = 0; _sync = false;
    _w(REG_CTRL1, _ctrl1);                   // ADDR_AI = 1, BE = 0
    _w(REG_CTRL8, _ctrl8);                   // CTRL9 handshake by polling
    _accel_fs = 0; _gyro_fs = 4; _accel_odr = 5; _gyro_odr = 5;
    _update_sens();
    _w(REG_CTRL2, 0x05);
    _w(REG_CTRL3, 0x45);
    _w(REG_CTRL5, 0x77);
    _w(REG_CTRL7, _ctrl7);
    DELAY_MS(165);                           // gyroscope turn-on
}

void QMI8658AMinimal::_read_data(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.read(reg, buf, len);
}

void QMI8658AMinimal::_vec(uint8_t reg, float sens, float scale, float& x, float& y, float& z) {
    uint8_t b[6];
    _read_data(reg, b, 6);
    x = le16(b) / sens * scale;
    y = le16(b + 2) / sens * scale;
    z = le16(b + 4) / sens * scale;
}

void QMI8658AMinimal::accel(float& x, float& y, float& z) {
    _vec(REG_AX_L, _accel_sens, G_MS2, x, y, z);
}

void QMI8658AMinimal::gyro(float& x, float& y, float& z) {
    _vec(REG_GX_L, _gyro_sens, DEG2RAD, x, y, z);
}

// ------------------------------------------------------------------- Full

QMI8658AFull* QMI8658AFull::_active_instance = nullptr;

QMI8658AFull::QMI8658AFull(RegisterConnection& connection)
    : QMI8658AMinimal(connection) {}

void QMI8658AFull::_set_ctrl1(uint8_t v) { _ctrl1 = v; _w(REG_CTRL1, v); }
void QMI8658AFull::_set_ctrl7(uint8_t v) { _ctrl7 = v; _w(REG_CTRL7, v); }
void QMI8658AFull::_set_ctrl8(uint8_t v) { _ctrl8 = v; _w(REG_CTRL8, v); }

bool QMI8658AFull::_wait_status(uint8_t mask, bool want, uint16_t timeout_ms) {
    for (uint16_t i = 0; i <= timeout_ms; i++) {
        bool set = (_r1(REG_STATUSINT) & mask) != 0;
        if (set == want) return true;
        DELAY_MS(1);
    }
    return false;
}

bool QMI8658AFull::_ctrl9(uint8_t cmd, uint16_t timeout_ms) {
    _w(REG_CTRL9, cmd);
    bool ok = _wait_status(0x80, true, timeout_ms);
    _w(REG_CTRL9, CMD_ACK);
    return _wait_status(0x80, false, timeout_ms) && ok;
}

bool QMI8658AFull::_cal_cmd(uint8_t cmd, const uint8_t params[8], uint16_t timeout_ms) {
    for (uint8_t i = 0; i < 8; i++) _w(REG_CAL1_L + i, params[i]);
    return _ctrl9(cmd, timeout_ms);
}

void QMI8658AFull::_two_sets(uint8_t cmd, const uint8_t set1[7], const uint8_t set2[7]) {
    uint8_t p[8];
    for (uint8_t i = 0; i < 7; i++) p[i] = set1[i];
    p[7] = 0x10;
    _cal_cmd(cmd, p);
    for (uint8_t i = 0; i < 7; i++) p[i] = set2[i];
    p[7] = 0x20;
    _cal_cmd(cmd, p);
}

uint8_t QMI8658AFull::_sensors_off() {
    uint8_t prev = _ctrl7 & 0x03;
    if (prev) {
        _set_ctrl7(_ctrl7 & ~0x03);
        DELAY_MS(5);
    }
    return prev;
}

void QMI8658AFull::_sensors_restore(uint8_t prev) {
    if (prev) {
        _set_ctrl7(_ctrl7 | prev);
        DELAY_MS((prev & 0x02) ? 165 : 10);
    }
}

void QMI8658AFull::_read_data(uint8_t reg, uint8_t* buf, size_t len) {
    if (_sync) {
        _wait_status(0x01, true, 100);       // Avail
        _wait_status(0x02, true, 100);       // Locked
    }
    _connection.read(reg, buf, len);
}

void QMI8658AFull::accel_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t b[6];
    _read_data(REG_AX_L, b, 6);
    x = le16(b); y = le16(b + 2); z = le16(b + 4);
}

void QMI8658AFull::gyro_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t b[6];
    _read_data(REG_GX_L, b, 6);
    x = le16(b); y = le16(b + 2); z = le16(b + 4);
}

void QMI8658AFull::read_all(float accel[3], float gyro[3], float& temp_c) {
    uint8_t b[14];
    _read_data(REG_TEMP_L, b, 14);
    temp_c = le16(b) / 256.0f;
    for (int i = 0; i < 3; i++) {
        accel[i] = le16(b + 2 + 2 * i) / _accel_sens * G_MS2;
        gyro[i] = le16(b + 8 + 2 * i) / _gyro_sens * DEG2RAD;
    }
}

float QMI8658AFull::temperature() {
    uint8_t b[2];
    _read_data(REG_TEMP_L, b, 2);
    return le16(b) / 256.0f;
}

uint32_t QMI8658AFull::timestamp() {
    uint8_t b[3];
    _connection.read(REG_TIMESTAMP, b, 3);
    return static_cast<uint32_t>(b[0]) | (static_cast<uint32_t>(b[1]) << 8) |
           (static_cast<uint32_t>(b[2]) << 16);
}

bool QMI8658AFull::data_ready() {
    if (_sync) return (_r1(REG_STATUSINT) & 0x01) != 0;
    return (_r1(REG_STATUS0) & 0x03) != 0;
}

void QMI8658AFull::configure_accel(uint8_t full_scale, uint8_t odr) {
    if (full_scale > 3) full_scale = 3;
    if (odr > 15 || (odr > 8 && odr < 12)) odr = 5;
    _accel_fs = full_scale;
    _accel_odr = odr;
    _update_sens();
    _w(REG_CTRL2, static_cast<uint8_t>((full_scale << 4) | odr));
}

void QMI8658AFull::configure_gyro(uint8_t full_scale, uint8_t odr) {
    if (full_scale > 7) full_scale = 7;
    if (odr > 8) odr = 8;
    _gyro_fs = full_scale;
    _gyro_odr = odr;
    _update_sens();
    _w(REG_CTRL3, static_cast<uint8_t>((full_scale << 4) | odr));
}

void QMI8658AFull::configure_filters(bool accel_filter, uint8_t accel_mode,
                                     bool gyro_filter, uint8_t gyro_mode) {
    _w(REG_CTRL5, static_cast<uint8_t>(((gyro_mode & 3) << 5) | (gyro_filter ? 0x10 : 0) |
                                       ((accel_mode & 3) << 1) | (accel_filter ? 1 : 0)));
}

void QMI8658AFull::set_sensors(bool accel, bool gyro, bool gyro_snooze) {
    uint8_t v = _ctrl7 & ~0x13;
    v |= (accel ? 0x01 : 0) | (gyro ? 0x02 : 0) | (gyro_snooze ? 0x10 : 0);
    _set_ctrl7(v);
}

void QMI8658AFull::set_power_down(bool down) {
    if (down) {
        _set_ctrl7(_ctrl7 & ~0x03);
        _set_ctrl1(_ctrl1 | 0x01);
    } else {
        _set_ctrl1(_ctrl1 & ~0x01);
        DELAY_MS(15);
    }
}

void QMI8658AFull::set_sync_sample(bool enabled) {
    uint8_t p[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    if (enabled) {
        p[0] = 1;
        _cal_cmd(CMD_AHB_CLOCK_GATING, p);
        _set_ctrl7(_ctrl7 | 0x80);
    } else {
        _set_ctrl7(_ctrl7 & ~0x80);
        _cal_cmd(CMD_AHB_CLOCK_GATING, p);
    }
    _sync = enabled;
}

void QMI8658AFull::configure_fifo(uint8_t mode, uint8_t size, uint8_t watermark) {
    if (mode > 2) mode = 2;
    if (size > 3) size = 3;
    _fifo_wtm = watermark;
    _w(REG_FIFO_WTM_TH, _fifo_wtm);
    _fifo_ctrl = static_cast<uint8_t>((size << 2) | mode);
    _w(REG_FIFO_CTRL, _fifo_ctrl);
    if (mode == 0) _set_ctrl7(_ctrl7 & ~0x20);   // DRDY back on
}

uint8_t QMI8658AFull::fifo_status() { return _r1(REG_FIFO_STATUS); }

uint16_t QMI8658AFull::fifo_count() {
    uint8_t st = _r1(REG_FIFO_STATUS);
    uint8_t lo = _r1(REG_FIFO_SMPL_CNT);
    return static_cast<uint16_t>(2 * (((st & 0x03) << 8) | lo));
}

uint16_t QMI8658AFull::read_fifo(uint8_t* buf, uint16_t len) {
    uint16_t total = fifo_count();
    uint16_t n = len < total ? len : total;
    if (n == 0) return 0;
    _ctrl9(CMD_REQ_FIFO);
    _w(REG_CTRL1, _ctrl1 & ~0x40);               // ADDR_AI off: FIFO_DATA repeats
    uint16_t got = 0;
    while (got < n) {
        uint8_t chunk = static_cast<uint8_t>((n - got) > 32 ? 32 : (n - got));
        _connection.read(REG_FIFO_DATA, buf + got, chunk);
        got += chunk;
    }
    _w(REG_CTRL1, _ctrl1);
    _w(REG_FIFO_CTRL, _fifo_ctrl & 0x7F);
    return got;
}

void QMI8658AFull::reset_fifo() { _ctrl9(CMD_RST_FIFO); }

void QMI8658AFull::_send_motion() {
    uint8_t prev = _sensors_off();
    uint8_t a = clamp_u8(iround(_any_thr * 32.0f));
    uint8_t n = clamp_u8(iround(_no_thr * 32.0f));
    uint8_t mode = static_cast<uint8_t>((_no_and ? 0x80 : 0) | ((_no_cfg_axes & 7) << 4) |
                                        (_any_and ? 0x08 : 0) | (_any_cfg_axes & 7));
    const uint8_t s1[7] = {a, a, a, n, n, n, mode};
    const uint8_t s2[7] = {_any_win, _no_win, static_cast<uint8_t>(_sig_wait & 0xFF),
                           static_cast<uint8_t>(_sig_wait >> 8), static_cast<uint8_t>(_sig_confirm & 0xFF),
                           static_cast<uint8_t>(_sig_confirm >> 8), 0};
    _two_sets(CMD_CONFIG_MOTION, s1, s2);
    _sensors_restore(prev);
}

void QMI8658AFull::configure_any_motion(float threshold_g, uint8_t window, uint8_t axes, bool logic_and) {
    _any_thr = threshold_g; _any_win = window; _any_cfg_axes = axes; _any_and = logic_and;
    _send_motion();
}

void QMI8658AFull::configure_no_motion(float threshold_g, uint8_t window, uint8_t axes, bool logic_and) {
    _no_thr = threshold_g; _no_win = window; _no_cfg_axes = axes; _no_and = logic_and;
    _send_motion();
}

void QMI8658AFull::configure_significant_motion(uint16_t wait_window, uint16_t confirm_window) {
    _sig_wait = wait_window; _sig_confirm = confirm_window;
    _send_motion();
}

void QMI8658AFull::enable_motion(bool any_motion, bool no_motion, bool significant) {
    uint8_t v = _ctrl8 & ~0x0E;
    v |= (any_motion ? 0x02 : 0) | (no_motion ? 0x04 : 0) | (significant ? 0x08 : 0);
    _set_ctrl8(v);
}

void QMI8658AFull::configure_tap(uint8_t priority, uint16_t peak_window, uint16_t tap_window,
                                 uint16_t double_tap_window, float alpha, float gamma,
                                 float peak_threshold, float quiet_threshold) {
    uint16_t peak = static_cast<uint16_t>(iround(peak_threshold * 1024.0f));
    uint16_t quiet = static_cast<uint16_t>(iround(quiet_threshold * 1024.0f));
    uint8_t prev = _sensors_off();
    const uint8_t s1[7] = {static_cast<uint8_t>(peak_window), priority, static_cast<uint8_t>(tap_window & 0xFF),
                           static_cast<uint8_t>(tap_window >> 8), static_cast<uint8_t>(double_tap_window & 0xFF),
                           static_cast<uint8_t>(double_tap_window >> 8), 0};
    const uint8_t s2[7] = {static_cast<uint8_t>(iround(alpha * 128.0f) & 0x7F),
                           static_cast<uint8_t>(iround(gamma * 128.0f) & 0x7F),
                           static_cast<uint8_t>(peak & 0xFF), static_cast<uint8_t>(peak >> 8),
                           static_cast<uint8_t>(quiet & 0xFF), static_cast<uint8_t>(quiet >> 8), 0};
    _two_sets(CMD_CONFIG_TAP, s1, s2);
    _sensors_restore(prev);
}

void QMI8658AFull::enable_tap(bool enabled) {
    _set_ctrl8(enabled ? (_ctrl8 | 0x01) : (_ctrl8 & ~0x01));
}

void QMI8658AFull::tap_status(uint8_t& count, uint8_t& axis, bool& negative) {
    uint8_t v = _r1(REG_TAP_STATUS);
    count = v & 0x03;
    axis = (v >> 4) & 0x03;
    negative = (v & 0x80) != 0;
}

void QMI8658AFull::configure_pedometer(uint16_t sample_count, float peak_to_peak_mg, float peak_mg,
                                       uint16_t time_up, uint8_t time_low, uint8_t entry_count,
                                       uint8_t precision, uint8_t report_every) {
    uint16_t p2p = static_cast<uint16_t>(iround(peak_to_peak_mg / 1000.0f * 1024.0f));
    uint16_t pk = static_cast<uint16_t>(iround(peak_mg / 1000.0f * 1024.0f));
    uint8_t prev = _sensors_off();
    const uint8_t s1[7] = {static_cast<uint8_t>(sample_count & 0xFF), static_cast<uint8_t>(sample_count >> 8),
                           static_cast<uint8_t>(p2p & 0xFF), static_cast<uint8_t>(p2p >> 8),
                           static_cast<uint8_t>(pk & 0xFF), static_cast<uint8_t>(pk >> 8), 0};
    const uint8_t s2[7] = {static_cast<uint8_t>(time_up & 0xFF), static_cast<uint8_t>(time_up >> 8),
                           time_low, entry_count, precision, report_every, 0};
    _two_sets(CMD_CONFIG_PEDOMETER, s1, s2);
    _sensors_restore(prev);
}

void QMI8658AFull::enable_pedometer(bool enabled) {
    _set_ctrl8(enabled ? (_ctrl8 | 0x10) : (_ctrl8 & ~0x10));
}

uint32_t QMI8658AFull::step_count() {
    uint8_t b[3];
    _connection.read(REG_STEP_CNT, b, 3);
    return static_cast<uint32_t>(b[0]) | (static_cast<uint32_t>(b[1]) << 8) |
           (static_cast<uint32_t>(b[2]) << 16);
}

void QMI8658AFull::reset_step_count() { _ctrl9(CMD_RESET_PEDOMETER); }

void QMI8658AFull::set_wake_on_motion(uint8_t threshold_mg, uint8_t interrupt_pin,
                                      uint8_t initial_level, uint8_t blanking_samples) {
    _sensors_off();
    uint8_t p[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    if (threshold_mg == 0) {
        _cal_cmd(CMD_WRITE_WOM, p);
        _int_sources &= ~SOURCE_WAKE_ON_MOTION;
        _update_int_pins();
        return;
    }
    _accel_fs = 0; _accel_odr = 12;
    _update_sens();
    _w(REG_CTRL2, 0x0C);
    _wom_pin = interrupt_pin == 2 ? 2 : 1;
    p[0] = threshold_mg;
    p[1] = static_cast<uint8_t>((_wom_pin == 2 ? 0x40 : 0) | (initial_level ? 0x80 : 0) | (blanking_samples & 0x3F));
    _cal_cmd(CMD_WRITE_WOM, p);
    _int_sources |= SOURCE_WAKE_ON_MOTION;
    _update_int_pins();
    _set_ctrl7((_ctrl7 & ~0x03) | 0x01);
    DELAY_MS(10);
}

void QMI8658AFull::_offset_cmd(uint8_t cmd, const int16_t codes[3]) {
    uint8_t prev = _sensors_off();
    uint8_t p[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    for (int i = 0; i < 3; i++) {
        uint16_t u = static_cast<uint16_t>(codes[i]);
        p[2 * i] = u & 0xFF;
        p[2 * i + 1] = u >> 8;
    }
    _cal_cmd(cmd, p);
    _sensors_restore(prev);
}

void QMI8658AFull::set_accel_offset(float x, float y, float z) {
    const int16_t c[3] = {static_cast<int16_t>(iround(x / G_MS2 * 4096.0f)),
                          static_cast<int16_t>(iround(y / G_MS2 * 4096.0f)),
                          static_cast<int16_t>(iround(z / G_MS2 * 4096.0f))};
    _offset_cmd(CMD_ACCEL_OFFSET, c);
}

void QMI8658AFull::set_gyro_offset(float x, float y, float z) {
    const int16_t c[3] = {static_cast<int16_t>(iround(x / DEG2RAD * 32.0f)),
                          static_cast<int16_t>(iround(y / DEG2RAD * 32.0f)),
                          static_cast<int16_t>(iround(z / DEG2RAD * 32.0f))};
    _offset_cmd(CMD_GYRO_OFFSET, c);
}

bool QMI8658AFull::calibrate_gyro(uint8_t* cod_status) {
    uint8_t prev = _sensors_off();
    _ctrl9(CMD_COD, 3000);
    uint8_t st = _r1(REG_COD_STATUS);
    _sensors_restore(prev);
    if (cod_status) *cod_status = st;
    return st == 0;
}

void QMI8658AFull::get_gyro_gains(uint16_t& x, uint16_t& y, uint16_t& z) {
    uint8_t b[6];
    _connection.read(REG_DVX_L, b, 6);
    x = static_cast<uint16_t>(b[0] | (b[1] << 8));
    y = static_cast<uint16_t>(b[2] | (b[3] << 8));
    z = static_cast<uint16_t>(b[4] | (b[5] << 8));
}

void QMI8658AFull::set_gyro_gains(uint16_t x, uint16_t y, uint16_t z) {
    uint8_t prev = _sensors_off();
    uint8_t p[8] = {static_cast<uint8_t>(x & 0xFF), static_cast<uint8_t>(x >> 8),
                    static_cast<uint8_t>(y & 0xFF), static_cast<uint8_t>(y >> 8),
                    static_cast<uint8_t>(z & 0xFF), static_cast<uint8_t>(z >> 8), 0, 0};
    _cal_cmd(CMD_APPLY_GYRO_GAINS, p);
    _sensors_restore(prev);
}

bool QMI8658AFull::_self_test(uint8_t ctrl_reg, uint8_t saved, float scale, float out[3]) {
    uint8_t prev = _sensors_off();
    _w(ctrl_reg, saved | 0x80);
    _wait_status(0x01, true, 3000);
    uint8_t b[6];
    _connection.read(REG_DVX_L, b, 6);
    _w(ctrl_reg, saved);
    _wait_status(0x01, false, 3000);
    _sensors_restore(prev);
    for (int i = 0; i < 3; i++) out[i] = le16(b + 2 * i) / scale;
    return true;
}

bool QMI8658AFull::self_test_accel(float xyz[3]) {
    float g[3];
    _self_test(REG_CTRL2, static_cast<uint8_t>((_accel_fs << 4) | _accel_odr), 2048.0f, g);
    bool pass = true;
    for (int i = 0; i < 3; i++) {
        if (!(g[i] > 0.2f || g[i] < -0.2f)) pass = false;
        xyz[i] = g[i] * G_MS2;
    }
    return pass;
}

bool QMI8658AFull::self_test_gyro(float xyz[3]) {
    float d[3];
    _self_test(REG_CTRL3, static_cast<uint8_t>((_gyro_fs << 4) | _gyro_odr), 16.0f, d);
    bool pass = true;
    for (int i = 0; i < 3; i++) {
        if (!(d[i] > 300.0f || d[i] < -300.0f)) pass = false;
        xyz[i] = d[i] * DEG2RAD;
    }
    return pass;
}

void QMI8658AFull::device_info(uint8_t& revision, uint8_t firmware[3], uint8_t usid[6]) {
    _ctrl9(CMD_COPY_USID);
    revision = _r1(REG_REVISION_ID);
    _connection.read(REG_FW_VERSION, firmware, 2);
    firmware[2] = _r1(REG_FW_VERSION + 2);
    _connection.read(REG_DVX_L, usid, 6);
}

void QMI8658AFull::set_pullups(bool aux, bool sdx, bool cs, bool i2c) {
    uint8_t p[8] = {static_cast<uint8_t>((aux ? 0 : 1) | (sdx ? 0 : 2) | (cs ? 0 : 4) | (i2c ? 0 : 8)),
                    0, 0, 0, 0, 0, 0, 0};
    _cal_cmd(CMD_SET_RPU, p);
}

void QMI8658AFull::reset() {
    _w(REG_RESET, RESET_CMD);
    DELAY_MS(15);
    _ctrl1 = 0x40; _ctrl7 = 0x00; _ctrl8 = 0x80;
    _w(REG_CTRL1, _ctrl1);
    _w(REG_CTRL8, _ctrl8);
    _sync = false;
    _int_sources = 0;
}

uint8_t QMI8658AFull::_source_pin(uint16_t source) const {
    if (source == SOURCE_DATA_READY) return 2;
    if (source == SOURCE_FIFO_WATERMARK || source == SOURCE_FIFO_FULL) return _fifo_pin;
    if (source == SOURCE_WAKE_ON_MOTION) return _wom_pin;
    return _activity_pin;
}

void QMI8658AFull::_update_int_pins() {
    uint8_t pins = 0;
    for (uint8_t bit = 0; bit < 9; bit++) {
        uint16_t s = static_cast<uint16_t>(1u << bit);
        if (_int_sources & s) pins |= static_cast<uint8_t>(1u << (_source_pin(s) - 1));
    }
    uint8_t v = _ctrl1 & ~0x18;
    v |= (pins & 1 ? 0x08 : 0) | (pins & 2 ? 0x10 : 0);
    v = _fifo_pin == 1 ? (v | 0x04) : (v & ~0x04);
    if (v != _ctrl1) _set_ctrl1(v);
}

void QMI8658AFull::set_interrupt_pins(uint8_t fifo_pin, uint8_t activity_pin) {
    _fifo_pin = fifo_pin == 1 ? 1 : 2;
    _activity_pin = activity_pin == 1 ? 1 : 2;
    _set_ctrl8(_activity_pin == 1 ? (_ctrl8 | 0x40) : (_ctrl8 & ~0x40));
    _update_int_pins();
}

static uint8_t engine_bit(uint16_t source) {
    switch (source) {
        case QMI8658AFull::SOURCE_ANY_MOTION:         return 0x02;
        case QMI8658AFull::SOURCE_NO_MOTION:          return 0x04;
        case QMI8658AFull::SOURCE_SIGNIFICANT_MOTION: return 0x08;
        case QMI8658AFull::SOURCE_STEP:               return 0x10;
        case QMI8658AFull::SOURCE_TAP:                return 0x01;
        default:                                      return 0x00;
    }
}

void QMI8658AFull::enable_interrupt(uint16_t source) {
    if (source == SOURCE_DATA_READY) _set_ctrl7(_ctrl7 & ~0x20);
    else if (source == SOURCE_FIFO_WATERMARK) _w(REG_FIFO_WTM_TH, _fifo_wtm);
    else if (engine_bit(source)) _set_ctrl8(_ctrl8 | engine_bit(source));
    _int_sources |= source;
    _update_int_pins();
}

void QMI8658AFull::disable_interrupt(uint16_t source) {
    if (source == SOURCE_DATA_READY) _set_ctrl7(_ctrl7 | 0x20);
    else if (source == SOURCE_FIFO_WATERMARK) _w(REG_FIFO_WTM_TH, 0);
    else if (engine_bit(source)) _set_ctrl8(_ctrl8 & ~engine_bit(source));
    _int_sources &= ~source;
    _update_int_pins();
}

uint16_t QMI8658AFull::poll_interrupt() {
    uint8_t s[2];
    _connection.read(REG_STATUS0, s, 2);
    uint8_t fs = _r1(REG_FIFO_STATUS);
    uint16_t status = 0;
    if (s[0] & 0x03) status |= SOURCE_DATA_READY;
    if (fs & 0x40) status |= SOURCE_FIFO_WATERMARK;
    if (fs & 0x80) status |= SOURCE_FIFO_FULL;
    if (s[1] & 0x20) status |= SOURCE_ANY_MOTION;
    if (s[1] & 0x40) status |= SOURCE_NO_MOTION;
    if (s[1] & 0x80) status |= SOURCE_SIGNIFICANT_MOTION;
    if (s[1] & 0x10) status |= SOURCE_STEP;
    if (s[1] & 0x02) status |= SOURCE_TAP;
    if (s[1] & 0x04) status |= SOURCE_WAKE_ON_MOTION;
    return status;
}

void QMI8658AFull::on_interrupt(void (*callback)(uint16_t status)) {
    _int_callback = callback;
    _int_pin = _connection.intPin();
    _active_instance = this;
    if (_int_pin) _int_pin->onEdge(&_edge_trampoline, InputPin::kRising);
}

void QMI8658AFull::off_interrupt() {
    if (_int_pin) _int_pin->offEdge(&_edge_trampoline);
    _int_pin = nullptr;
    _int_callback = nullptr;
    if (_active_instance == this) _active_instance = nullptr;
}

void QMI8658AFull::_edge_trampoline() {
    if (_active_instance) _active_instance->_handle_edge();
}

void QMI8658AFull::_handle_edge() {
    if (!_int_callback) return;
    uint16_t status = poll_interrupt();
    if (status) _int_callback(status);
}
