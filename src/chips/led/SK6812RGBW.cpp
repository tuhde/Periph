#include "SK6812RGBW.h"
#include <string.h>
#include <stdlib.h>

// ── SK6812RGBWMinimal ────────────────────────────────────────────────────────

SK6812RGBWMinimal::SK6812RGBWMinimal(Transport& transport, size_t n)
    : _transport(transport), _n(n), _buf(new uint8_t[n * 4 + 24]())
{}

SK6812RGBWMinimal::~SK6812RGBWMinimal() {
    delete[] _buf;
}

void SK6812RGBWMinimal::fill(uint8_t r, uint8_t g, uint8_t b, uint8_t w) {
    for (size_t i = 0; i < _n; i++) {
        _buf[i * 4]     = g;
        _buf[i * 4 + 1] = r;
        _buf[i * 4 + 2] = b;
        _buf[i * 4 + 3] = w;
    }
    _send();
}

void SK6812RGBWMinimal::off() {
    fill(0, 0, 0, 0);
}

void SK6812RGBWMinimal::_send() {
    _transport.write(_buf, _n * 4 + 24);
}

// ── SK6812RGBWFull ───────────────────────────────────────────────────────────

SK6812RGBWFull::SK6812RGBWFull(Transport& transport, size_t n)
    : SK6812RGBWMinimal(transport, n), _brightness(255)
{}

void SK6812RGBWFull::set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b, uint8_t w) {
    if (index >= _n) index = _n - 1;
    _buf[index * 4]     = g;
    _buf[index * 4 + 1] = r;
    _buf[index * 4 + 2] = b;
    _buf[index * 4 + 3] = w;
}

void SK6812RGBWFull::show() {
    size_t total = _n * 4 + 24;
    if (_brightness == 255) {
        _transport.write(_buf, total);
        return;
    }
    uint8_t* scaled = new uint8_t[total]();
    for (size_t i = 0; i < _n * 4; i++) {
        scaled[i] = (uint8_t)((uint16_t)_buf[i] * _brightness / 255u);
    }
    _transport.write(scaled, total);
    delete[] scaled;
}

uint8_t SK6812RGBWFull::get_brightness() const {
    return _brightness;
}

void SK6812RGBWFull::set_brightness(uint8_t value) {
    _brightness = value;
}

void SK6812RGBWFull::rotate(size_t steps) {
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

void SK6812RGBWFull::fill_hsv(float h, float s, float v) {
    uint8_t r, g, b;
    neopixel_hsv_to_rgb(h, s, v, r, g, b);
    fill(r, g, b, 0);
}
