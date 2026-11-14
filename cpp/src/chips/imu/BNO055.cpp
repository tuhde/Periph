#include "BNO055.h"

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

static inline int16_t le16(const uint8_t* p) {
    return static_cast<int16_t>((static_cast<uint16_t>(p[1]) << 8) | p[0]);
}

// ---------------------------------------------------------------- Minimal

BNO055Minimal::BNO055Minimal(RegisterConnection& connection) : _connection(connection) {
    _valid = _initialize();
}

bool BNO055Minimal::_initialize() {
    bool found = false;
    for (int attempt = 0; attempt < 66; attempt++) {     // the chip does not ACK for up to 650 ms
        uint8_t id = 0;
        _connection.read(REG_CHIP_ID, &id, 1);
        if (_connection.ok() && id == CHIP_ID_VALUE) { found = true; break; }
        DELAY_MS(10);
    }
    if (!found) return false;
    _w(REG_PWR_MODE, 0x00);
    _w(REG_PAGE_ID, 0x00);
    _w(REG_UNIT_SEL, UNIT_SEL_SI);
    _w(REG_OPR_MODE, MODE_NDOF_V);
    _mode = MODE_NDOF_V;
    DELAY_MS(7);
    return true;
}

void BNO055Minimal::_w(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
}

uint8_t BNO055Minimal::_r(uint8_t reg) {
    uint8_t v = 0;
    _connection.read(reg, &v, 1);
    return v;
}

void BNO055Minimal::_vector(uint8_t reg, float scale, float& x, float& y, float& z) {
    uint8_t b[6] = {0};
    _connection.read(reg, b, 6);
    x = le16(b) / scale;
    y = le16(b + 2) / scale;
    z = le16(b + 4) / scale;
}

void BNO055Minimal::euler(float& heading, float& roll, float& pitch) {
    _vector(REG_EUL_DATA, 900.0f, heading, roll, pitch);
}

// ------------------------------------------------------------------- Full

BNO055Full* BNO055Full::_active_instance = nullptr;

BNO055Full::BNO055Full(RegisterConnection& connection) : BNO055Minimal(connection) {}

uint8_t BNO055Full::_enter_config() {
    uint8_t prev = _mode;
    _w(REG_OPR_MODE, MODE_CONFIG_V);
    DELAY_MS(19);
    return prev;
}

void BNO055Full::_leave_config(uint8_t mode) {
    if (mode != MODE_CONFIG_V) {
        _w(REG_OPR_MODE, mode);
        DELAY_MS(7);
    }
}

void BNO055Full::set_mode(uint8_t mode) {
    if (mode > 12) return;
    _w(REG_OPR_MODE, MODE_CONFIG_V);
    DELAY_MS(19);
    if (mode != MODE_CONFIG_V) {
        _w(REG_OPR_MODE, mode);
        DELAY_MS(7);
    }
    _mode = mode;
}

uint8_t BNO055Full::get_mode() { return _r(REG_OPR_MODE) & 0x0F; }

void BNO055Full::quaternion(float& w, float& x, float& y, float& z) {
    uint8_t b[8] = {0};
    _connection.read(REG_QUA_DATA, b, 8);
    w = le16(b) / 16384.0f;
    x = le16(b + 2) / 16384.0f;
    y = le16(b + 4) / 16384.0f;
    z = le16(b + 6) / 16384.0f;
}

void BNO055Full::linear_acceleration(float& x, float& y, float& z) { _vector(REG_LIA_DATA, 100.0f, x, y, z); }
void BNO055Full::gravity(float& x, float& y, float& z) { _vector(REG_GRV_DATA, 100.0f, x, y, z); }
void BNO055Full::acceleration(float& x, float& y, float& z) { _vector(REG_ACC_DATA, 100.0f, x, y, z); }
void BNO055Full::magnetic_field(float& x, float& y, float& z) { _vector(REG_MAG_DATA, 16.0f, x, y, z); }
void BNO055Full::angular_rate(float& x, float& y, float& z) { _vector(REG_GYR_DATA, 900.0f, x, y, z); }

int8_t BNO055Full::temperature() { return static_cast<int8_t>(_r(REG_TEMP)); }

void BNO055Full::calibration_status(uint8_t& sys, uint8_t& gyr, uint8_t& acc, uint8_t& mag) {
    uint8_t v = _r(REG_CALIB_STAT);
    sys = (v >> 6) & 3;
    gyr = (v >> 4) & 3;
    acc = (v >> 2) & 3;
    mag = v & 3;
}

bool BNO055Full::is_calibrated() { return _r(REG_CALIB_STAT) == 0xFF; }

void BNO055Full::get_calibration(uint8_t out[22]) {
    uint8_t prev = _enter_config();
    _connection.read(REG_ACC_OFFSET, out, 22);
    _leave_config(prev);
}

