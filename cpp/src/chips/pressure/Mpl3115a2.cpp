#include "Mpl3115a2.h"
#include <math.h>

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

// Minimum time between samples (ms) for OS = 0..7
static const unsigned OSR_TIME_MS[8] = {6, 10, 18, 34, 66, 130, 258, 512};

// --- Minimal ---

MPL3115A2Minimal::MPL3115A2Minimal(Connection& connection)
    : _connection(connection) {}

bool MPL3115A2Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { reg, value };
    _connection.write(buf, 2);
    return _connection.ok();
}

bool MPL3115A2Minimal::_write_regs(uint8_t reg, const uint8_t* data, size_t len) {
    uint8_t buf[4];
    if (len > 3) return false;
    buf[0] = reg;
    for (size_t i = 0; i < len; i++) buf[1 + i] = data[i];
    _connection.write(buf, len + 1);
    return _connection.ok();
}

bool MPL3115A2Minimal::_read_reg(uint8_t reg, uint8_t* buf, size_t len) {
    _connection.write_read(&reg, 1, buf, len);
    return _connection.ok();
}

uint8_t MPL3115A2Minimal::_ctrl1() const {
    return (uint8_t)((_alt ? CTRL1_ALT : 0) | (_os << 3));
}

unsigned MPL3115A2Minimal::_timeout_ms() const {
    return OSR_TIME_MS[_os] * 2 + 50;
}

bool MPL3115A2Minimal::_soft_reset() {
    _write_reg(REG_CTRL_REG1, CTRL1_RST);  // the chip may not ACK while rebooting
    for (int i = 0; i < 100; i++) {
        periph_delay_ms(2);
        uint8_t c1;
        if (_read_reg(REG_CTRL_REG1, &c1, 1) && !(c1 & CTRL1_RST)) return true;
    }
    return false;
}

bool MPL3115A2Minimal::_apply_config() {
    return _write_reg(REG_PT_DATA_CFG, 0x07) && _write_reg(REG_CTRL_REG1, _ctrl1());
}

bool MPL3115A2Minimal::init() {
    uint8_t id;
    if (!_read_reg(REG_WHO_AM_I, &id, 1) || id != CHIP_ID) return false;
    if (!_soft_reset()) return false;
    _os = 7;
    _alt = false;
    return _apply_config();
}

bool MPL3115A2Minimal::_wait_ptdr() {
    unsigned waited = 0;
    unsigned limit = _timeout_ms();
    for (;;) {
        periph_delay_ms(3);
        waited += 3;
        uint8_t s;
        if (!_read_reg(REG_DR_STATUS, &s, 1)) return false;
        if (s & DR_PTDR) return true;
        if (waited > limit) return false;
    }
}

bool MPL3115A2Minimal::_one_shot(uint8_t* raw5) {
    if (!_write_reg(REG_CTRL_REG1, _ctrl1())) return false;                 // OST must be 0 before it is set
    if (!_write_reg(REG_CTRL_REG1, (uint8_t)(_ctrl1() | CTRL1_OST))) return false;
    if (!_wait_ptdr()) return false;
    return _read_reg(REG_OUT_P_MSB, raw5, 5);
}

float MPL3115A2Minimal::_pressure_from(const uint8_t* b) {
    uint32_t raw = ((uint32_t)b[0] << 12) | ((uint32_t)b[1] << 4) | (b[2] >> 4);
    return (float)raw / 4.0f;
}

float MPL3115A2Minimal::_altitude_from(const uint8_t* b) {
    int16_t whole = (int16_t)(uint16_t)((b[0] << 8) | b[1]);
    return (float)whole + (float)(b[2] >> 4) / 16.0f;
}

float MPL3115A2Minimal::_temperature_from(const uint8_t* b) {
    return (float)(int8_t)b[0] + (float)(b[1] >> 4) / 16.0f;
}

bool MPL3115A2Minimal::read(float& pressure_pa, float& temperature_c) {
    uint8_t raw[5];
    if (!_one_shot(raw)) return false;
    pressure_pa   = _pressure_from(raw);
    temperature_c = _temperature_from(raw + 3);
    return true;
}

