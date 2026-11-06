#include "ICM20948.h"

// Out-of-class definitions for ODR-used static constexpr members; required
// before C++17 (AVR Arduino builds as C++11), redundant but valid after.
constexpr float ICM20948Minimal::ACCEL_SENSITIVITY[4];
constexpr float ICM20948Minimal::GYRO_SENSITIVITY[4];

#include <stdlib.h>
#include <math.h>

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

static inline int16_t be16(const uint8_t* p) {
    return static_cast<int16_t>((static_cast<uint16_t>(p[0]) << 8) | p[1]);
}

static inline int16_t le16(const uint8_t* p) {
    return static_cast<int16_t>((static_cast<uint16_t>(p[1]) << 8) | p[0]);
}

// ---------------------------------------------------------------- Minimal

ICM20948Minimal::ICM20948Minimal(RegisterConnection& connection)
    : _connection(connection) {
    _select_bank(0, true);
    uint8_t who = _rd1(0, REG_WHO_AM_I);
    if (who != WHO_AM_I_VALUE) {
        abort();
    }
    _wr1(0, REG_PWR_MGMT_1, 0x80);      // DEVICE_RESET
    DELAY_MS(100);
    _bank = 0;                           // reset returns to bank 0
    _wr1(0, REG_PWR_MGMT_1, 0x01);      // SLEEP = 0, CLKSEL = 1
    _wr1(0, REG_PWR_MGMT_2, 0x00);      // all axes on
    _write_gyro_config();
    _write_accel_config();
    _wr1(2, REG_GYRO_SMPLRT_DIV, 0x0A);
    _wr1(2, REG_ACCEL_SMPLRT_DIV_1, 0x00);
    _wr1(2, REG_ACCEL_SMPLRT_DIV_2, 0x0A);
    DELAY_MS(35);
}

void ICM20948Minimal::_select_bank(uint8_t bank, bool force) {
    if (force || bank != _bank) {
        uint8_t v = static_cast<uint8_t>(bank << 4);
        _connection.write(REG_BANK_SEL, &v, 1);
        _bank = bank;
    }
}

void ICM20948Minimal::_rd(uint8_t bank, uint8_t reg, uint8_t* buf, size_t len) {
    _select_bank(bank);
    _connection.read(reg, buf, len);
    if (bank != 0) _select_bank(0);
}

void ICM20948Minimal::_wr(uint8_t bank, uint8_t reg, const uint8_t* data, size_t len) {
    _select_bank(bank);
    _connection.write(reg, data, len);
    if (bank != 0) _select_bank(0);
}

void ICM20948Minimal::_wr1(uint8_t bank, uint8_t reg, uint8_t value) {
    _wr(bank, reg, &value, 1);
}

uint8_t ICM20948Minimal::_rd1(uint8_t bank, uint8_t reg) {
    uint8_t v = 0;
    _rd(bank, reg, &v, 1);
    return v;
}

void ICM20948Minimal::_write_gyro_config() {
    _wr1(2, REG_GYRO_CONFIG_1,
         static_cast<uint8_t>((_gyro_dlpf << 3) | (_gyro_fs << 1) | (_gyro_filter ? 1 : 0)));
}

void ICM20948Minimal::_write_accel_config() {
    _wr1(2, REG_ACCEL_CONFIG,
         static_cast<uint8_t>((_accel_dlpf << 3) | (_accel_fs << 1) | (_accel_filter ? 1 : 0)));
}

void ICM20948Minimal::accel(float& x, float& y, float& z) {
    uint8_t buf[6];
    _rd(0, REG_ACCEL_XOUT_H, buf, 6);
    float sens = ACCEL_SENSITIVITY[_accel_fs];
    x = be16(buf) / sens * G_MS2;
    y = be16(buf + 2) / sens * G_MS2;
    z = be16(buf + 4) / sens * G_MS2;
}

void ICM20948Minimal::gyro(float& x, float& y, float& z) {
    uint8_t buf[6];
    _rd(0, REG_GYRO_XOUT_H, buf, 6);
    float sens = GYRO_SENSITIVITY[_gyro_fs];
    x = be16(buf) / sens * DEG2RAD;
    y = be16(buf + 2) / sens * DEG2RAD;
    z = be16(buf + 4) / sens * DEG2RAD;
}

// ------------------------------------------------------------------ Full

ICM20948Full* ICM20948Full::_active_instance = nullptr;

ICM20948Full::ICM20948Full(RegisterConnection& connection)
    : ICM20948Minimal(connection) {}