void BNO055Full::set_calibration(const uint8_t data[22]) {
    uint8_t prev = _enter_config();
    for (uint8_t i = 0; i < 22; i++) _w(static_cast<uint8_t>(REG_ACC_OFFSET + i), data[i]);
    _leave_config(prev);
}

void BNO055Full::set_axis_remap(uint8_t config, uint8_t sign) {
    uint8_t prev = _enter_config();
    _w(REG_AXIS_MAP_CONFIG, config);
    _w(REG_AXIS_MAP_SIGN, sign);
    _leave_config(prev);
}

void BNO055Full::set_power_mode(uint8_t mode) {
    if (mode > POWER_SUSPEND) return;
    uint8_t prev = _enter_config();
    _w(REG_PWR_MODE, mode);
    _leave_config(prev);
}

void BNO055Full::set_temperature_source(uint8_t source) {
    if (source > TEMP_SOURCE_GYROSCOPE) return;
    uint8_t prev = _enter_config();
    _w(REG_TEMP_SOURCE, source);
    _leave_config(prev);
}

void BNO055Full::use_external_crystal(bool on) {
    uint8_t prev = _enter_config();
    _clk_sel = on ? 0x80 : 0x00;
    _w(REG_SYS_TRIGGER, _clk_sel);
    _leave_config(prev);
}

void BNO055Full::self_test(bool& acc, bool& mag, bool& gyr, bool& mcu) {
    uint8_t prev = _enter_config();
    _w(REG_SYS_TRIGGER, static_cast<uint8_t>(_clk_sel | 0x01));
    DELAY_MS(500);
    uint8_t v = _r(REG_ST_RESULT);
    _leave_config(prev);
    acc = v & 0x01;
    mag = v & 0x02;
    gyr = v & 0x04;
    mcu = v & 0x08;
}

void BNO055Full::system_status(uint8_t& status, uint8_t& error) {
    uint8_t b[2] = {0};
    _connection.read(REG_SYS_STATUS, b, 2);
    status = b[0];
    error = b[1];
}

void BNO055Full::reset() {
    _w(REG_SYS_TRIGGER, static_cast<uint8_t>(_clk_sel | 0x20));
    DELAY_MS(650);
    _clk_sel = 0x00;
    _valid = _initialize();
}

void BNO055Full::revisions(uint8_t& chip_id, uint8_t& acc_id, uint8_t& mag_id, uint8_t& gyr_id,
                           uint16_t& sw_revision, uint8_t& bootloader) {
    uint8_t b[7] = {0};
    _connection.read(REG_CHIP_ID, b, 7);
    chip_id = b[0];
    acc_id = b[1];
    mag_id = b[2];
    gyr_id = b[3];
    sw_revision = static_cast<uint16_t>(b[4] | (b[5] << 8));
    bootloader = b[6];
}

void BNO055Full::unique_id(uint8_t out[16]) {
    _w(REG_PAGE_ID, 0x01);
    _connection.read(REG_UNIQUE_ID, out, 16);
    _w(REG_PAGE_ID, 0x00);
}

void BNO055Full::_update_int_reg(uint8_t source, bool enable) {
    _w(REG_PAGE_ID, 0x01);
    const uint8_t regs[2] = {REG_INT_EN, REG_INT_MSK};
    for (uint8_t i = 0; i < 2; i++) {
        uint8_t v = _r(regs[i]);
        v = enable ? static_cast<uint8_t>(v | source) : static_cast<uint8_t>(v & ~source);
        _w(regs[i], v);
    }
    _w(REG_PAGE_ID, 0x00);
}

void BNO055Full::enable_interrupt(uint8_t source) { _update_int_reg(source, true); }
void BNO055Full::disable_interrupt(uint8_t source) { _update_int_reg(source, false); }

uint8_t BNO055Full::poll_interrupt() { return _r(REG_INT_STA); }

void BNO055Full::on_interrupt(void (*callback)(uint8_t status)) {
    _int_callback = callback;
    _int_pin = _connection.intPin();
    _active_instance = this;
    if (_int_pin) _int_pin->onEdge(&_edge_trampoline, InputPin::kRising);
}

void BNO055Full::off_interrupt() {
    if (_int_pin) _int_pin->offEdge(&_edge_trampoline);
    _int_pin = nullptr;
    _int_callback = nullptr;
    if (_active_instance == this) _active_instance = nullptr;
}

void BNO055Full::_edge_trampoline() {
    if (_active_instance) _active_instance->_handle_edge();
}

void BNO055Full::_handle_edge() {
    if (!_int_callback) return;
    uint8_t status = poll_interrupt();
    if (status) _int_callback(status);
}