bool MPL3115A2Minimal::read_pressure(float& pressure_pa) {
    float t;
    return read(pressure_pa, t);
}

bool MPL3115A2Minimal::read_temperature(float& temperature_c) {
    float p;
    return read(p, temperature_c);
}

// --- Full ---

MPL3115A2Full* MPL3115A2Full::_activeInstance = nullptr;

MPL3115A2Full::MPL3115A2Full(Connection& connection)
    : MPL3115A2Minimal(connection) {}

bool MPL3115A2Full::_standby(bool& was_active) {
    uint8_t c1;
    if (!_read_reg(REG_CTRL_REG1, &c1, 1)) return false;
    was_active = (c1 & CTRL1_SBYB) != 0;
    if (was_active) return _write_reg(REG_CTRL_REG1, (uint8_t)(c1 & ~CTRL1_SBYB));
    return true;
}

bool MPL3115A2Full::_restore(bool was_active) {
    return was_active ? _write_reg(REG_CTRL_REG1, (uint8_t)(_ctrl1() | CTRL1_SBYB)) : true;
}

bool MPL3115A2Full::set_mode(bool altimeter) {
    bool was;
    if (!_standby(was)) return false;
    _alt = altimeter;
    if (!_write_reg(REG_CTRL_REG1, _ctrl1())) return false;
    return _restore(was);
}

bool MPL3115A2Full::read_altitude(float& altitude_m) {
    if (!_alt && !set_mode(true)) return false;
    uint8_t raw[5];
    if (!_one_shot(raw)) return false;
    altitude_m = _altitude_from(raw);
    return true;
}

bool MPL3115A2Full::read(float& pressure_pa, float& temperature_c) {
    if (_alt && !set_mode(false)) return false;
    return MPL3115A2Minimal::read(pressure_pa, temperature_c);
}

bool MPL3115A2Full::read_pressure(float& pressure_pa) {
    float t;
    return read(pressure_pa, t);
}

bool MPL3115A2Full::read_temperature(float& temperature_c) {
    float p;
    return read(p, temperature_c);
}

bool MPL3115A2Full::set_oversample(uint8_t ratio) {
    uint8_t os = 0;
    switch (ratio) {
        case 1: os = 0; break;   case 2: os = 1; break;
        case 4: os = 2; break;   case 8: os = 3; break;
        case 16: os = 4; break;  case 32: os = 5; break;
        case 64: os = 6; break;  case 128: os = 7; break;
        default: return false;
    }
    bool was;
    if (!_standby(was)) return false;
    _os = os;
    if (!_write_reg(REG_CTRL_REG1, _ctrl1())) return false;
    return _restore(was);
}

bool MPL3115A2Full::start_continuous(uint8_t step_exp) {
    if (step_exp > 15) return false;
    bool was;
    if (!_standby(was)) return false;
    uint8_t c2;
    if (!_read_reg(REG_CTRL_REG2, &c2, 1)) return false;
    if (!_write_reg(REG_CTRL_REG2, (uint8_t)((c2 & 0xF0) | step_exp))) return false;
    return _write_reg(REG_CTRL_REG1, (uint8_t)(_ctrl1() | CTRL1_SBYB));
}

bool MPL3115A2Full::stop_continuous() {
    return _write_reg(REG_CTRL_REG1, _ctrl1());
}

bool MPL3115A2Full::data_ready() {
    uint8_t s;
    return _read_reg(REG_DR_STATUS, &s, 1) && (s & DR_PTDR);
}

bool MPL3115A2Full::read_continuous(float& value, float& temperature_c) {
    if (!_wait_ptdr()) return false;
    uint8_t raw[5];
    if (!_read_reg(REG_OUT_P_MSB, raw, 5)) return false;
    value         = _alt ? _altitude_from(raw) : _pressure_from(raw);
    temperature_c = _temperature_from(raw + 3);
    return true;
}

