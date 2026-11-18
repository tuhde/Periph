#include "Is31fl3731.h"
#include <string.h>

namespace {
constexpr uint8_t REG_COMMAND = 0xFD;
constexpr uint8_t PAGE_FUNCTION = 0x0B;
constexpr uint8_t REG_CTRL = 0x00;
constexpr uint8_t REG_BLINK = 0x12;
constexpr uint8_t REG_PWM = 0x24;
constexpr uint8_t FN_CONFIG = 0x00;
constexpr uint8_t FN_AUTOPLAY1 = 0x02;
constexpr uint8_t FN_DISPLAY = 0x05;
constexpr uint8_t FN_AUDIO_SYNC = 0x06;
constexpr uint8_t FN_FRAME_STATE = 0x07;
constexpr uint8_t FN_BREATH1 = 0x08;
constexpr uint8_t FN_SHUTDOWN = 0x0A;
constexpr uint8_t FN_AGC = 0x0B;
constexpr uint8_t FN_AUDIO_RATE = 0x0C;
constexpr size_t  CHUNK = 16;   // fits Arduino Wire's 32-byte buffer (register + 16 data bytes)
}

// Out-of-class definitions for ODR-used static constexpr members (AVR builds as C++11).
constexpr uint8_t Is31fl3731Minimal::ROWS;
constexpr uint8_t Is31fl3731Minimal::COLS;
constexpr uint8_t Is31fl3731Full::GAMMA_32[32];
constexpr uint8_t Is31fl3731Full::GAMMA_64[64];

Is31fl3731Minimal::Is31fl3731Minimal(RegisterConnection& connection)
    : _connection(connection), _page(-1), _frame(0), _ic(0), _be(0), _bpt(0) {
    memset(_pwm, 0, sizeof(_pwm));
    memset(_ctrl, 0xFF, sizeof(_ctrl));
    memset(_blink, 0, sizeof(_blink));
    _func_write(FN_SHUTDOWN, (uint8_t)0x00);
    const uint8_t mode[2] = {0x00, 0x00};
    _func_write(FN_CONFIG, mode, 2);
    _func_write(FN_DISPLAY, (uint8_t)0x00);
    _init_frame(0);
    _func_write(FN_SHUTDOWN, (uint8_t)0x01);
}

void Is31fl3731Minimal::_select(uint8_t page) {
    if (_page != page) {
        _connection.write(REG_COMMAND, &page, 1);
        _page = page;
    }
}

void Is31fl3731Minimal::_write_block(uint8_t reg, const uint8_t* data, size_t len) {
    for (size_t off = 0; off < len; off += CHUNK) {
        size_t n = len - off < CHUNK ? len - off : CHUNK;
        _connection.write((uint8_t)(reg + off), data + off, n);
    }
}

void Is31fl3731Minimal::_func_write(uint8_t reg, const uint8_t* data, size_t len) {
    _select(PAGE_FUNCTION);
    _connection.write(reg, data, len);
}

void Is31fl3731Minimal::_func_write(uint8_t reg, uint8_t value) {
    _func_write(reg, &value, 1);
}

uint8_t Is31fl3731Minimal::_func_read(uint8_t reg) {
    _select(PAGE_FUNCTION);
    uint8_t value = 0;
    _connection.read(reg, &value, 1);
    return value;
}

void Is31fl3731Minimal::_init_frame(uint8_t frame) {
    memset(_ctrl[frame], 0xFF, 18);
    memset(_blink[frame], 0, 18);
    memset(_pwm[frame], 0, 144);
    _select(frame);
    _write_block(REG_CTRL, _ctrl[frame], 18);
    _write_block(REG_BLINK, _blink[frame], 18);
    _write_block(REG_PWM, _pwm[frame], 144);
}

bool Is31fl3731Minimal::set_pixel(uint8_t row, uint8_t col, uint8_t brightness) {
    if (row > 8 || col > 15) return false;
    _pwm[_frame][16 * row + col] = brightness;
    _select(_frame);
    _connection.write((uint8_t)(REG_PWM + 16 * row + col), &brightness, 1);
    return true;
}

