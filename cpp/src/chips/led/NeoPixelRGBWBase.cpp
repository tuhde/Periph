#include "NeoPixelRGBWBase.h"
#include <string.h>
#include <stdlib.h>

// ── NeoPixelRGBWMinimal ──────────────────────────────────────────────────────

NeoPixelRGBWMinimal::NeoPixelRGBWMinimal(Connection& connection, size_t n, const uint8_t channel_order[4], size_t reset_bytes)
    : _connection(connection), _n(n), _buf(new uint8_t[n * 4 + reset_bytes]()), _reset_bytes(reset_bytes)
{
    _channel_order[0] = channel_order[0];
    _channel_order[1] = channel_order[1];
    _channel_order[2] = channel_order[2];
    _channel_order[3] = channel_order[3];
}

NeoPixelRGBWMinimal::~NeoPixelRGBWMinimal() {
    delete[] _buf;
}

void NeoPixelRGBWMinimal::fill(uint8_t r, uint8_t g, uint8_t b, uint8_t w) {
    uint8_t vals[4] = {r, g, b, w};
    uint8_t w0 = vals[_channel_order[0]];
    uint8_t w1 = vals[_channel_order[1]];
    uint8_t w2 = vals[_channel_order[2]];
    uint8_t w3 = vals[_channel_order[3]];
    for (size_t i = 0; i < _n; i++) {
        _buf[i * 4]     = w0;
        _buf[i * 4 + 1] = w1;
        _buf[i * 4 + 2] = w2;
        _buf[i * 4 + 3] = w3;
    }
    _send();
}

void NeoPixelRGBWMinimal::off() {
    fill(0, 0, 0, 0);
}

void NeoPixelRGBWMinimal::_send() {
    _connection.write(_buf, _n * 4 + _reset_bytes);
}

// ── NeoPixelRGBWFull ─────────────────────────────────────────────────────────

NeoPixelRGBWFull::NeoPixelRGBWFull(Connection& connection, size_t n, const uint8_t channel_order[4], size_t reset_bytes)
    : NeoPixelRGBWMinimal(connection, n, channel_order, reset_bytes), _brightness(255)
{}

void NeoPixelRGBWFull::set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b, uint8_t w) {
    if (index >= _n) index = _n - 1;
    uint8_t vals[4] = {r, g, b, w};
    _buf[index * 4]     = vals[_channel_order[0]];
    _buf[index * 4 + 1] = vals[_channel_order[1]];
    _buf[index * 4 + 2] = vals[_channel_order[2]];
    _buf[index * 4 + 3] = vals[_channel_order[3]];
}

void NeoPixelRGBWFull::show() {
    size_t total = _n * 4 + _reset_bytes;
    if (_brightness == 255) {
        _connection.write(_buf, total);
        return;
    }
    uint8_t* scaled = new uint8_t[total]();
    for (size_t i = 0; i < _n * 4; i++) {
        scaled[i] = (uint8_t)((uint16_t)_buf[i] * _brightness / 255u);
    }
    _connection.write(scaled, total);
    delete[] scaled;
}

uint8_t NeoPixelRGBWFull::get_brightness() const {
    return _brightness;
}

void NeoPixelRGBWFull::set_brightness(uint8_t value) {
    _brightness = value;
}

void NeoPixelRGBWFull::rotate(size_t steps) {
    if (_n == 0) return;
    steps = steps % _n;
    if (steps == 0) return;
    size_t bytes = steps * 4;
    size_t pixel_bytes = _n * 4;
    uint8_t* tmp = new uint8_t[bytes];
    memcpy(tmp, _buf, bytes);
    memmove(_buf, _buf + bytes, pixel_bytes - bytes);
    memcpy(_buf + pixel_bytes - bytes, tmp, bytes);
    delete[] tmp;
}

void NeoPixelRGBWFull::fill_hsv(float h, float s, float v) {
    uint8_t r, g, b;
    neopixel_hsv_to_rgb(h, s, v, r, g, b);
    fill(r, g, b, 0);
}