void ICM20948Full::_lp_suspend() {
    if (_lp_en && !_lp_suspended) {
        uint8_t v = _rd1(0, REG_PWR_MGMT_1);
        _wr1(0, REG_PWR_MGMT_1, static_cast<uint8_t>(v & ~0x20));
        _lp_suspended = true;
    }
}

void ICM20948Full::_lp_resume() {
    if (_lp_suspended) {
        uint8_t v = _rd1(0, REG_PWR_MGMT_1);
        _wr1(0, REG_PWR_MGMT_1, static_cast<uint8_t>(v | 0x20));
        _lp_suspended = false;
    }
}

void ICM20948Full::accel_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _rd(0, REG_ACCEL_XOUT_H, buf, 6);
    x = be16(buf); y = be16(buf + 2); z = be16(buf + 4);
}

void ICM20948Full::gyro_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t buf[6];
    _rd(0, REG_GYRO_XOUT_H, buf, 6);
    x = be16(buf); y = be16(buf + 2); z = be16(buf + 4);
}

void ICM20948Full::read_all(float accel[3], float gyro[3], float& temp_c) {
    uint8_t buf[14];
    _rd(0, REG_ACCEL_XOUT_H, buf, 14);
    float a_sens = ACCEL_SENSITIVITY[_accel_fs];
    float g_sens = GYRO_SENSITIVITY[_gyro_fs];
    for (int i = 0; i < 3; i++) {
        accel[i] = be16(buf + 2 * i) / a_sens * G_MS2;
        gyro[i] = be16(buf + 6 + 2 * i) / g_sens * DEG2RAD;
    }
    temp_c = be16(buf + 12) / 333.87f + 21.0f;
}

float ICM20948Full::temperature() {
    uint8_t buf[2];
    _rd(0, REG_TEMP_OUT_H, buf, 2);
    return be16(buf) / 333.87f + 21.0f;
}

void ICM20948Full::configure_gyro(uint8_t full_scale) {
    _lp_suspend();
    _gyro_fs = full_scale & 0x03;
    _write_gyro_config();
    _lp_resume();
}

void ICM20948Full::configure_accel(uint8_t full_scale) {
    _lp_suspend();
    _accel_fs = full_scale & 0x03;
    _write_accel_config();
    _lp_resume();
}

void ICM20948Full::configure_filters(uint8_t gyro_dlpf, uint8_t accel_dlpf,
                                     bool gyro_filter, bool accel_filter) {
    _lp_suspend();
    _gyro_dlpf = gyro_dlpf & 0x07;
    _accel_dlpf = accel_dlpf & 0x07;
    _gyro_filter = gyro_filter;
    _accel_filter = accel_filter;
    _write_gyro_config();
    _write_accel_config();
    _lp_resume();
}

void ICM20948Full::configure_sample_rate(uint8_t gyro_div, uint16_t accel_div) {
    _lp_suspend();
    _wr1(2, REG_GYRO_SMPLRT_DIV, gyro_div);
    _wr1(2, REG_ACCEL_SMPLRT_DIV_1, static_cast<uint8_t>((accel_div >> 8) & 0x0F));
    _wr1(2, REG_ACCEL_SMPLRT_DIV_2, static_cast<uint8_t>(accel_div & 0xFF));
    _lp_resume();
}

void ICM20948Full::set_sensors(bool accel, bool gyro) {
    _wr1(0, REG_PWR_MGMT_2, static_cast<uint8_t>((accel ? 0 : 0x38) | (gyro ? 0 : 0x07)));
}

void ICM20948Full::set_sleep(bool sleep) {
    uint8_t v = _rd1(0, REG_PWR_MGMT_1);
    v = sleep ? static_cast<uint8_t>(v | 0x40) : static_cast<uint8_t>(v & ~0x40);
    _wr1(0, REG_PWR_MGMT_1, v);
}

void ICM20948Full::set_low_power(bool accel, bool gyro, uint8_t accel_avg, uint8_t gyro_avg) {
    _lp_suspended = false;
    uint8_t v = static_cast<uint8_t>(_rd1(0, REG_PWR_MGMT_1) & ~0x20);
    _wr1(0, REG_PWR_MGMT_1, v);
    _wr1(2, REG_ACCEL_CONFIG_2, accel_avg & 0x03);
    _wr1(2, REG_GYRO_CONFIG_2, gyro_avg & 0x07);
    _wr1(0, REG_LP_CONFIG, static_cast<uint8_t>(0x40 | (accel ? 0x20 : 0) | (gyro ? 0x10 : 0)));
    _lp_en = accel || gyro;
    if (_lp_en) _wr1(0, REG_PWR_MGMT_1, static_cast<uint8_t>(v | 0x20));
}

