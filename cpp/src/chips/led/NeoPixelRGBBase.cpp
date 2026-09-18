#include "NeoPixelRGBBase.h"
#include <string.h>
#include <stdlib.h>

// ── NeoPixelRGBMinimal ───────────────────────────────────────────────────────

NeoPixelRGBMinimal::NeoPixelRGBMinimal(Connection& connection, size_t n, const uint8_t channel_order[3], size_t reset_bytes)
    : _connection(connection), _n(n), _buf(new uint8_t[n * 3 + reset_bytes]()), _reset_bytes(reset_bytes)
{
    _channel_order[0] = channel_order[0];
    _channel_order[1] = channel_order[1];
    _channel_order[2] = channel_order[2];
}

NeoPixelRGBMinimal::~NeoPixelRGBMinimal() {
    delete[] _buf;
}

void NeoPixelRGBMinimal::fill(uint8_t r, uint8_t g, uint8_t b) {
    uint8_t vals[3] = {r, g, b};
    uint8_t w0 = vals[_channel_order[0]];
    uint8_t w1 = vals[_channel_order[1]];
    uint8_t w2 = vals[_channel_order[2]];
    for (size_t i = 0; i < _n; i++) {
        _buf[i * 3]     = w0;
        _buf[i * 3 + 1] = w1;
        _buf[i * 3 + 2] = w2;
    }
    _send();
}

void NeoPixelRGBMinimal::off() {
    fill(0, 0, 0);
}

void NeoPixelRGBMinimal::_send() {
    _connection.write(_buf, _n * 3 + _reset_bytes);
}

// ── NeoPixelRGBFull ──────────────────────────────────────────────────────────

NeoPixelRGBFull::NeoPixelRGBFull(Connection& connection, size_t n, const uint8_t channel_order[3], size_t reset_bytes)
    : NeoPixelRGBMinimal(connection, n, channel_order, reset_bytes), _brightness(255)
{}

void NeoPixelRGBFull::set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b) {
    if (index >= _n) index = _n - 1;
    uint8_t vals[3] = {r, g, b};
    _buf[index * 3]     = vals[_channel_order[0]];
    _buf[index * 3 + 1] = vals[_channel_order[1]];
    _buf[index * 3 + 2] = vals[_channel_order[2]];
}

void NeoPixelRGBFull::show() {
    size_t total = _n * 3 + _reset_bytes;
    if (_brightness == 255) {
        _connection.write(_buf, total);
        return;
    }
    uint8_t* scaled = new uint8_t[total]();
    for (size_t i = 0; i < _n * 3; i++) {
        scaled[i] = (uint8_t)((uint16_t)_buf[i] * _brightness / 255u);
    }
    _connection.write(scaled, total);
    delete[] scaled;
}

uint8_t NeoPixelRGBFull::get_brightness() const {
    return _brightness;
}

void NeoPixelRGBFull::set_brightness(uint8_t value) {
    _brightness = value;
}

void NeoPixelRGBFull::rotate(size_t steps) {
    if (_n == 0) return;
    steps = steps % _n;
    if (steps == 0) return;
    size_t bytes = steps * 3;
    size_t total = _n * 3;
    uint8_t* tmp = new uint8_t[bytes];
    memcpy(tmp, _buf, bytes);
    memmove(_buf, _buf + bytes, total - bytes);
    memcpy(_buf + total - bytes, tmp, bytes);
    delete[] tmp;
}

void NeoPixelRGBFull::fill_hsv(float h, float s, float v) {
    uint8_t r, g, b;
    neopixel_hsv_to_rgb(h, s, v, r, g, b);
    fill(r, g, b);
}