bool MPL3115A2Full::read_delta(float& delta_value, float& delta_temperature_c) {
    uint8_t raw[5];
    if (!_read_reg(REG_OUT_P_DELTA, raw, 5)) return false;
    if (_alt) {
        delta_value = _altitude_from(raw);
    } else {
        delta_value = _pressure_from(raw);
        if (delta_value >= 262144.0f) delta_value -= 524288.0f;
    }
    delta_temperature_c = _temperature_from(raw + 3);
    return true;
}

bool MPL3115A2Full::set_sea_level_pressure(float pa) {
    uint16_t raw = (uint16_t)lroundf(pa / 2.0f);
    uint8_t d[2] = { (uint8_t)(raw >> 8), (uint8_t)raw };
    return _write_regs(REG_BAR_IN_MSB, d, 2);
}

bool MPL3115A2Full::set_offsets(float pressure_pa, float temperature_c, int8_t altitude_m) {
    long op = lroundf(pressure_pa / 4.0f);
    long ot = lroundf(temperature_c / 0.0625f);
    if (op < -128 || op > 127 || ot < -128 || ot > 127) return false;
    return _write_reg(REG_OFF_P, (uint8_t)op) && _write_reg(REG_OFF_T, (uint8_t)ot)
        && _write_reg(REG_OFF_H, (uint8_t)altitude_m);
}

bool MPL3115A2Full::set_target(float value, float window, int8_t temperature_c, uint8_t temperature_window) {
    uint16_t tgt = _alt ? (uint16_t)(int16_t)lroundf(value) : (uint16_t)lroundf(value / 2.0f);
    uint16_t wnd = _alt ? (uint16_t)lroundf(window) : (uint16_t)lroundf(window / 2.0f);
    bool was;
    if (!_standby(was)) return false;
    uint8_t t[2] = { (uint8_t)(tgt >> 8), (uint8_t)tgt };
    uint8_t w[2] = { (uint8_t)(wnd >> 8), (uint8_t)wnd };
    if (!_write_regs(REG_P_TGT_MSB, t, 2) || !_write_reg(REG_T_TGT, (uint8_t)temperature_c)
        || !_write_regs(REG_P_WND_MSB, w, 2) || !_write_reg(REG_T_WND, temperature_window)) return false;
    return _restore(was);
}

bool MPL3115A2Full::load_current_as_target() {
    uint8_t c2;
    if (!_read_reg(REG_CTRL_REG2, &c2, 1)) return false;
    return _write_reg(REG_CTRL_REG2, (uint8_t)((c2 | 0x10) & ~0x20))
        && _write_reg(REG_CTRL_REG2, (uint8_t)(c2 | 0x30));
}

bool MPL3115A2Full::read_min_max(float& p_min, float& p_max, float& t_min, float& t_max) {
    uint8_t pm[3], px[3], tm[2], tx[2];
    if (!_read_reg(REG_P_MIN, pm, 3) || !_read_reg(REG_P_MAX, px, 3)
        || !_read_reg(REG_T_MIN, tm, 2) || !_read_reg(REG_T_MAX, tx, 2)) return false;
    p_min = _alt ? _altitude_from(pm) : _pressure_from(pm);
    p_max = _alt ? _altitude_from(px) : _pressure_from(px);
    t_min = _temperature_from(tm);
    t_max = _temperature_from(tx);
    return true;
}

bool MPL3115A2Full::clear_min_max() {
    const uint8_t z[3] = {0, 0, 0};
    return _write_regs(REG_P_MIN, z, 3) && _write_regs(REG_T_MIN, z, 2)
        && _write_regs(REG_P_MAX, z, 3) && _write_regs(REG_T_MAX, z, 2);
}

bool MPL3115A2Full::fifo_setup(uint8_t mode, uint8_t watermark) {
    if (mode > 2) return false;
    if (_fifo_mode != 0 && mode != 0 && mode != _fifo_mode) return false;
    bool was;
    if (!_standby(was)) return false;
    if (!_write_reg(REG_F_SETUP, (uint8_t)((mode << 6) | (watermark & 0x3F)))) return false;
    _fifo_mode = mode;
    return _restore(was);
}