void ICM20948Full::set_gyro_offset(float x, float y, float z) {
    uint8_t data[6];
    float v[3] = {x, y, z};
    for (int i = 0; i < 3; i++) {
        float code = roundf(v[i] / DEG2RAD / 0.0305f);
        if (code > 32767.0f) code = 32767.0f;
        if (code < -32768.0f) code = -32768.0f;
        uint16_t u = static_cast<uint16_t>(static_cast<int16_t>(code));
        data[2 * i] = static_cast<uint8_t>(u >> 8);
        data[2 * i + 1] = static_cast<uint8_t>(u & 0xFF);
    }
    _lp_suspend();
    _wr(2, REG_XG_OFFS_USRH, data, 6);
    _lp_resume();
}

void ICM20948Full::get_accel_offset(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t raw[8];
    _rd(1, REG_XA_OFFS_H, raw, 8);
    int16_t* out[3] = {&x, &y, &z};
    for (int i = 0; i < 3; i++) {
        int32_t code = (static_cast<int32_t>(raw[3 * i]) << 7) | (raw[3 * i + 1] >> 1);
        if (code & 0x4000) code -= 0x8000;
        *out[i] = static_cast<int16_t>(code);
    }
}

void ICM20948Full::set_accel_offset(int16_t x, int16_t y, int16_t z) {
    static const uint8_t regs[3] = {0x14, 0x17, 0x1A};
    int16_t v[3] = {x, y, z};
    _lp_suspend();
    for (int i = 0; i < 3; i++) {
        uint16_t code = static_cast<uint16_t>(v[i]) & 0x7FFF;
        uint8_t d[2] = {static_cast<uint8_t>((code >> 7) & 0xFF),
                        static_cast<uint8_t>((code << 1) & 0xFE)};
        _wr(1, regs[i], d, 2);
    }
    _lp_resume();
}

bool ICM20948Full::_slv4_wait() {
    for (int i = 0; i < 100; i++) {
        uint8_t status = _rd1(0, REG_I2C_MST_STATUS);
        if (status & 0x10) return false;     // NACK
        if (status & 0x40) return true;      // SLV4_DONE
        DELAY_MS(1);
    }
    return false;
}

bool ICM20948Full::aux_read_byte(uint8_t address, uint8_t reg, uint8_t& value) {
    _select_bank(3);
    uint8_t a = static_cast<uint8_t>(0x80 | (address & 0x7F));
    uint8_t ctrl = 0x80;
    _connection.write(REG_I2C_SLV4_ADDR, &a, 1);
    _connection.write(REG_I2C_SLV4_REG, &reg, 1);
    _connection.write(REG_I2C_SLV4_CTRL, &ctrl, 1);
    _select_bank(0);
    if (!_slv4_wait()) return false;
    value = _rd1(3, REG_I2C_SLV4_DI);
    return true;
}

bool ICM20948Full::aux_write_byte(uint8_t address, uint8_t reg, uint8_t value) {
    _select_bank(3);
    uint8_t a = address & 0x7F;
    uint8_t ctrl = 0x80;
    _connection.write(REG_I2C_SLV4_ADDR, &a, 1);
    _connection.write(REG_I2C_SLV4_REG, &reg, 1);
    _connection.write(REG_I2C_SLV4_DO, &value, 1);
    _connection.write(REG_I2C_SLV4_CTRL, &ctrl, 1);
    _select_bank(0);
    return _slv4_wait();
}

bool ICM20948Full::enable_mag() {
    _lp_suspend();
    uint8_t ctrl = _rd1(0, REG_USER_CTRL);
    _wr1(0, REG_USER_CTRL, static_cast<uint8_t>(ctrl | 0x20));   // I2C_MST_EN
    _wr1(3, REG_I2C_MST_CTRL, 0x07);                             // 345.6 kHz
    bool ok = aux_write_byte(AK_ADDR, AK_CNTL3, 0x01);           // soft reset
    DELAY_MS(2);
    uint8_t wia = 0;
    ok = ok && aux_read_byte(AK_ADDR, AK_WIA2, wia) && wia == AK_WIA2_VALUE;
    ok = ok && aux_write_byte(AK_ADDR, AK_CNTL2, 0x08);          // continuous 4
    if (ok) {
        _select_bank(3);
        uint8_t a = static_cast<uint8_t>(0x80 | AK_ADDR);
        uint8_t r = AK_ST1;
        uint8_t c = 0x89;
        _connection.write(REG_I2C_SLV0_ADDR, &a, 1);
        _connection.write(REG_I2C_SLV0_REG, &r, 1);
        _connection.write(REG_I2C_SLV0_CTRL, &c, 1);
        _select_bank(0);
        _mag_enabled = true;
    }
    _lp_resume();
    return ok;
}