void Is31fl3731Minimal::clear() { fill(0); }

void Is31fl3731Minimal::fill(uint8_t brightness) {
    memset(_pwm[_frame], brightness, 144);
    _select(_frame);
    _write_block(REG_PWM, _pwm[_frame], 144);
}

// Is31fl3731Full

Is31fl3731Full* Is31fl3731Full::_active_instance = nullptr;

Is31fl3731Full::Is31fl3731Full(RegisterConnection& connection)
    : Is31fl3731Minimal(connection), _callback(nullptr), _int_pin_used(nullptr) {}

uint8_t Is31fl3731Full::get_pixel(uint8_t row, uint8_t col) const {
    if (row > 8 || col > 15) return 0;
    return _pwm[_frame][16 * row + col];
}

bool Is31fl3731Full::set_pixels(const uint8_t* data, size_t len) {
    if (len != 144) return false;
    memcpy(_pwm[_frame], data, 144);
    _select(_frame);
    _write_block(REG_PWM, _pwm[_frame], 144);
    return true;
}

bool Is31fl3731Full::_set_bit(uint8_t (*cache)[18], uint8_t base, uint8_t row, uint8_t col, bool on) {
    if (row > 8 || col > 15) return false;
    uint8_t idx = 2 * row + (col >> 3);
    uint8_t mask = 1u << (col & 7);
    if (on) cache[_frame][idx] |= mask;
    else    cache[_frame][idx] &= (uint8_t)~mask;
    _select(_frame);
    _connection.write((uint8_t)(base + idx), &cache[_frame][idx], 1);
    return true;
}

bool Is31fl3731Full::set_led_enabled(uint8_t row, uint8_t col, bool on) { return _set_bit(_ctrl, REG_CTRL, row, col, on); }
bool Is31fl3731Full::set_blink(uint8_t row, uint8_t col, bool on) { return _set_bit(_blink, REG_BLINK, row, col, on); }

void Is31fl3731Full::set_blink_all(bool on) {
    memset(_blink[_frame], on ? 0xFF : 0x00, 18);
    _select(_frame);
    _write_block(REG_BLINK, _blink[_frame], 18);
}

void Is31fl3731Full::_write_display() {
    _func_write(FN_DISPLAY, (uint8_t)((_ic << 5) | (_be << 3) | _bpt));
}

bool Is31fl3731Full::enable_blink(bool enabled, uint8_t period) {
    if (period > 7) return false;
    _be = enabled ? 1 : 0;
    _bpt = period;
    _write_display();
    return true;
}

bool Is31fl3731Full::select_frame(uint8_t frame) {
    if (frame > 7) return false;
    _frame = frame;
    _select(frame);
    return true;
}

bool Is31fl3731Full::show_frame(uint8_t frame) {
    if (frame > 7) return false;
    const uint8_t data[2] = {0x00, frame};
    _func_write(FN_CONFIG, data, 2);
    return true;
}

bool Is31fl3731Full::clear_frame(uint8_t frame) {
    if (frame > 7) return false;
    _init_frame(frame);
    return true;
}

void Is31fl3731Full::clear_all_frames() {
    for (uint8_t f = 0; f < 8; f++) _init_frame(f);
}

bool Is31fl3731Full::copy_frame(uint8_t src, uint8_t dst) {
    if (src > 7 || dst > 7) return false;
    if (src != dst) memcpy(_pwm[dst], _pwm[src], 144);
    _select(dst);
    _write_block(REG_PWM, _pwm[dst], 144);
    return true;
}

void Is31fl3731Full::set_picture_mode() { _func_write(FN_CONFIG, (uint8_t)0x00); }

bool Is31fl3731Full::start_auto_play(uint8_t frames, uint8_t loops, uint16_t delay_ms, uint8_t start_frame) {
    uint16_t a = (uint16_t)((delay_ms + 5) / 11);
    if (frames > 7 || loops > 7 || start_frame > 7 || a < 1 || a > 64) return false;
    const uint8_t data[2] = {(uint8_t)((loops << 4) | frames), (uint8_t)(a & 0x3F)};
    _func_write(FN_AUTOPLAY1, data, 2);
    _func_write(FN_CONFIG, (uint8_t)(0x08 | start_frame));
    return true;
}