bool MPL3115A2Full::fifo_status(bool& overflow, bool& watermark, uint8_t& count) {
    uint8_t s;
    if (!_read_reg(REG_F_STATUS, &s, 1)) return false;
    overflow  = (s & 0x80) != 0;
    watermark = (s & 0x40) != 0;
    count     = s & 0x3F;
    return true;
}

uint8_t MPL3115A2Full::fifo_read(float* values, float* temperatures, uint8_t max_samples) {
    bool ov, wm;
    uint8_t count;
    if (!fifo_status(ov, wm, count)) return 0;
    uint8_t n = 0;
    while (n < count && n < max_samples) {
        uint8_t raw[5];
        if (!_read_reg(REG_F_DATA, raw, 5)) break;
        values[n]       = _alt ? _altitude_from(raw) : _pressure_from(raw);
        temperatures[n] = _temperature_from(raw + 3);
        n++;
    }
    return n;
}

bool MPL3115A2Full::reset() {
    if (!_soft_reset()) return false;
    _os = 7;
    _alt = false;
    _fifo_mode = 0;
    return _apply_config();
}

uint8_t MPL3115A2Full::who_am_i() {
    uint8_t id = 0;
    _read_reg(REG_WHO_AM_I, &id, 1);
    return id;
}

bool MPL3115A2Full::is_active() {
    uint8_t s;
    return _read_reg(REG_SYSMOD, &s, 1) && (s & 0x01);
}

bool MPL3115A2Full::set_interrupt_pins(bool polarity1, bool open_drain1, bool polarity2, bool open_drain2) {
    bool was;
    if (!_standby(was)) return false;
    uint8_t v = (uint8_t)((polarity1 ? 0x20 : 0) | (open_drain1 ? 0x10 : 0)
                        | (polarity2 ? 0x02 : 0) | (open_drain2 ? 0x01 : 0));
    if (!_write_reg(REG_CTRL_REG3, v)) return false;
    return _restore(was);
}

bool MPL3115A2Full::enable_interrupt(uint8_t source, uint8_t pin) {
    if (pin != 1 && pin != 2) return false;
    bool was;
    if (!_standby(was)) return false;
    uint8_t c4, c5;
    if (!_read_reg(REG_CTRL_REG4, &c4, 1) || !_read_reg(REG_CTRL_REG5, &c5, 1)) return false;
    if (!_write_reg(REG_CTRL_REG4, (uint8_t)(c4 | source))) return false;
    if (!_write_reg(REG_CTRL_REG5, pin == 1 ? (uint8_t)(c5 | source) : (uint8_t)(c5 & ~source))) return false;
    return _restore(was);
}

bool MPL3115A2Full::disable_interrupt(uint8_t source) {
    bool was;
    if (!_standby(was)) return false;
    uint8_t c4;
    if (!_read_reg(REG_CTRL_REG4, &c4, 1)) return false;
    if (!_write_reg(REG_CTRL_REG4, (uint8_t)(c4 & ~source))) return false;
    return _restore(was);
}

uint8_t MPL3115A2Full::poll_interrupt() {
    uint8_t s = 0;
    _read_reg(REG_INT_SOURCE, &s, 1);
    return s;
}

void MPL3115A2Full::on_interrupt(void (*callback)(uint8_t), uint8_t pin, InputPin* intPin) {
    InputPin* p = intPin ? intPin : _connection.intPin();
    if (!p || (pin != 1 && pin != 2)) return;  // no INT line wired: the caller polls poll_interrupt()
    _callback = callback;
    _int_pin_no = pin;
    _intPinUsed = p;
    _activeInstance = this;
    p->onEdge(&MPL3115A2Full::_edgeTrampoline, InputPin::kFalling);
}

void MPL3115A2Full::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&MPL3115A2Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

void MPL3115A2Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void MPL3115A2Full::_handleEdge() {
    uint8_t src = poll_interrupt();
    uint8_t c5 = 0;
    _read_reg(REG_CTRL_REG5, &c5, 1);
    src &= (_int_pin_no == 1) ? c5 : (uint8_t)~c5;
    if (src && _callback) _callback(src);
}
