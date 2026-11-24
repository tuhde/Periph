#include "SAA1064.h"
#include <string.h>

// Segments a..g (bits 0..6 of the default mapping) lit for digits 0-9.
static const uint8_t SEGMENTS[10] = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

SAA1064Minimal::SAA1064Minimal(Connection& connection)
    : _connection(connection), _control(0x47) {
    memset(_patterns, 0, sizeof(_patterns));
    for (uint8_t i = 0; i < 8; i++) _map[i] = i;
    _rebuild_glyphs();
    const uint8_t buf[6] = {SUB_CONTROL, _control, 0, 0, 0, 0};
    _connection.write(buf, sizeof(buf));
}

void SAA1064Minimal::_rebuild_glyphs() {
    for (uint8_t v = 0; v < 10; v++) {
        uint8_t p = 0;
        for (uint8_t s = 0; s < 7; s++) {
            if (SEGMENTS[v] & (1u << s)) p |= (uint8_t)(1u << _map[s]);
        }
        _glyphs[v] = p;
    }
}

bool SAA1064Minimal::_pattern(int8_t value, uint8_t& out) const {
    if (value == BLANK) { out = 0; return true; }
    if (value < 0 || value > 9) return false;
    out = _glyphs[value];
    return true;
}

void SAA1064Minimal::_write_digits(uint8_t first) {
    uint8_t buf[5];
    uint8_t n = DIGITS - first + 1;
    buf[0] = first;
    memcpy(&buf[1], &_patterns[first - 1], n);
    _connection.write(buf, (size_t)n + 1);
}

bool SAA1064Minimal::show_digits(const int8_t digits[4]) {
    uint8_t p[4];
    for (uint8_t i = 0; i < 4; i++) {
        if (!_pattern(digits[i], p[i])) return false;
    }
    memcpy(_patterns, p, 4);
    _write_digits(1);
    return true;
}

bool SAA1064Minimal::show_number(uint16_t value) {
    if (value > 9999) return false;
    int8_t digits[4] = {BLANK, BLANK, BLANK, (int8_t)(value % 10)};
    uint16_t v = value / 10;
    for (int i = 2; i >= 0; i--) {
        if (v > 0) { digits[i] = (int8_t)(v % 10); v /= 10; }
    }
    return show_digits(digits);
}

void SAA1064Minimal::clear() {
    memset(_patterns, 0, sizeof(_patterns));
    _write_digits(1);
}

bool SAA1064Minimal::write_raw(const uint8_t* patterns, size_t len, uint8_t first_digit) {
    if (first_digit < 1 || first_digit > DIGITS || len == 0 || (size_t)(first_digit - 1) + len > DIGITS) return false;
    uint8_t buf[5];
    buf[0] = first_digit;
    memcpy(&buf[1], patterns, len);
    memcpy(&_patterns[first_digit - 1], patterns, len);
    _connection.write(buf, len + 1);
    return true;
}

// SAA1064Full

SAA1064Full::SAA1064Full(Connection& connection) : SAA1064Minimal(connection) {}

void SAA1064Full::_write_control() {
    const uint8_t buf[2] = {SUB_CONTROL, _control};
    _connection.write(buf, sizeof(buf));
}

void SAA1064Full::_set_bit(uint8_t mask, bool on) {
    if (on) _control |= mask;
    else    _control &= (uint8_t)~mask;
    _control &= 0x7F;
    _write_control();
}

void SAA1064Full::set_mode(bool dynamic) { _set_bit(C_DYNAMIC, dynamic); }

void SAA1064Full::blank(bool digits_13, bool digits_24) {
    if (digits_13) _control &= (uint8_t)~C_UNBLANK_13; else _control |= C_UNBLANK_13;
    if (digits_24) _control &= (uint8_t)~C_UNBLANK_24; else _control |= C_UNBLANK_24;
    _control &= 0x7F;
    _write_control();
}

bool SAA1064Full::set_current(uint8_t ma) {
    if (ma > 21 || ma % 3 != 0) return false;
    _control = (uint8_t)((_control & ~C_CURRENT & 0x7F) | ((ma / 3) << 4));
    _write_control();
    return true;
}

void SAA1064Full::segment_test(bool on) { _set_bit(C_TEST, on); }

bool SAA1064Full::set_segment_map(const uint8_t map[8]) {
    for (uint8_t i = 0; i < 8; i++) {
        if (map[i] > 7) return false;
    }
    memcpy(_map, map, 8);
    _rebuild_glyphs();
    return true;
}

bool SAA1064Full::show_digit(uint8_t position, int8_t value, bool dp) {
    if (position < 1 || position > DIGITS) return false;
    uint8_t p = 0;
    if (!_pattern(value, p)) return false;
    if (dp) p |= (uint8_t)(1u << _map[7]);
    _patterns[position - 1] = p;
    const uint8_t buf[2] = {position, p};
    _connection.write(buf, sizeof(buf));
    return true;
}

bool SAA1064Full::set_decimal_point(uint8_t position, bool on) {
    if (position < 1 || position > DIGITS) return false;
    uint8_t bit = (uint8_t)(1u << _map[7]);
    if (on) _patterns[position - 1] |= bit;
    else    _patterns[position - 1] &= (uint8_t)~bit;
    const uint8_t buf[2] = {position, _patterns[position - 1]};
    _connection.write(buf, sizeof(buf));
    return true;
}

uint8_t SAA1064Full::read_status() {
    uint8_t b = 0;
    _connection.read(&b, 1);
    return b;
}

bool SAA1064Full::power_reset_flag() {
    return (read_status() & 0x80) != 0;
}

void SAA1064Full::write_control(uint8_t value) {
    _control = value & 0x7F;
    _write_control();
}