void Is31fl3731Full::stop_auto_play() {
    const uint8_t data[2] = {0x00, (uint8_t)(_func_read(FN_FRAME_STATE) & 0x07)};
    _func_write(FN_CONFIG, data, 2);
}

void Is31fl3731Full::set_audio_mode(bool enabled) { _func_write(FN_CONFIG, (uint8_t)(enabled ? 0x10 : 0x00)); }
void Is31fl3731Full::set_audio_sync(bool enabled) { _func_write(FN_AUDIO_SYNC, (uint8_t)(enabled ? 1 : 0)); }

bool Is31fl3731Full::set_audio_gain(uint8_t gain_db, bool agc, bool fast) {
    if (gain_db > 21 || gain_db % 3) return false;
    _func_write(FN_AGC, (uint8_t)(((fast ? 1 : 0) << 4) | ((agc ? 1 : 0) << 3) | (gain_db / 3)));
    return true;
}

void Is31fl3731Full::set_audio_rate(uint8_t a) { _func_write(FN_AUDIO_RATE, a); }

bool Is31fl3731Full::set_breath(bool enabled, uint8_t fade_in, uint8_t fade_out, uint8_t extinguish) {
    if (fade_in > 7 || fade_out > 7 || extinguish > 7) return false;
    const uint8_t data[2] = {(uint8_t)((fade_out << 4) | fade_in), (uint8_t)(((enabled ? 1 : 0) << 4) | extinguish)};
    _func_write(FN_BREATH1, data, 2);
    return true;
}

void Is31fl3731Full::set_intensity_control(bool use_frame1) {
    _ic = use_frame1 ? 1 : 0;
    _write_display();
}

void Is31fl3731Full::shutdown(bool on) { _func_write(FN_SHUTDOWN, (uint8_t)(on ? 0 : 1)); }

uint8_t Is31fl3731Full::frame_state() { return _func_read(FN_FRAME_STATE); }
uint8_t Is31fl3731Full::current_frame() { return frame_state() & 0x07; }
bool Is31fl3731Full::movie_finished() { return (frame_state() & 0x10) != 0; }

void Is31fl3731Full::_restore(int16_t prev) {
    if (prev >= 0 && prev != _page) _select((uint8_t)prev);
}

uint8_t Is31fl3731Full::read_function_register(uint8_t reg) {
    if (reg > 0x0C) return 0;
    int16_t prev = _page;
    uint8_t value = _func_read(reg);
    _restore(prev);
    return value;
}

bool Is31fl3731Full::write_function_register(uint8_t reg, uint8_t value) {
    if (reg > 0x0C) return false;
    int16_t prev = _page;
    _func_write(reg, value);
    _restore(prev);
    return true;
}

uint8_t Is31fl3731Full::poll_interrupt() {
    int16_t prev = _page;
    uint8_t value = _func_read(FN_FRAME_STATE);
    _restore(prev);
    return value;
}

void Is31fl3731Full::_edge_trampoline() {
    if (_active_instance && _active_instance->_callback) _active_instance->_callback(_active_instance->poll_interrupt());
}

void Is31fl3731Full::service() {
    uint8_t status = poll_interrupt();
    if ((status & 0x10) && _callback) _callback(status);
}

void Is31fl3731Full::on_interrupt(void (*callback)(uint8_t), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _int_pin_used = pin;
    if (!pin) return;
    _active_instance = this;
    pin->onEdge(&Is31fl3731Full::_edge_trampoline, InputPin::kFalling);
}

void Is31fl3731Full::off_interrupt() {
    if (_int_pin_used) {
        _int_pin_used->offEdge(&Is31fl3731Full::_edge_trampoline);
        _int_pin_used = nullptr;
    }
    _callback = nullptr;
}