bool ICM20948Full::mag_set_mode(uint8_t mode) {
    return aux_write_byte(AK_ADDR, AK_CNTL2, mode & 0x1F);
}

bool ICM20948Full::_mag_block(uint8_t* block) {
    if (!_mag_enabled) return false;
    _rd(0, REG_EXT_SLV_SENS_DATA_00, block, 9);
    return true;
}

bool ICM20948Full::mag(float& x, float& y, float& z) {
    uint8_t b[9];
    if (!_mag_block(b)) return false;
    if (b[8] & 0x08) return false;       // HOFL
    x = le16(b + 1) * MAG_SENSITIVITY;
    y = le16(b + 3) * MAG_SENSITIVITY;
    z = le16(b + 5) * MAG_SENSITIVITY;
    return true;
}

bool ICM20948Full::mag_raw(int16_t& x, int16_t& y, int16_t& z) {
    uint8_t b[9];
    if (!_mag_block(b)) return false;
    x = le16(b + 1); y = le16(b + 3); z = le16(b + 5);
    return true;
}

bool ICM20948Full::mag_data_ready() {
    uint8_t b[9];
    if (!_mag_block(b)) return false;
    return (b[0] & 0x01) != 0;
}

bool ICM20948Full::data_ready() {
    return (_rd1(0, REG_INT_STATUS_1) & 0x01) != 0;
}

void ICM20948Full::enable_fifo(bool accel, bool gyro, bool temp) {
    _lp_suspend();
    _wr1(0, REG_FIFO_EN_2, static_cast<uint8_t>((accel ? 0x10 : 0) | (gyro ? 0x0E : 0) | (temp ? 0x01 : 0)));
    uint8_t ctrl = _rd1(0, REG_USER_CTRL);
    _wr1(0, REG_USER_CTRL, static_cast<uint8_t>(ctrl | 0x40));
    reset_fifo();
    _lp_resume();
}

void ICM20948Full::set_fifo_mode(bool snapshot) {
    _lp_suspend();
    _wr1(0, REG_FIFO_MODE, snapshot ? 0x1F : 0x00);
    _lp_resume();
}

uint16_t ICM20948Full::fifo_count() {
    uint8_t b[2];
    _rd(0, REG_FIFO_COUNTH, b, 2);
    return static_cast<uint16_t>(((b[0] & 0x1F) << 8) | b[1]);
}

uint16_t ICM20948Full::read_fifo(uint8_t* buf, uint16_t len) {
    uint16_t available = fifo_count();
    uint16_t count = len < available ? len : available;
    uint16_t done = 0;
    while (done < count) {
        uint16_t n = count - done;
        if (n > 24) n = 24;
        _rd(0, REG_FIFO_R_W, buf + done, n);
        done += n;
    }
    return done;
}

void ICM20948Full::reset_fifo() {
    _wr1(0, REG_FIFO_RST, 0x1F);
    _wr1(0, REG_FIFO_RST, 0x00);
}

void ICM20948Full::set_wake_on_motion(float threshold_mg, bool enabled) {
    _lp_suspend();
    float thr = roundf(threshold_mg / 4.0f);
    if (thr < 0.0f) thr = 0.0f;
    if (thr > 255.0f) thr = 255.0f;
    _wr1(2, REG_ACCEL_WOM_THR, static_cast<uint8_t>(thr));
    _wr1(2, REG_ACCEL_INTEL_CTRL, enabled ? 0x03 : 0x00);
    if (enabled) enable_interrupt(SOURCE_WAKE_ON_MOTION);
    else disable_interrupt(SOURCE_WAKE_ON_MOTION);
    _lp_resume();
}

void ICM20948Full::set_interrupt_pin(bool active_low, bool open_drain, bool latch, bool any_read_clears) {
    _int_active_low = active_low;
    _wr1(0, REG_INT_PIN_CFG,
         static_cast<uint8_t>((active_low ? 0x80 : 0) | (open_drain ? 0x40 : 0) |
                              (latch ? 0x20 : 0) | (any_read_clears ? 0x10 : 0)));
}

void ICM20948Full::_int_enable(uint8_t source, bool on) {
    uint8_t reg, mask;
    switch (source) {
        case SOURCE_DATA_READY:     reg = REG_INT_ENABLE_1; mask = 0x01; break;
        case SOURCE_WAKE_ON_MOTION: reg = REG_INT_ENABLE;   mask = 0x08; break;
        case SOURCE_I2C_MASTER:     reg = REG_INT_ENABLE;   mask = 0x01; break;
        case SOURCE_PLL_READY:      reg = REG_INT_ENABLE;   mask = 0x04; break;
        case SOURCE_FIFO_OVERFLOW:  reg = REG_INT_ENABLE_2; mask = 0x1F; break;
        default: abort();
    }
    uint8_t v = _rd1(0, reg);
    _wr1(0, reg, on ? static_cast<uint8_t>(v | mask) : static_cast<uint8_t>(v & ~mask));
}

void ICM20948Full::enable_interrupt(uint8_t source) { _int_enable(source, true); }

void ICM20948Full::disable_interrupt(uint8_t source) { _int_enable(source, false); }

uint8_t ICM20948Full::poll_interrupt() {
    uint8_t s[3];
    _rd(0, REG_INT_STATUS, s, 3);
    uint8_t status = 0;
    if (s[1] & 0x01) status |= SOURCE_DATA_READY;
    if (s[0] & 0x08) status |= SOURCE_WAKE_ON_MOTION;
    if (s[0] & 0x01) status |= SOURCE_I2C_MASTER;
    if (s[0] & 0x04) status |= SOURCE_PLL_READY;
    if (s[2] & 0x1F) status |= SOURCE_FIFO_OVERFLOW;
    return status;
}

void ICM20948Full::on_interrupt(void (*callback)(uint8_t status)) {
    _int_callback = callback;
    _int_pin = _connection.intPin();
    _active_instance = this;
    if (_int_pin) {
        _int_pin->onEdge(&_edge_trampoline, _int_active_low ? InputPin::kFalling : InputPin::kRising);
    }
}

void ICM20948Full::off_interrupt() {
    if (_int_pin) _int_pin->offEdge(&_edge_trampoline);
    _int_pin = nullptr;
    _int_callback = nullptr;
    if (_active_instance == this) _active_instance = nullptr;
}

void ICM20948Full::_edge_trampoline() {
    if (_active_instance) _active_instance->_handle_edge();
}

void ICM20948Full::_handle_edge() {
    if (!_int_callback) return;
    uint8_t status = poll_interrupt();
    if (status) _int_callback(status);
}

void ICM20948Full::_average_raw(float accel[3], float gyro[3]) {
    const int samples = 5;
    float sa[3] = {0, 0, 0};
    float sg[3] = {0, 0, 0};
    for (int n = 0; n < samples; n++) {
        int16_t a[3], g[3];
        accel_raw(a[0], a[1], a[2]);
        gyro_raw(g[0], g[1], g[2]);
        for (int i = 0; i < 3; i++) { sa[i] += a[i]; sg[i] += g[i]; }
        DELAY_MS(11);
    }
    for (int i = 0; i < 3; i++) { accel[i] = sa[i] / samples; gyro[i] = sg[i] / samples; }
}

void ICM20948Full::self_test(float accel[3], float gyro[3]) {
    uint8_t saved_accel = _accel_fs, saved_gyro = _gyro_fs;
    configure_accel(0);
    configure_gyro(0);
    DELAY_MS(50);
    float a0[3], g0[3], a1[3], g1[3];
    _average_raw(a0, g0);
    _lp_suspend();
    _wr1(2, REG_GYRO_CONFIG_2, 0x38);
    _wr1(2, REG_ACCEL_CONFIG_2, 0x1C);
    DELAY_MS(50);
    _average_raw(a1, g1);
    _wr1(2, REG_GYRO_CONFIG_2, 0x00);
    _wr1(2, REG_ACCEL_CONFIG_2, 0x00);
    _lp_resume();
    configure_accel(saved_accel);
    configure_gyro(saved_gyro);
    for (int i = 0; i < 3; i++) { accel[i] = a1[i] - a0[i]; gyro[i] = g1[i] - g0[i]; }
}

float ICM20948Full::read_timebase_correction() {
    int8_t raw = static_cast<int8_t>(_rd1(1, REG_TIMEBASE_CORRECTION_PLL));
    return raw * 0.079f;
}

void ICM20948Full::reset() {
    _wr1(0, REG_PWR_MGMT_1, 0x80);
    DELAY_MS(100);
    _bank = 0;
    _lp_en = false;
    _lp_suspended = false;
    _mag_enabled = false;
}
